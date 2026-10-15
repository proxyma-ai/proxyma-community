package ai.proxyma.core.chat.workflow;

import ai.proxyma.core.chat.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a markdown workflow into a graph, and moves a directory of them over.
 *
 * <p>WHAT A MARKDOWN WORKFLOW BECOMES. Each {@code ##} step is one LLM node, chained in file order
 * between an Input and a Result. That is the honest conversion, not a lossy one: a prose step IS an
 * instruction handed to a model, which is exactly what an LLM node is. What it is not is an
 * IMPROVEMENT - the converted graph is a straight line with no bindings, because the markdown form
 * never had any. Its steps shared context by being turns in one conversation, and the engine's
 * per-component memory deliberately removes that. So a migrated workflow keeps working the way it
 * reads, and gets better only when somebody opens it and wires the values explicitly.
 *
 * <p>THIS IS NOT WIRED TO STARTUP, AND MUST NOT BE UNTIL THE INTERPRETER EXISTS. Renaming the
 * markdown out of the way is what makes a workflow stop being runnable by the current runner, which
 * reads exactly those files. Converting now would leave every workflow readable, drawable and dead.
 * The order is: interpreter first, then this, in one release.
 *
 * <p>Reversible on purpose. The original is renamed to {@code .md.migrated} rather than deleted, so
 * a conversion somebody disagrees with is one rename away from being inspected, and nothing anyone
 * wrote is destroyed by a build.
 */
public final class WorkflowMarkdownMigration {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMarkdownMigration.class);

    /** Kept in step with the editor's own spacing so a converted graph opens looking deliberate
     *  rather than piled at the origin. */
    private static final int COLUMN_X = 174;
    private static final int TOP_Y = 20;
    private static final int ROW_HEIGHT = 96;

    public static final String MIGRATED_SUFFIX = ".md.migrated";

    private WorkflowMarkdownMigration() {}

    /** @param id the file basename the graph will be written under. */
    public static WorkflowGraph toGraph(String id, WorkflowService.WorkflowDetail detail) {
        List<WorkflowGraph.Node> nodes = new ArrayList<>();
        List<WorkflowGraph.Edge> edges = new ArrayList<>();
        int row = 0;

        // The markdown form had no declared inputs - a run took whatever prose the user typed
        // alongside the "/Name" chip. `request` preserves that exactly: one free-text input, in
        // scope for every step, which is what the composer was already sending.
        // Built with ordered puts, not Map.of: Map.of has no order, and the writer preserves
        // whatever order it is handed - so the declared input would come out with its keys
        // shuffled differently between builds, which is exactly the diff noise the format is
        // supposed to avoid.
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "request");
        request.put("type", "string");
        request.put("required", false);
        request.put("description", "Whatever the user typed when invoking this workflow.");
        Map<String, Object> inputConfig = new LinkedHashMap<>();
        inputConfig.put("inputs", List.of(request));
        nodes.add(new WorkflowGraph.Node("input", NodeType.INPUT, "request", COLUMN_X,
                TOP_Y + ROW_HEIGHT * row++, null, null, inputConfig));

        String preamble = detail.preamble() == null ? "" : detail.preamble().strip();
        List<WorkflowService.WorkflowStep> steps = detail.steps();
        String previous = "input";
        String lastStepId = null;

        for (int i = 0; i < steps.size(); i++) {
            WorkflowService.WorkflowStep step = steps.get(i);
            String nodeId = "step" + (i + 1);
            Map<String, Object> config = new LinkedHashMap<>();
            // The preamble was stated once before step 1 and stayed in scope by being in the
            // thread. With per-component memory there is no thread, so it is copied into every
            // step's instruction - the only faithful reading of "context every step is given".
            String instruction = step.instruction() == null ? "" : step.instruction().strip();
            config.put("instruction", preamble.isEmpty() ? instruction
                    : preamble + "\n\n" + instruction);
            if (!detail.tools().isEmpty()) {
                // Carried, not silently dropped: the markdown field was inert (nothing read it),
                // but an author who ticked those boxes meant something by it, and on an LLM node
                // the list is finally meaningful. The validator warns about it, which is the right
                // outcome - it makes the author confirm a choice that never did anything before.
                config.put("tools", detail.tools());
            }
            nodes.add(new WorkflowGraph.Node(nodeId, NodeType.LLM,
                    step.title() == null || step.title().isBlank() ? "Step " + (i + 1) : step.title(),
                    COLUMN_X, TOP_Y + ROW_HEIGHT * row++, null, null, config));
            edges.add(new WorkflowGraph.Edge(previous, null, nodeId));
            previous = nodeId;
            lastStepId = nodeId;
        }

        // "The answer is the last step's text" was the markdown runner's rule, so the converted
        // Result binds to exactly that rather than inventing a better one.
        Map<String, Object> resultConfig = new LinkedHashMap<>();
        resultConfig.put("value", lastStepId == null ? "" : "{{" + lastStepId + ".output.text}}");
        nodes.add(new WorkflowGraph.Node("result", NodeType.RESULT, "Answer", COLUMN_X,
                TOP_Y + ROW_HEIGHT * row, null, null, resultConfig));
        edges.add(new WorkflowGraph.Edge(previous, null, "result"));

        return new WorkflowGraph(
                id,
                detail.name(),
                detail.shortDescription(),
                detail.userId(),
                detail.shared(),
                detail.tags(),
                detail.provider(),
                detail.model(),
                List.copyOf(nodes),
                List.copyOf(edges));
    }

    /** What one file's conversion did, for the report a migration has to be able to show. */
    public record Converted(String id, String name, int steps, boolean written, String note) {}

    /**
     * Converts every {@code .md} in the directory that has no {@code .yaml} beside it already.
     *
     * @param apply false to report what WOULD happen and write nothing - the same
     *              preview-and-apply pairing ConfigTransferService uses, and for the same reason:
     *              a preview assembled by different code from the apply is a promise the button
     *              does not have to keep.
     */
    public static List<Converted> migrateDirectory(Path workflowsDir, WorkflowService service,
                                                   String requesterId, boolean apply) {
        List<Converted> report = new ArrayList<>();
        if (!Files.isDirectory(workflowsDir)) return report;

        List<Path> files;
        try (var stream = Files.list(workflowsDir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (Path file : files) {
            String id = file.getFileName().toString().replaceFirst("\\.md$", "");
            Path target = workflowsDir.resolve(id + ".yaml");
            if (Files.exists(target)) {
                report.add(new Converted(id, id, 0, false, "A .yaml already exists here; left alone."));
                continue;
            }
            try {
                WorkflowService.WorkflowDetail detail = service.get(id, requesterId, true);
                WorkflowGraph graph = toGraph(id, detail);
                List<WorkflowGraphValidator.Problem> problems = WorkflowGraphValidator.validate(graph);
                String note = WorkflowGraphValidator.saveable(problems)
                        ? null
                        : "Converted with " + problems.size() + " problem(s) to fix in the editor.";
                if (apply) {
                    Files.writeString(target, WorkflowGraphWriter.write(graph), StandardCharsets.UTF_8);
                    Files.move(file, workflowsDir.resolve(id + MIGRATED_SUFFIX),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                report.add(new Converted(id, detail.name(), detail.steps().size(), apply, note));
                log.info("[Workflow] migrated '{}' ({} steps){}", id, detail.steps().size(),
                        apply ? "" : " (preview only)");
            } catch (RuntimeException | IOException e) {
                // One unreadable file must not stop the rest: a directory half-migrated by an
                // exception is the worst of both formats.
                report.add(new Converted(id, id, 0, false, "Could not convert: " + e.getMessage()));
                log.warn("[Workflow] could not migrate '{}': {}", id, e.getMessage());
            }
        }
        return report;
    }
}
