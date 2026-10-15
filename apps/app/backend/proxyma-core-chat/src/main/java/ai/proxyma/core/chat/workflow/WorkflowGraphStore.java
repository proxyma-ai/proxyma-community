package ai.proxyma.core.chat.workflow;

import ai.proxyma.core.chat.exception.WorkflowAccessDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads and writes graph workflows, one {@code .yaml} per definition.
 *
 * <p>Deliberately the same directory, the same id-is-the-basename rule and the same visibility
 * model as {@link ai.proxyma.core.chat.service.WorkflowService}: the two formats coexist until the
 * markdown runner is deleted, and having them in one place is what makes the migration a rename
 * rather than a move. Everything about ownership below is that class's rule restated - visible if
 * yours, shared, or ownerless; editable by the owner or a Super Admin - because a workflow changing
 * format must not quietly change who can see it.
 *
 * <p>Path handling repeats WorkflowService's defence: an id with a separator in it is rejected
 * rather than sanitized, since it is not a request to be repaired into a valid one.
 */
@Service
public class WorkflowGraphStore {

    private final Path workflowsDir;

    public WorkflowGraphStore(
            @Value("${proxyma.workflows-dir:${user.home}/.proxyma/workflows}") String workflowsDir) {
        this.workflowsDir = Path.of(workflowsDir);
    }

    /** Every graph workflow visible to this requester, by name. */
    public List<WorkflowGraph> list(String requesterId) {
        List<WorkflowGraph> out = new ArrayList<>();
        for (Path file : files()) {
            WorkflowGraph graph = readQuietly(file);
            if (graph != null && visible(graph, requesterId)) out.add(graph);
        }
        out.sort(Comparator.comparing(WorkflowGraph::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    /** Never distinguishes "no such id" from "exists but not visible to you" - both are the same
     *  NoSuchElementException, so a private workflow's existence is not leaked. */
    public WorkflowGraph get(String id, String requesterId) {
        Path path = path(id);
        if (!Files.isRegularFile(path)) throw new NoSuchElementException("No such workflow: " + id);
        WorkflowGraph graph = WorkflowGraphParser.parse(id, read(path));
        if (!visible(graph, requesterId)) throw new NoSuchElementException("No such workflow: " + id);
        return graph;
    }

    public Optional<WorkflowGraph> findByNameVisibleTo(String name, String requesterId) {
        if (name == null || name.isBlank()) return Optional.empty();
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Path file : files()) {
            WorkflowGraph graph = readQuietly(file);
            if (graph != null && graph.name().toLowerCase(Locale.ROOT).equals(wanted)
                    && visible(graph, requesterId)) {
                return Optional.of(graph);
            }
        }
        return Optional.empty();
    }

    /** Writes, refusing a graph the validator rejects. The editor validates before it asks, so
     *  reaching this with errors means a client that skipped the check - and a workflow that cannot
     *  run is worse on disk than a save that failed. */
    public WorkflowGraph save(WorkflowGraph graph, String requesterId, boolean callerIsSuperAdmin) {
        Path path = path(graph.id());
        if (Files.isRegularFile(path)) {
            WorkflowGraph existing = WorkflowGraphParser.parse(graph.id(), read(path));
            if (!canEdit(existing, requesterId, callerIsSuperAdmin)) {
                throw new WorkflowAccessDeniedException("Not permitted to edit workflow: " + graph.id());
            }
        }
        List<WorkflowGraphValidator.Problem> problems = WorkflowGraphValidator.validate(graph);
        if (!WorkflowGraphValidator.saveable(problems)) {
            String first = problems.stream()
                    .filter(p -> p.severity() == WorkflowGraphValidator.Severity.ERROR)
                    .map(WorkflowGraphValidator.Problem::message)
                    .findFirst().orElse("This workflow is not valid.");
            throw new WorkflowGraphParser.MalformedWorkflowException(first);
        }
        try {
            Files.createDirectories(workflowsDir);
            Files.writeString(path, WorkflowGraphWriter.write(graph), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return graph;
    }

    public boolean canEdit(WorkflowGraph graph, String requesterId, boolean callerIsSuperAdmin) {
        return callerIsSuperAdmin || (graph.userId() != null && graph.userId().equals(requesterId));
    }

    private static boolean visible(WorkflowGraph graph, String requesterId) {
        return graph.userId() == null || graph.userId().equals(requesterId) || graph.shared();
    }

    private List<Path> files() {
        if (!Files.isDirectory(workflowsDir)) return List.of();
        try (Stream<Path> stream = Files.list(workflowsDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A file that will not parse is skipped in a LISTING rather than failing it: one broken
     *  workflow must not make the others unreachable. get() still throws, because a caller asking
     *  for that one specifically needs to know why. */
    private WorkflowGraph readQuietly(Path file) {
        try {
            return WorkflowGraphParser.parse(file.getFileName().toString().replaceFirst("\\.yaml$", ""),
                    read(file));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\")
                || id.contains("..") || id.startsWith(".")) {
            throw new NoSuchElementException("No such workflow: " + id);
        }
        Path resolved = workflowsDir.resolve(id + ".yaml").normalize();
        if (!resolved.startsWith(workflowsDir.normalize())) {
            throw new NoSuchElementException("No such workflow: " + id);
        }
        return resolved;
    }
}
