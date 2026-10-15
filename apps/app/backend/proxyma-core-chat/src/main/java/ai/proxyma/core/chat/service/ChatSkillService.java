package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.external.ExternalToolSource;
import ai.proxyma.core.chat.exception.SkillAccessDeniedException;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

// CRUD + system-prompt formatting for chat Skills. A skill is entirely a single markdown file
// on disk ({skillsDir}/{id}.md) - name/shortDescription/detail live as before, plus (as of the
// ownership/visibility/sharing feature) a handful of new frontmatter fields: userId, shared,
// official, tags, tools, connectorNames. Every field is optional with a graceful default, so every
// skill file that predates this feature (only name/shortDescription) still parses cleanly -
// see parse() below. Still no database table: a skill is meant to be as portable and diffable
// as a plain markdown file, not a DB row split across a JPA entity and a file.
//
// Visibility rule: a skill is visible to a requester if userId == null (official/seeded, owned
// by nobody) OR userId equals the requester's own id OR shared == true. Mutation (update/delete)
// is allowed only if the caller is a Super Admin OR the skill's userId equals the requester's own
// id - nobody else may edit/delete a skill, even one it can see because it's shared.
// `mine`/`canEdit` on the returned records are computed relative to the requester at read time,
// never persisted.
@Service
public class ChatSkillService {

    // Matches "---\n<header>\n---\n<body>" - written by writeSkill() below, tolerant of either
    // line ending. Anything that doesn't match (e.g. a stray non-conforming file dropped into
    // skillsDir by hand) degrades to an unnamed skill with the whole file as its body, rather
    // than throwing.
    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\r?\\n(.*?)\\r?\\n---\\r?\\n?(.*)\\z", Pattern.DOTALL);

    // Server-side caps on user-suppliable list fields, applied (by silent truncation, not
    // rejection) in create()/update() - every user can now create skills, not just admins, so an
    // unbounded tags/tools list could otherwise bloat formatForSystemPrompt()'s output or the
    // gating map indefinitely.
    private static final int MAX_TAGS = 20;
    private static final int MAX_TOOLS = 50;
    private static final int MAX_CONNECTOR_NAMES = 20;

    private final Path skillsDir;
    private final DefaultSkillRegistry defaultSkills;

    public ChatSkillService(@Value("${proxyma.skills-dir:${user.home}/.proxyma/skills}") String skillsDir,
                            DefaultSkillRegistry defaultSkills) {
        this.skillsDir = Path.of(skillsDir);
        this.defaultSkills = defaultSkills;
    }

    // canDelete is deliberately separate from canEdit rather than folded into it: a product skill
    // is editable but permanent (see canDelete() below), so the two answers genuinely differ and a
    // caller that conflated them would render a delete button that always 403s.
    public record SkillSummary(String id, String name, String shortDescription,
            String userId, boolean shared, boolean official,
            List<String> tags, boolean mine, boolean canEdit, boolean canDelete,
            boolean hasDefault) {}

    public record SkillDetail(String id, String name, String shortDescription, String detail,
            String userId, boolean shared, boolean official,
            List<String> tags, List<String> tools, List<String> connectorNames, boolean mine,
            boolean canEdit, boolean canDelete, boolean hasDefault) {}

    private record ParsedSkill(String name, String shortDescription, String detail,
            String userId, boolean shared, boolean official,
            List<String> tags, List<String> tools, List<String> connectorNames) {}

    private record IdParsed(String id, ParsedSkill parsed) {}

    public List<SkillSummary> list(String requesterId, boolean callerIsSuperAdmin) {
        return listFiles().stream()
                .map(this::readIdParsed)
                .filter(Objects::nonNull)
                .filter(ip -> isVisible(ip.parsed(), requesterId))
                .map(ip -> toSummary(ip.id(), ip.parsed(), requesterId, callerIsSuperAdmin))
                .sorted(Comparator.comparing(SkillSummary::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // Never distinguishes "no such id" from "exists but not visible to you" - both throw the same
    // NoSuchElementException, so a private skill's existence is never leaked to a caller it isn't
    // visible to.
    public SkillDetail get(String id, String requesterId, boolean callerIsSuperAdmin) {
        Path path = skillPath(id);
        if (!Files.isRegularFile(path)) throw new NoSuchElementException("No such skill: " + id);
        ParsedSkill parsed = parse(readFile(path));
        if (!isVisible(parsed, requesterId)) throw new NoSuchElementException("No such skill: " + id);
        return toDetail(id, parsed, requesterId, callerIsSuperAdmin);
    }

    public SkillDetail create(String name, String shortDescription, String detail, String requesterId,
            List<String> tags, List<String> tools, List<String> connectorNames) {
        String id = uniqueId(slugify(name));
        ParsedSkill parsed = new ParsedSkill(name, shortDescription, detail == null ? "" : detail,
                requesterId, false, false, cap(tags, MAX_TAGS), cap(tools, MAX_TOOLS),
                cap(connectorNames, MAX_CONNECTOR_NAMES));
        writeSkill(id, parsed);
        // The creator always owns what they just created, so canEdit is true regardless of the
        // caller's admin status - no need to resolve callerIsSuperAdmin for this call.
        return toDetail(id, parsed, requesterId, false);
    }

    // shared is owner-editable like every other field here - any successful caller of update()
    // already passed the canEdit check below.
    public SkillDetail update(String id, String name, String shortDescription, String detail,
            String requesterId, boolean callerIsSuperAdmin,
            List<String> tags, List<String> tools, List<String> connectorNames, Boolean shared) {
        Path path = skillPath(id);
        if (!Files.isRegularFile(path)) throw new NoSuchElementException("No such skill: " + id);
        ParsedSkill existing = parse(readFile(path));
        if (!isVisible(existing, requesterId)) throw new NoSuchElementException("No such skill: " + id);
        if (!canEdit(existing, requesterId, callerIsSuperAdmin)) {
            throw new SkillAccessDeniedException("Not permitted to edit skill: " + id);
        }
        boolean newShared = shared != null ? shared : existing.shared();
        ParsedSkill parsed = new ParsedSkill(name, shortDescription, detail == null ? "" : detail,
                existing.userId(), newShared, existing.official(),
                cap(tags, MAX_TAGS), cap(tools, MAX_TOOLS), cap(connectorNames, MAX_CONNECTOR_NAMES));
        writeSkill(id, parsed);
        return toDetail(id, parsed, requesterId, callerIsSuperAdmin);
    }

    public void delete(String id, String requesterId, boolean callerIsSuperAdmin) {
        Path path = skillPath(id);
        if (!Files.isRegularFile(path)) throw new NoSuchElementException("No such skill: " + id);
        ParsedSkill existing = parse(readFile(path));
        if (!isVisible(existing, requesterId)) throw new NoSuchElementException("No such skill: " + id);
        if (!canEdit(existing, requesterId, callerIsSuperAdmin)) {
            throw new SkillAccessDeniedException("Not permitted to delete skill: " + id);
        }
        // Enforced here, not just hidden in the UI - see canDelete() for why a product skill is
        // permanent. The seeder would recreate it on the next restart anyway, so allowing the
        // delete would only produce a confusing "it came back" instead of an honest refusal.
        if (!canDelete(existing, requesterId, callerIsSuperAdmin)) {
            throw new SkillAccessDeniedException(
                    "This is a built-in skill and cannot be deleted. You can edit it, or use "
                            + "Restore default to put it back the way it shipped.");
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Trusted-internal-only: bypasses all permission/ownership logic entirely. Used solely by
    // DefaultChatSkillSeeder to create/refresh its own seeded, org-wide skills - never call this
    // from a user-facing request path.
    public SkillDetail createOfficial(String name, String shortDescription, String detail) {
        return createOfficial(name, shortDescription, detail, List.of());
    }

    public SkillDetail createOfficial(String name, String shortDescription, String detail,
                                      List<String> tools) {
        String id = uniqueId(slugify(name));
        ParsedSkill parsed = new ParsedSkill(name, shortDescription, detail == null ? "" : detail,
                null, false, true, List.of(), tools == null ? List.of() : tools, List.of());
        writeSkill(id, parsed);
        return toDetail(id, parsed, null, true);
    }

    // One-time backfill for skills seeded before default skills carried their own `tools`: those
    // files have an empty list, so the Skills UI shows every default skill with nothing selected
    // even though gating works (via ToolSkillGating's name-keyed map). Fills ONLY an empty list on
    // an OFFICIAL skill, so it can never overwrite a selection anyone actually made, and is
    // idempotent - once filled, the isEmpty() guard stops it. Returns true if it wrote.
    public boolean backfillOfficialToolsIfEmpty(String id, List<String> tools) {
        if (tools == null || tools.isEmpty()) return false;
        Path path = skillPath(id);
        if (!Files.isRegularFile(path)) return false;
        ParsedSkill existing = parse(readFile(path));
        if (!existing.official() || !existing.tools().isEmpty()) return false;
        writeSkill(id, new ParsedSkill(existing.name(), existing.shortDescription(), existing.detail(),
                existing.userId(), existing.shared(), existing.official(),
                existing.tags(), List.copyOf(tools), existing.connectorNames()));
        return true;
    }

    // Preserves the existing skill's userId/shared/official/tags/tools - only
    // name/shortDescription/detail are updated. Trusted-internal-only, see createOfficial().
    public SkillDetail updateOfficial(String id, String name, String shortDescription, String detail) {
        Path path = skillPath(id);
        if (!Files.isRegularFile(path)) throw new NoSuchElementException("No such skill: " + id);
        ParsedSkill existing = parse(readFile(path));
        ParsedSkill parsed = new ParsedSkill(name, shortDescription, detail == null ? "" : detail,
                existing.userId(), existing.shared(), existing.official(),
                existing.tags(), existing.tools(), existing.connectorNames());
        writeSkill(id, parsed);
        return toDetail(id, parsed, null, true);
    }

    // Opens the block this renders. A marker rather than a plain heading because a skill's own
    // short description is arbitrary user text that may contain any heading itself; it is also
    // stripped out of every injected body (see injectableSkills) so no skill can forge a boundary.
    public static final String SKILLS_BLOCK_MARKER = "<!-- proxyma:skills -->";

    // Per-skill cap on the advertised description, and on the block as a whole. Both exist because
    // every skill this user can see is now listed - one user with a hundred skills would otherwise
    // grow every single turn's prompt without bound. Truncation (with an ellipsis) rather than
    // omission for the per-skill cap: a clipped description still identifies the skill well enough
    // for the model to decide whether to read it. The block cap does drop skills, alphabetically
    // last first, since there is no way to keep them all and stay inside a budget.
    private static final int MAX_DESCRIPTION_CHARS = 300;
    private static final int MAX_BLOCK_CHARS = 8000;

    // Renders the listing appended to the system prompt (see ChatService.resolveSystemPrompt()) -
    // one line per skill VISIBLE to this requester, name plus short description, and nothing more.
    // No skill body ever appears here.
    //
    // This is the progressive-disclosure split: the prompt says which skills exist, and a body
    // enters the conversation as its own message only when it is actually used - either because the
    // model called readSkill for it, or because the user picked it in the composer (see
    // ChatService.applyPickedSkills). The alternative - inlining a picked skill's full instructions
    // here - is what this used to do, and it made the system prompt a mutable thing that had to be
    // rewritten mid-conversation on every load/unload, which cascade-invalidates the provider's
    // cached prompt prefix for the whole conversation (see AnthropicAdapter's cache strategy).
    // A listing that only ever grows at conversation start keeps that prefix stable.
    // Setter-injected and optional, so a ChatSkillService built without one lists every skill as-is.
    private LicenseToolScope licenseScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLicenseScope(LicenseToolScope licenseScope) {
        this.licenseScope = licenseScope;
    }

    public String formatForSystemPrompt(String requesterId) {
        List<IdParsed> visible = listFiles().stream()
                .map(this::readIdParsed)
                .filter(Objects::nonNull)
                .filter(ip -> isVisible(ip.parsed(), requesterId))
                .sorted(Comparator.comparing(ip -> ip.parsed().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<ExternalToolSource.ExternalSkill> external = externalSkills(requesterId);
        if (visible.isEmpty() && external.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(SKILLS_BLOCK_MARKER).append('\n')
                .append("## Available Skills\n")
                .append("Call the 'readSkill' tool with a skill's exact name below to load its full instructions ")
                .append("when a task matches it - do not ask the user for permission first, and do not assume the ")
                .append("detail without reading it. A skill you have already read stays in effect for the rest of ")
                .append("the conversation; there is no need to read it twice.\n");
        for (IdParsed ip : visible) {
            // A skill whose subject the license leaves out is still LISTED, marked, so the model can
            // say it needs a higher plan instead of that it does not exist; its tools are not
            // advertised (ToolSkillGating). See LicenseToolScope.
            boolean included = licenseScope == null || licenseScope.isSkillIncluded(ip.parsed().name());
            String line = "- **" + ip.parsed().name() + "**: "
                    + (included ? truncate(ip.parsed().shortDescription(), MAX_DESCRIPTION_CHARS)
                                : "not in this installation's license plan - do not read it; say it needs a higher plan.")
                    + "\n";
            if (sb.length() + line.length() > MAX_BLOCK_CHARS) break;
            sb.append(line);
        }
        // Each connected MCP server, as a skill of its own: reading it lists the server's tools and
        // makes them callable (ExternalToolSource). After the skill files, in the same list.
        for (ExternalToolSource.ExternalSkill skill : external) {
            String line = "- **" + skill.name() + "**: " + truncate(skill.description(), MAX_DESCRIPTION_CHARS) + "\n";
            if (sb.length() + line.length() > MAX_BLOCK_CHARS) break;
            sb.append(line);
        }
        return sb.toString();
    }

    // Optional, as the license scope above is.
    private ExternalToolSource externalTools;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExternalTools(ExternalToolSource externalTools) {
        this.externalTools = externalTools;
    }

    private List<ExternalToolSource.ExternalSkill> externalSkills(String requesterId) {
        if (externalTools == null) return List.of();
        try {
            return externalTools.skillsFor(requesterId);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ').strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
    }

    // One skill's full instructions, ready to be injected into a conversation as a message (see
    // ChatService.applyPickedSkills). Same text readSkill would have returned for it, including the
    // preferred-connectors footer, so a picked skill and a model-read one carry identical content.
    public record InjectableSkill(String id, String name, String body) {}

    // Resolves skill ids to their injectable bodies, applying the same visibility rule every other
    // read path applies. Silently drops an id with nothing visible behind it - this runs on the hot
    // chat-send path and must never fail a turn over a stale chip left in a draft.
    public List<InjectableSkill> injectableSkills(Set<String> ids, String requesterId) {
        if (ids.isEmpty()) return List.of();
        return listFiles().stream()
                .map(this::readIdParsed)
                .filter(Objects::nonNull)
                .filter(ip -> ids.contains(ip.id()) && isVisible(ip.parsed(), requesterId))
                .map(ip -> new InjectableSkill(ip.id(), ip.parsed().name(),
                        withConnectorPriorityInstruction(
                                ip.parsed().detail().replace(SKILLS_BLOCK_MARKER, "").strip(),
                                ip.parsed().connectorNames())))
                .sorted(Comparator.comparing(InjectableSkill::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // Maps user-picked skill NAMES (what the composer's "/name" chips carry) to their ids, applying
    // the same visibility filter every other read path applies. Silently drops a name with nothing
    // visible behind it - this runs on the hot chat-send path and must never fail a turn over a
    // stale chip left in a draft.
    public Set<String> resolveIdsForNames(List<String> names, String requesterId) {
        if (names == null || names.isEmpty()) return Set.of();
        Set<String> wanted = names.stream()
                .filter(Objects::nonNull)
                .map(n -> n.trim().toLowerCase(Locale.ROOT))
                .filter(n -> !n.isEmpty())
                .collect(Collectors.toSet());
        if (wanted.isEmpty()) return Set.of();
        Set<String> resolved = new HashSet<>();
        for (IdParsed ip : listFiles().stream().map(this::readIdParsed).filter(Objects::nonNull).toList()) {
            if (wanted.contains(ip.parsed().name().toLowerCase(Locale.ROOT)) && isVisible(ip.parsed(), requesterId)) {
                resolved.add(ip.id());
            }
        }
        return resolved;
    }

    /**
     * Every skill name visible to this requester, lowercased.
     *
     * <p>The mirror of WorkflowService.visibleNamesLowercase(), and it exists for the same one
     * reason: "/" is ONE namespace to the person typing into it, so a skill and a workflow may not
     * share a name. Creation of either rejects a collision rather than resolving it by precedence -
     * a silent winner between the two is a bug report nobody will be able to reproduce.
     *
     * <p>Checked in the controllers, not here: the check needs both services, and having each
     * service hold the other would be a bean cycle for what is request validation either way.
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

    // Used by ToolSkillGating: looks up a skill by NAME (matching readDetailByName's existing
    // by-name lookup pattern) across ALL skills on disk, no visibility filtering - this runs from
    // server-side gating logic during an active chat turn (the model already read the skill by
    // name), not from a user-facing read endpoint.
    public Optional<Set<String>> toolsForSkillName(String name) {
        for (Path path : listFiles()) {
            ParsedSkill parsed = parse(readFile(path));
            if (parsed.name().equalsIgnoreCase(name)) {
                return parsed.tools().isEmpty() ? Optional.empty() : Optional.of(new HashSet<>(parsed.tools()));
            }
        }
        return Optional.empty();
    }

    // Trusted-internal lookup, NO visibility filtering - used only by DefaultChatSkillSeeder (and
    // downstream consumers' own seeders) to check "does a skill
    // with this name already exist" before seeding. Never expose this to a user-facing/tool-
    // execution path - see readDetailByNameVisibleTo() below for that.
    public Optional<String> readDetailByName(String name) {
        for (Path path : listFiles()) {
            ParsedSkill parsed = parse(readFile(path));
            if (parsed.name().equalsIgnoreCase(name)) return Optional.of(parsed.detail());
        }
        return Optional.empty();
    }

    // Used by ReadSkillTool (proxyma-core-tools) - unlike readDetailByName above, this DOES
    // enforce isVisible(), because a tool call's skillName argument is not trustworthy: a user can
    // ask the agent to call readSkill with any guessed name directly, regardless of whether that
    // skill was ever actually announced to it in the system prompt (SEC-26 - the model "already
    // saw this name" premise the old unrestricted lookup relied on doesn't hold for a name typed
    // by the user, not chosen by the model). Same never-leak-existence behavior as get(): a
    // private skill someone else owns returns empty, indistinguishable from a nonexistent name.
    public Optional<String> readDetailByNameVisibleTo(String name, String requesterId) {
        if (externalTools != null && name != null && externalTools.owns(name)) {
            return externalTools.readSkill(name, requesterId);
        }
        for (Path path : listFiles()) {
            ParsedSkill parsed = parse(readFile(path));
            if (parsed.name().equalsIgnoreCase(name) && isVisible(parsed, requesterId)) {
                return Optional.of(withConnectorPriorityInstruction(parsed.detail(), parsed.connectorNames()));
            }
        }
        return Optional.empty();
    }

    // Appends a short instruction block naming this skill's preferred connectors, so the model
    // knows to pass them as searchIndexedSources' own connectorNames param once it reads this
    // skill. Deliberately only called from readDetailByNameVisibleTo() above - never from
    // readDetailByName() (trusted-internal, used by seeders) and never from toDetail()/get()
    // (the settings edit-form GET must keep returning exactly the raw editable markdown, not
    // text that would round-trip back into the file on the user's next save). A preference, not
    // a hard restriction - SearchTool.resolveVisibleByNames() already fails soft (a
    // {success:false} tool result, not an exception) if a name isn't visible to the actual
    // caller, so the instruction explicitly tells the model to fall back when that happens.
    private static String withConnectorPriorityInstruction(String detail, List<String> connectorNames) {
        if (connectorNames.isEmpty()) return detail;
        String quoted = connectorNames.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(", "));
        return detail + "\n\n---\n"
                + "**Preferred sources for this skill:** when calling `searchIndexedSources`, pass "
                + "sourceNames = [" + quoted + "] to prioritize these sources. This is a "
                + "preference, not a hard restriction - if the user asks about something outside them, or "
                + "if none of them come back visible/found, fall back to an unrestricted search instead.\n";
    }

    private static boolean isVisible(ParsedSkill parsed, String requesterId) {
        return parsed.userId() == null
                || parsed.userId().equals(requesterId)
                || parsed.shared();
    }

    private static boolean mine(ParsedSkill parsed, String requesterId) {
        return parsed.userId() != null && parsed.userId().equals(requesterId);
    }

    // Product built-ins are READ-ONLY, for the same reason they are undeletable: they describe
    // capabilities the agent is expected to have, and tool visibility is gated by skill NAME
    // (ToolSkillGating), so an edit that renames or narrows one silently takes its tools away.
    // They belong to the product, not the deployment. A user who wants different guidance can
    // create their own skill; nothing here blocks that.
    //
    // Not static any more - it has to ask DefaultSkillRegistry what is a product skill.
    private boolean canEdit(ParsedSkill parsed, String requesterId, boolean callerIsSuperAdmin) {
        if (parsed.official() && defaultSkills.isProduct(parsed.name())) return false;
        return callerIsSuperAdmin || (parsed.userId() != null && parsed.userId().equals(requesterId));
    }

    // Everything editable is deletable EXCEPT a skill currently going by a PRODUCT built-in's name.
    // Those describe capabilities the agent is expected to have, and because tool visibility is
    // gated by skill NAME (ToolSkillGating), deleting one doesn't just remove guidance - it makes
    // that skill's tools unreachable, with nothing in the UI to explain why. Editing one is now
    // refused for the same reason - see canEdit.
    //
    // Both conditions matter. The CURRENT name decides whether this still IS a built-in - rename
    // one and it has become the admin's own skill, deletable, with the seeder re-creating the
    // built-in under its own name on the next startup. `official` keeps that from catching a
    // user-created skill that merely happens to be named "Jira", which is theirs and must stay
    // deletable. Tenant skills are deletable throughout.
    private boolean canDelete(ParsedSkill parsed, String requesterId, boolean callerIsSuperAdmin) {
        if (!canEdit(parsed, requesterId, callerIsSuperAdmin)) return false;
        return !(parsed.official() && defaultSkills.isProduct(parsed.name()));
    }

    // Drives the "Restore default" affordance: true only when this skill's seeded original is
    // actually recoverable, so the button is never offered where it would 404 (a user-created
    // Never offered for a product built-in: it cannot be edited, so there is nothing to restore
    // FROM - the stored copy is already the seeded original.
    // skill, or an official one whose seeder no longer exists).
    private boolean hasDefault(ParsedSkill parsed) {
        return parsed.official() && !defaultSkills.isProduct(parsed.name())
                && defaultSkills.find(parsed.name()).isPresent();
    }

    private SkillSummary toSummary(String id, ParsedSkill p, String requesterId, boolean callerIsSuperAdmin) {
        return new SkillSummary(id, p.name(), p.shortDescription(), p.userId(), p.shared(), p.official(),
                p.tags(), mine(p, requesterId), canEdit(p, requesterId, callerIsSuperAdmin),
                canDelete(p, requesterId, callerIsSuperAdmin), hasDefault(p));
    }

    private SkillDetail toDetail(String id, ParsedSkill p, String requesterId, boolean callerIsSuperAdmin) {
        return new SkillDetail(id, p.name(), p.shortDescription(), p.detail(), p.userId(), p.shared(), p.official(),
                p.tags(), p.tools(), p.connectorNames(), mine(p, requesterId),
                canEdit(p, requesterId, callerIsSuperAdmin), canDelete(p, requesterId, callerIsSuperAdmin),
                hasDefault(p));
    }

    // The seeded original for a skill, for the Skills UI's "Restore default". Returns empty for a
    // skill that has no seeded default, and (like get()) for one not visible to the requester.
    // Read-only: it hands back the original text for the editor to show, and the user still has to
    // press Save - restoring is never a silent write, matching how the system prompt's own
    // "Reset to default" behaves.
    public Optional<DefaultSkillRegistry.DefaultSkill> defaultFor(String id, String requesterId) {
        Path path = skillPath(id);
        if (!Files.isRegularFile(path)) return Optional.empty();
        ParsedSkill parsed = parse(readFile(path));
        if (!isVisible(parsed, requesterId) || !parsed.official()) return Optional.empty();
        return defaultSkills.find(parsed.name());
    }

    private List<Path> listFiles() {
        if (!Files.isDirectory(skillsDir)) return List.of();
        try (Stream<Path> stream = Files.list(skillsDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private IdParsed readIdParsed(Path path) {
        ParsedSkill parsed = parse(readFile(path));
        String id = path.getFileName().toString().replaceFirst("\\.md$", "");
        return new IdParsed(id, parsed);
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ParsedSkill parse(String content) {
        Matcher m = FRONTMATTER.matcher(content);
        if (!m.matches()) return new ParsedSkill("", "", content, null, false, false, List.of(), List.of(), List.of());
        String name = "";
        String shortDescription = "";
        String userId = null;
        boolean shared = false;
        boolean official = false;
        List<String> tags = List.of();
        List<String> tools = List.of();
        List<String> connectorNames = List.of();
        for (String line : m.group(1).split("\\r?\\n")) {
            int idx = line.indexOf(':');
            if (idx < 0) continue;
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            if ("name".equals(key)) name = value;
            else if ("shortDescription".equals(key)) shortDescription = value;
            else if ("userId".equals(key)) userId = value.isEmpty() ? null : value;
            else if ("shared".equals(key)) shared = Boolean.parseBoolean(value);
            else if ("official".equals(key)) official = Boolean.parseBoolean(value);
            else if ("tags".equals(key)) tags = parseCsv(value);
            else if ("tools".equals(key)) tools = parseCsv(value);
            else if ("connectorNames".equals(key)) connectorNames = parseCsv(value);
        }
        return new ParsedSkill(name, shortDescription, m.group(2), userId, shared, official, tags, tools, connectorNames);
    }

    private static List<String> parseCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // Silently truncates (rather than rejecting) a tags/tools list past its cap - simplest
    // behavior for a UI-driven checklist/tag-input where the user isn't crafting the request by
    // hand, and consistent with sanitizeLine()'s existing "degrade gracefully, don't throw" stance
    // on malformed frontmatter input elsewhere in this class.
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

    private void writeSkill(String id, ParsedSkill parsed) {
        StringBuilder header = new StringBuilder();
        header.append("name: ").append(sanitizeLine(parsed.name())).append('\n');
        header.append("shortDescription: ").append(sanitizeLine(parsed.shortDescription())).append('\n');
        if (parsed.userId() != null) {
            header.append("userId: ").append(sanitizeLine(parsed.userId())).append('\n');
        }
        header.append("shared: ").append(parsed.shared()).append('\n');
        header.append("official: ").append(parsed.official()).append('\n');
        if (!parsed.tags().isEmpty()) {
            header.append("tags: ").append(String.join(",", parsed.tags())).append('\n');
        }
        if (!parsed.tools().isEmpty()) {
            header.append("tools: ").append(String.join(",", parsed.tools())).append('\n');
        }
        if (!parsed.connectorNames().isEmpty()) {
            header.append("connectorNames: ").append(String.join(",", parsed.connectorNames())).append('\n');
        }
        String content = "---\n" + header + "---\n" + (parsed.detail() == null ? "" : parsed.detail());
        try {
            Files.createDirectories(skillsDir);
            Files.writeString(skillPath(id), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Never a file outside skillsDir, whatever the id - see DefinitionFiles.
    private Path skillPath(String id) {
        return DefinitionFiles.jailed(skillsDir, id, "skill");
    }

    // Frontmatter values are single-line by contract (shortDescription is a one-line UI field;
    // name/userId likewise) - strip any embedded newline defensively rather than letting it
    // corrupt the header on the next parse.
    private static String sanitizeLine(String s) {
        return s == null ? "" : s.replace("\r", " ").replace("\n", " ").trim();
    }

    private static String slugify(String name) {
        String base = name == null ? "" : name.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return base.isEmpty() ? "skill" : base;
    }

    private String uniqueId(String base) {
        String id = base;
        int suffix = 2;
        while (Files.exists(skillPath(id))) {
            id = base + "-" + suffix++;
        }
        return id;
    }
}
