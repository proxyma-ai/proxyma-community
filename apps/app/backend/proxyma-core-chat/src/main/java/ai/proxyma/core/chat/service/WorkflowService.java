package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.exception.WorkflowAccessDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// CRUD + system-prompt formatting for Workflows. Deliberately built as a near-copy of
// ChatSkillService rather than a generalization of it, and stored the same way: one markdown file
// per workflow at {workflowsDir}/{id}.md, frontmatter plus body, no database table. The reasoning
// is the one recorded there - a definition should be as portable and diffable as a plain markdown
// file - and it applies with more force here, because a workflow is a small program and the thing
// you most want from a program's source is a readable diff of what changed.
//
// What a workflow is, and why it is not just a skill:
//
//   A SKILL is instructions. The agent reads it and then decides what to do, so the shape of the
//   work is not known until it happens.
//
//   A WORKFLOW is a program. The author fixes the steps and their order; the agent's freedom is
//   scoped to the inside of one step. That is what lets the UI draw the whole plan BEFORE anything
//   runs, which is the entire point of the feature - see proxyma-docs/proposals/workflows.md.
//
// Everything below follows from that. The frontmatter is the same format ChatSkillService.parse()
// reads (key: value, lists comma-separated, every field optional with a graceful default) so the
// two stay readable side by side; the body is where they differ, because a workflow's body is a
// sequence of `##`-headed steps rather than one block of prose.
//
// Two keys a skill does not have: `provider` and `model`, the pair every step runs on. They are a
// workflow's business and not a skill's for the reason the step cap exists - a run is N turns, so
// the model choice is multiplied by N, while a skill is read inside one turn on whatever model that
// turn was already using. See normalizeModelChoice().
//
// Visibility and mutation follow the skill rule exactly: visible if userId == null OR userId is
// the requester's OR shared; editable/deletable only by the owner or a Super Admin. There is no
// `official`/product-built-in concept here - workflows have no seeder, so there is nothing to
// restore a default from and nothing that must be undeletable.
@Service
public class WorkflowService {

    // Matches "---\n<header>\n---\n<body>" - the same pattern and the same tolerance of either
    // line ending ChatSkillService uses. Anything that doesn't match degrades to an unnamed
    // workflow with the whole file as its body, rather than throwing.
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\r?\\n(.*?)\\r?\\n---\\r?\\n?(.*)\\z", Pattern.DOTALL);

    // A step is an H2 heading. H2 rather than H1 because the body is a fragment inside a larger
    // document, and rather than H3+ because step instructions are prose that may well want
    // sub-headings of their own - taking `###` would make a step unable to structure itself.
    private static final Pattern STEP_HEADING = Pattern.compile("^##[ \\t]+(.+?)[ \\t]*$", Pattern.MULTILINE);

    // Same caps, same silent-truncation behaviour as ChatSkillService, for the same reason.
    private static final int MAX_TAGS = 20;
    private static final int MAX_TOOLS = 50;
    private static final int MAX_CONNECTOR_NAMES = 20;

    // Steps are NOT capped by silent truncation, unlike the lists above. Dropping a tag past the
    // cap loses a label; dropping a step past the cap changes what the workflow DOES while still
    // reporting success, and the user would find out by watching a run end early. So this is
    // enforced by rejecting the save - the one place in this class that refuses input rather than
    // degrading gracefully. The number is a spend ceiling, not a storage one: every step is another
    // turn carrying all prior context (see the proposal's note that a five-step workflow costs
    // closer to fifteen), so an unbounded step list is an unbounded bill per invocation.
    public static final int MAX_STEPS = 20;

    // Cap on one step's title. It is rendered in a table row that must not wrap - the same
    // constraint toolActivity's MAX_DETAIL exists for.
    private static final int MAX_STEP_TITLE_CHARS = 80;

    private final Path workflowsDir;

    public WorkflowService(
            @Value("${proxyma.workflows-dir:${user.home}/.proxyma/workflows}") String workflowsDir) {
        this.workflowsDir = Path.of(workflowsDir);
    }

    /**
     * One step of a workflow. `index` is 0-based and positional - it is the step's identity for the
     * whole run, including the SSE events and the table row, so it is derived from file order and
     * never stored.
     */
    public record WorkflowStep(int index, String title, String instruction) {}

    public record WorkflowSummary(String id, String name, String shortDescription,
            String userId, boolean shared, List<String> tags, int stepCount,
            boolean mine, boolean canEdit) {}

    /**
     * @param provider the id of the provider instance every step runs on, or null to run on
     *                 whatever the chat role is pointed at. See {@link #normalizeModelChoice}.
     * @param model    the model name every step runs on, or null to run on the chat role's model.
     */
    public record WorkflowDetail(String id, String name, String shortDescription,
            String preamble, List<WorkflowStep> steps, String userId, boolean shared,
            List<String> tags, List<String> tools, List<String> connectorNames,
            String provider, String model,
            boolean mine, boolean canEdit) {}

    private record ParsedWorkflow(String name, String shortDescription, String body,
            String userId, boolean shared, List<String> tags, List<String> tools,
            List<String> connectorNames, String provider, String model) {}

    private record IdParsed(String id, ParsedWorkflow parsed) {}

    /** Thrown by create/update when the step list is empty or past {@link #MAX_STEPS}. */
    public static class InvalidWorkflowException extends RuntimeException {
        public InvalidWorkflowException(String message) {
            super(message);
        }
    }

    public List<WorkflowSummary> list(String requesterId, boolean callerIsSuperAdmin) {
        return listFiles().stream()
                .map(this::readIdParsed)
                .filter(Objects::nonNull)
                .filter(ip -> isVisible(ip.parsed(), requesterId))
                .map(ip -> toSummary(ip.id(), ip.parsed(), requesterId, callerIsSuperAdmin))
                .sorted(Comparator.comparing(WorkflowSummary::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // Never distinguishes "no such id" from "exists but not visible to you" - both throw the same
    // NoSuchElementException, so a private workflow's existence is never leaked to a caller it
    // isn't visible to. Same rule as ChatSkillService.get().
    public WorkflowDetail get(String id, String requesterId, boolean callerIsSuperAdmin) {
        ParsedWorkflow parsed = readVisible(id, requesterId);
        return toDetail(id, parsed, requesterId, callerIsSuperAdmin);
    }

    public WorkflowDetail create(String name, String shortDescription, String preamble,
            List<WorkflowStep> steps, String requesterId, List<String> tags, List<String> tools,
            List<String> connectorNames, String provider, String model) {
        String body = composeBody(preamble, validateSteps(steps));
        String[] modelChoice = normalizeModelChoice(provider, model);
        String id = uniqueId(slugify(name));
        ParsedWorkflow parsed = new ParsedWorkflow(name, shortDescription, body, requesterId, false,
                cap(tags, MAX_TAGS), cap(tools, MAX_TOOLS), cap(connectorNames, MAX_CONNECTOR_NAMES),
                modelChoice[0], modelChoice[1]);
        write(id, parsed);
        // The creator always owns what they just created, so canEdit is true regardless of the
        // caller's admin status - no need to resolve callerIsSuperAdmin for this call.
        return toDetail(id, parsed, requesterId, false);
    }

    public WorkflowDetail update(String id, String name, String shortDescription, String preamble,
            List<WorkflowStep> steps, String requesterId, boolean callerIsSuperAdmin,
            List<String> tags, List<String> tools, List<String> connectorNames, Boolean shared,
            String provider, String model) {
        ParsedWorkflow existing = readVisible(id, requesterId);
        if (!canEdit(existing, requesterId, callerIsSuperAdmin)) {
            throw new WorkflowAccessDeniedException("Not permitted to edit workflow: " + id);
        }
        String body = composeBody(preamble, validateSteps(steps));
        String[] modelChoice = normalizeModelChoice(provider, model);
        ParsedWorkflow parsed = new ParsedWorkflow(name, shortDescription, body,
                existing.userId(), shared != null ? shared : existing.shared(),
                cap(tags, MAX_TAGS), cap(tools, MAX_TOOLS), cap(connectorNames, MAX_CONNECTOR_NAMES),
                modelChoice[0], modelChoice[1]);
        write(id, parsed);
        return toDetail(id, parsed, requesterId, callerIsSuperAdmin);
    }

    public void delete(String id, String requesterId, boolean callerIsSuperAdmin) {
        ParsedWorkflow existing = readVisible(id, requesterId);
        if (!canEdit(existing, requesterId, callerIsSuperAdmin)) {
            throw new WorkflowAccessDeniedException("Not permitted to delete workflow: " + id);
        }
        try {
            Files.deleteIfExists(path(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Resolves a workflow by the NAME the composer's "/name" chip carries, applying the visibility
     * rule. Empty for a name with nothing visible behind it, which the send path treats as "not a
     * workflow" and passes through as ordinary text rather than failing the turn - the same
     * fail-soft stance ChatSkillService.resolveIdsForNames takes for a stale chip left in a draft.
     */
    public Optional<WorkflowDetail> findByNameVisibleTo(String name, String requesterId) {
        if (name == null || name.isBlank()) return Optional.empty();
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (IdParsed ip : listFiles().stream().map(this::readIdParsed).filter(Objects::nonNull).toList()) {
            if (ip.parsed().name().toLowerCase(Locale.ROOT).equals(wanted) && isVisible(ip.parsed(), requesterId)) {
                return Optional.of(toDetail(ip.id(), ip.parsed(), requesterId, false));
            }
        }
        return Optional.empty();
    }

    /**
     * Every workflow name visible to this requester, lowercased. Used to reject a SKILL whose name
     * would collide with a workflow (and vice versa) at creation time: `/` is one namespace to the
     * person typing into it, and a silent winner between a skill and a workflow of the same name is
     * a bug report nobody will be able to reproduce.
     */
    public Set<String> visibleNamesLowercase(String requesterId) {
        Set<String> names = new HashSet<>();
        for (IdParsed ip : listFiles().stream().map(this::readIdParsed).filter(Objects::nonNull).toList()) {
            if (isVisible(ip.parsed(), requesterId) && !ip.parsed().name().isBlank()) {
                names.add(ip.parsed().name().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    // Opens the block this renders, mirroring ChatSkillService.SKILLS_BLOCK_MARKER and stripped out
    // of any injected body for the same reason: so no user-authored text can forge a boundary.
    public static final String WORKFLOWS_BLOCK_MARKER = "<!-- proxyma:workflows -->";

    private static final int MAX_DESCRIPTION_CHARS = 300;
    private static final int MAX_BLOCK_CHARS = 4000;

    /**
     * The listing appended to the system prompt, one line per workflow visible to this requester.
     *
     * <p>This MUST be appended at the same point ChatSkillService.formatForSystemPrompt() is - once,
     * at conversation start - and never rewritten mid-conversation. That constraint is not
     * stylistic: the system prompt is the provider's cached prompt prefix, and rewriting it
     * cascade-invalidates the cache for the whole conversation (see AnthropicAdapter's cache
     * strategy). A workflow created mid-conversation is therefore invisible until the next one,
     * exactly as a skill created mid-conversation is.
     *
     * <p>Unlike the skills block, this does NOT tell the model to load anything. A workflow is not
     * something the agent decides to read - it is something the USER invokes, and the run is driven
     * from outside the model's control. The listing exists so the agent can say "there is a
     * workflow for that, type /Name to run it" rather than improvising the same job badly.
     */
    public String formatForSystemPrompt(String requesterId) {
        List<IdParsed> visible = listFiles().stream()
                .map(this::readIdParsed)
                .filter(Objects::nonNull)
                .filter(ip -> isVisible(ip.parsed(), requesterId))
                .filter(ip -> !ip.parsed().name().isBlank())
                .sorted(Comparator.comparing(ip -> ip.parsed().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (visible.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(WORKFLOWS_BLOCK_MARKER).append('\n')
                .append("## Available Workflows\n")
                .append("These are multi-step procedures the user can run by typing \"/\" followed by the ")
                .append("workflow's exact name. You cannot start one yourself and you are not running one ")
                .append("now unless you were told so explicitly. When a request matches one, say it exists ")
                .append("and name it, then either do the task normally or let the user choose to run it.\n");
        for (IdParsed ip : visible) {
            String line = "- **" + ip.parsed().name() + "** ("
                    + parseSteps(stripMarker(ip.parsed().body())).size() + " steps): "
                    + truncate(ip.parsed().shortDescription(), MAX_DESCRIPTION_CHARS) + "\n";
            if (sb.length() + line.length() > MAX_BLOCK_CHARS) break;
            sb.append(line);
        }
        return sb.toString();
    }

    // - parsing -

    /**
     * Splits a body into its preamble and its steps.
     *
     * <p>A body with no `##` heading at all is ONE step whose instruction is the whole body and
     * whose title is empty (the caller substitutes the workflow's own name). That is deliberate:
     * such a file is a skill with a workflow's frontmatter, and answering it with an error would be
     * a worse outcome than running it as the single-step procedure it plainly describes. It matches
     * parse()'s existing stance that a malformed file degrades rather than throws.
     */
    static List<WorkflowStep> parseSteps(String body) {
        if (body == null || body.isBlank()) return List.of();
        Matcher m = STEP_HEADING.matcher(body);
        List<WorkflowStep> steps = new ArrayList<>();
        List<int[]> bounds = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            bounds.add(new int[]{m.start(), m.end()});
            titles.add(m.group(1).strip());
        }
        if (bounds.isEmpty()) {
            String only = body.strip();
            return only.isEmpty() ? List.of() : List.of(new WorkflowStep(0, "", only));
        }
        for (int i = 0; i < bounds.size(); i++) {
            int from = bounds.get(i)[1];
            int to = i + 1 < bounds.size() ? bounds.get(i + 1)[0] : body.length();
            steps.add(new WorkflowStep(i, titles.get(i), body.substring(from, to).strip()));
        }
        return List.copyOf(steps);
    }

    /** Everything before the first `##`, which is the context every step is given. */
    static String parsePreamble(String body) {
        if (body == null || body.isBlank()) return "";
        Matcher m = STEP_HEADING.matcher(body);
        return (m.find() ? body.substring(0, m.start()) : "").strip();
    }

    // Rebuilds the markdown body from the editor's structured step list. Round-trips with
    // parseSteps/parsePreamble, which is what lets the same file be edited in the UI and by hand.
    private static String composeBody(String preamble, List<WorkflowStep> steps) {
        StringBuilder sb = new StringBuilder();
        if (preamble != null && !preamble.isBlank()) {
            sb.append(preamble.strip()).append("\n\n");
        }
        for (WorkflowStep step : steps) {
            sb.append("## ").append(sanitizeLine(step.title())).append('\n');
            String instruction = step.instruction() == null ? "" : step.instruction().strip();
            if (!instruction.isEmpty()) sb.append(instruction).append('\n');
            sb.append('\n');
        }
        return sb.toString().stripTrailing() + "\n";
    }

    // Rejects rather than truncates - see MAX_STEPS. Also strips a step whose title AND instruction
    // are both blank, which is what an editor row the user added and never filled in looks like;
    // that is a UI artifact rather than an authored step, so dropping it silently is right where
    // dropping a real one would not be.
    private static List<WorkflowStep> validateSteps(List<WorkflowStep> steps) {
        List<WorkflowStep> kept = new ArrayList<>();
        if (steps != null) {
            for (WorkflowStep s : steps) {
                if (s == null) continue;
                String title = s.title() == null ? "" : s.title().strip();
                String instruction = s.instruction() == null ? "" : s.instruction().strip();
                if (title.isEmpty() && instruction.isEmpty()) continue;
                if (title.length() > MAX_STEP_TITLE_CHARS) {
                    title = title.substring(0, MAX_STEP_TITLE_CHARS - 3) + "...";
                }
                // A blank title on a step that HAS an instruction gets a positional one rather than
                // being written as-is. composeBody would otherwise emit a bare "## " line, and
                // STEP_HEADING requires at least one character after the space - so that step would
                // not match on the next read, and its instruction would be silently absorbed into
                // the previous step's body. Losing a step on a round-trip through the editor is far
                // worse than naming it something dull.
                if (title.isEmpty()) {
                    title = "Step " + (kept.size() + 1);
                }
                kept.add(new WorkflowStep(kept.size(), title, instruction));
            }
        }
        if (kept.isEmpty()) {
            throw new InvalidWorkflowException("A workflow needs at least one step.");
        }
        if (kept.size() > MAX_STEPS) {
            throw new InvalidWorkflowException(
                    "A workflow can have at most " + MAX_STEPS + " steps; this one has " + kept.size()
                            + ". Every step is another turn carrying all the previous ones, so a longer "
                            + "one costs more than it looks. Split it into two workflows instead.");
        }
        return List.copyOf(kept);
    }

    /**
     * The provider/model pair a workflow's steps run on, normalized to {@code [provider, model]}
     * with a blank meaning "inherit".
     *
     * <p>WHY A WORKFLOW CARRIES A PROVIDER AND NOT JUST A MODEL. Since provider instances landed
     * (#167) a provider is a name, a protocol and a base URL that somebody configured, so a model
     * name on its own only means something relative to one of them. A workflow declaring
     * {@code model: gpt-4o-mini} against a chat role pointed at Anthropic would build without
     * complaint and then fail EVERY step with a 404 from a provider that has never heard of the
     * model - and the cheap model this field exists to select usually lives on a different instance
     * anyway (a local runtime, a gateway), not on the one the main chat model came from.
     *
     * <p>Model with no provider is allowed and means "the chat role's provider, this model" - the
     * common case of one account serving both an expensive and a cheap model. Provider with no
     * model is REFUSED: an instance cannot be called without naming what to call on it, and picking
     * one silently (the chat role's model name, on a provider that may not serve it) is the exact
     * failure the paragraph above describes.
     */
    private static String[] normalizeModelChoice(String provider, String model) {
        String p = provider == null || provider.isBlank() ? null : provider.trim();
        String m = model == null || model.isBlank() ? null : model.trim();
        if (p != null && m == null) {
            throw new InvalidWorkflowException(
                    "This workflow names a provider to run on but no model. Pick a model from that "
                            + "provider, or clear the provider to run on the main chat model.");
        }
        return new String[]{p, m};
    }

    private ParsedWorkflow readVisible(String id, String requesterId) {
        Path path = path(id);
        if (!Files.isRegularFile(path)) throw new NoSuchElementException("No such workflow: " + id);
        ParsedWorkflow parsed = parse(readFile(path));
        if (!isVisible(parsed, requesterId)) throw new NoSuchElementException("No such workflow: " + id);
        return parsed;
    }

    private ParsedWorkflow parse(String content) {
        Matcher m = FRONTMATTER.matcher(content);
        if (!m.matches()) {
            return new ParsedWorkflow("", "", content, null, false, List.of(), List.of(), List.of(),
                    null, null);
        }
        String name = "";
        String shortDescription = "";
        String userId = null;
        boolean shared = false;
        List<String> tags = List.of();
        List<String> tools = List.of();
        List<String> connectorNames = List.of();
        String provider = null;
        String model = null;
        for (String line : m.group(1).split("\\r?\\n")) {
            int idx = line.indexOf(':');
            if (idx < 0) continue;
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            if ("name".equals(key)) name = value;
            else if ("shortDescription".equals(key)) shortDescription = value;
            else if ("userId".equals(key)) userId = value.isEmpty() ? null : value;
            else if ("shared".equals(key)) shared = Boolean.parseBoolean(value);
            else if ("tags".equals(key)) tags = parseCsv(value);
            else if ("tools".equals(key)) tools = parseCsv(value);
            else if ("connectorNames".equals(key)) connectorNames = parseCsv(value);
            else if ("provider".equals(key)) provider = value.isEmpty() ? null : value;
            else if ("model".equals(key)) model = value.isEmpty() ? null : value;
        }
        // A hand-edited file naming a provider but no model is READ as "no override" rather than
        // rejected, unlike the same pair arriving from the editor - parse() degrades, it never
        // throws, and the alternative is a workflow that cannot be opened to be repaired.
        if (model == null) provider = null;
        return new ParsedWorkflow(name, shortDescription, m.group(2), userId, shared, tags, tools,
                connectorNames, provider, model);
    }

    private void write(String id, ParsedWorkflow parsed) {
        StringBuilder header = new StringBuilder();
        header.append("name: ").append(sanitizeLine(parsed.name())).append('\n');
        header.append("shortDescription: ").append(sanitizeLine(parsed.shortDescription())).append('\n');
        if (parsed.userId() != null) {
            header.append("userId: ").append(sanitizeLine(parsed.userId())).append('\n');
        }
        header.append("shared: ").append(parsed.shared()).append('\n');
        if (!parsed.tags().isEmpty()) {
            header.append("tags: ").append(String.join(",", parsed.tags())).append('\n');
        }
        if (!parsed.tools().isEmpty()) {
            header.append("tools: ").append(String.join(",", parsed.tools())).append('\n');
        }
        if (!parsed.connectorNames().isEmpty()) {
            header.append("connectorNames: ").append(String.join(",", parsed.connectorNames())).append('\n');
        }
        // Both omitted entirely when there is no override, so a workflow that runs on the chat model
        // says nothing about a model at all - an empty `model:` line would read as a deliberate
        // choice of nothing, and would survive being hand-edited into one.
        if (parsed.model() != null) {
            if (parsed.provider() != null) {
                header.append("provider: ").append(sanitizeLine(parsed.provider())).append('\n');
            }
            header.append("model: ").append(sanitizeLine(parsed.model())).append('\n');
        }
        String content = "---\n" + header + "---\n" + (parsed.body() == null ? "" : parsed.body());
        try {
            Files.createDirectories(workflowsDir);
            Files.writeString(path(id), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // - helpers, all mirroring ChatSkillService's own -

    private static boolean isVisible(ParsedWorkflow parsed, String requesterId) {
        return parsed.userId() == null
                || parsed.userId().equals(requesterId)
                || parsed.shared();
    }

    private static boolean mine(ParsedWorkflow parsed, String requesterId) {
        return parsed.userId() != null && parsed.userId().equals(requesterId);
    }

    private static boolean canEdit(ParsedWorkflow parsed, String requesterId, boolean callerIsSuperAdmin) {
        return callerIsSuperAdmin || (parsed.userId() != null && parsed.userId().equals(requesterId));
    }

    private WorkflowSummary toSummary(String id, ParsedWorkflow p, String requesterId, boolean superAdmin) {
        return new WorkflowSummary(id, p.name(), p.shortDescription(), p.userId(), p.shared(), p.tags(),
                parseSteps(stripMarker(p.body())).size(), mine(p, requesterId),
                canEdit(p, requesterId, superAdmin));
    }

    private WorkflowDetail toDetail(String id, ParsedWorkflow p, String requesterId, boolean superAdmin) {
        String body = stripMarker(p.body());
        List<WorkflowStep> steps = parseSteps(body);
        // A single untitled step is the no-heading case (see parseSteps): give it the workflow's own
        // name so the table row is never blank. Done here rather than in parseSteps because only
        // this layer knows the workflow's name.
        if (steps.size() == 1 && steps.get(0).title().isBlank()) {
            steps = List.of(new WorkflowStep(0, p.name().isBlank() ? "Run" : p.name(), steps.get(0).instruction()));
        }
        return new WorkflowDetail(id, p.name(), p.shortDescription(), parsePreamble(body), steps,
                p.userId(), p.shared(), p.tags(), p.tools(), p.connectorNames(),
                p.provider(), p.model(),
                mine(p, requesterId), canEdit(p, requesterId, superAdmin));
    }

    private static String stripMarker(String body) {
        return body == null ? "" : body.replace(WORKFLOWS_BLOCK_MARKER, "");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ').strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
    }

    private List<Path> listFiles() {
        if (!Files.isDirectory(workflowsDir)) return List.of();
        try (Stream<Path> stream = Files.list(workflowsDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private IdParsed readIdParsed(Path path) {
        ParsedWorkflow parsed = parse(readFile(path));
        return new IdParsed(path.getFileName().toString().replaceFirst("\\.md$", ""), parsed);
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> parseCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<String> cap(List<String> values, int max) {
        if (values == null) return List.of();
        List<String> deduped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String v : values) {
            if (v == null) continue;
            String trimmed = v.trim();
            if (trimmed.isEmpty() || !seen.add(trimmed)) continue;
            deduped.add(trimmed);
            if (deduped.size() == max) break;
        }
        return List.copyOf(deduped);
    }

    // An id that arrives as a @PathVariable. Spring Security's firewall refuses encoded separators,
    // but only on Enterprise Server - Desktop and Home Server run without it, and WebFlux decodes
    // %2F inside a path variable - so this check is what keeps the id to a file of this folder.
    // Rejects rather than sanitizes: an id with a separator in it is not a request to be repaired
    // into a valid one. Shared with the skills, see DefinitionFiles.
    private Path path(String id) {
        return DefinitionFiles.jailed(workflowsDir, id, "workflow");
    }

    // Frontmatter values and step titles are single-line by contract - strip any embedded newline
    // defensively rather than letting it corrupt the header, or split one step into two, on the
    // next parse.
    private static String sanitizeLine(String s) {
        return s == null ? "" : s.replace("\r", " ").replace("\n", " ").trim();
    }

    private static String slugify(String name) {
        String base = name == null ? "" : name.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return base.isEmpty() ? "workflow" : base;
    }

    private String uniqueId(String base) {
        String id = base;
        int suffix = 2;
        while (Files.exists(path(id))) {
            id = base + "-" + suffix++;
        }
        return id;
    }
}
