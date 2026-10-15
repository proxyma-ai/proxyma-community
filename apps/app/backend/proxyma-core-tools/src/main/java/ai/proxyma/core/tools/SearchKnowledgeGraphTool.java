package ai.proxyma.core.tools;

import ai.proxyma.core.graph.service.GraphNeighbourhood;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.graph.service.RelevantPassage;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.DocumentKey;
import ai.proxyma.core.registry.service.SourceCollections;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.search.service.SearchService;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The deliberate multi-hop surface over the knowledge graph - phase 5 of
 * proxyma-docs/architecture/knowledge-graph.md.
 *
 * <p>A TOOL rather than a fourth retrieval channel, and the design says why both should exist: a
 * question like "what depends on X, and who owns those" needs the ReAct loop to decide when a
 * second hop is worth taking, where folding traversal into every search would take that decision
 * away and pay for it on every query.
 *
 * <h2>Structure, with the passages behind its strongest edges</h2>
 *
 * Entities, edges, how the names asked about connect, and the documents behind them - and, for the
 * first few edges, the passage each was drawn from (since 2026-10-13). A model handed a confident
 * triple with no text behind it will happily assert it; with the passage it can quote, and without
 * a second search.
 *
 * <h2>Three tools, one walk</h2>
 *
 * {@code searchKnowledgeGraph} is the general one; {@code findGraphPath} ("how is X related to Y")
 * and {@code findGraphPeople} ("who works on X") are typed answers over the same walk, so a model
 * asking one of those questions gets the answer's shape rather than a neighbourhood to read
 * (the review's "typed tools rather than one prose tool").
 *
 * <h2>Linked by meaning</h2>
 *
 * Given the user's {@code question}, the tool runs the ordinary hybrid search over the same readable
 * sources and ties each result back to the graph's passages (a document and its chunk index), so the
 * walk starts from what the question is about when no name matches, and ranks by it when one does.
 *
 * <h2>Visibility</h2>
 *
 * Scoped to the connectors the asking user can read - narrowed further to the sources they
 * @-mentioned - at every hop, for every edge (SEC-51), for every name shown, and for every document
 * title. A title is resolved only for a document of a readable source, so a document id that slipped
 * past every other filter still names nothing.
 */
@Component
public class SearchKnowledgeGraphTool {

    /** Words that are never worth resolving to an entity. Kept tiny on purpose: this is a stop
     *  list for a NAME lookup, not for a search engine, and dropping a real term because it looked
     *  generic is the failure that makes the tool answer "nothing found" about something present.
     *  English and Vietnamese, the two languages the product is used in. */
    private static final Set<String> NOISE = Set.of(
            "the", "a", "an", "of", "and", "or", "for", "to", "in", "on", "is", "are", "what",
            "which", "who", "whom", "whose", "does", "do", "did", "with", "about", "from",
            // la, va, cua, cho, voi, gi, ai, nao, co, khong, nhung, cac, trong, ve, tu, den
            "l\u00e0", "v\u00e0", "c\u1ee7a", "cho", "v\u1edbi", "g\u00ec", "ai", "n\u00e0o", "c\u00f3",
            "kh\u00f4ng", "nh\u1eefng", "c\u00e1c", "trong", "v\u1ec1", "t\u1eeb", "\u0111\u1ebfn");

    /** Edges that carry the text of their passage. Each is a read of the document's chunks from
     *  the keyword index, so a handful - the strongest edges come first. */
    private static final int PASSAGES = 6;
    private static final int MAX_PASSAGE_CHARS = 1200;

    /** Search results a question is linked through. */
    private static final int QUESTION_RESULTS = 8;

    private final KnowledgeGraphService knowledgeGraph;
    private final ConnectorService connectorService;
    private final TrackedDocumentRepository documents;
    private final BM25IndexService bm25IndexService;
    private final SearchService searchService;

    public SearchKnowledgeGraphTool(KnowledgeGraphService knowledgeGraph,
                                    ConnectorService connectorService,
                                    TrackedDocumentRepository documents,
                                    BM25IndexService bm25IndexService,
                                    SearchService searchService) {
        this.knowledgeGraph = knowledgeGraph;
        this.documents = documents;
        this.connectorService = connectorService;
        this.bm25IndexService = bm25IndexService;
        this.searchService = searchService;
    }

    @Tool(name = "searchKnowledgeGraph", description = "How named things in the indexed corpus connect: "
            + "dependencies, ownership, membership, assignment, incl. multi-hop ('what depends on X, and who owns "
            + "those'). Returns entities, relationships (from the text, or exact from source metadata), paths "
            + "between the names given, documents, and the passages behind the strongest relationships - quote "
            + "those. Pass the user's question too: it finds the right entities when names are spelled "
            + "differently. Empty when no graph was extracted; then use 'searchIndexedSources'.")
    @ToolResult(
            value = {
                @ToolResultField(name = "entities", type = ToolFieldType.OBJECT_ARRAY, always = true,
                        description = "Matched entities and their neighbours: name, type, description (what "
                                + "a passage says it is), hop (0 = matched, 1 = a neighbour, 2 = a "
                                + "neighbour's neighbour), and documents (titles that mention it)."),
                @ToolResultField(name = "relations", type = ToolFieldType.OBJECT_ARRAY, always = true,
                        description = "Edges between those entities: subject, predicate, object, the "
                                + "extracted description, weight (how many documents state it), documents, "
                                + "origin ('metadata' when the source's own fields state it, exactly), and for "
                                + "the first few the passage it was drawn from."),
                @ToolResultField(name = "paths", type = ToolFieldType.STRING_ARRAY,
                        description = "Present when two or more names were given and they connect: each "
                                + "path as its relationships in order, shortest first."),
                @ToolResultField(name = "truncated", type = ToolFieldType.BOOLEAN, always = true,
                        description = "True when a cap was reached and the neighbourhood shown is "
                                + "partial. Say so rather than answering as if it were complete."),
                @ToolResultField(name = "note", type = ToolFieldType.STRING,
                        description = "Present only when there is nothing to return, explaining why.")
            },
            envelope = false,
            description = "Entities and relationships around the names given, scoped to what this user "
                    + "may read. Reports no success flag. An empty result is a valid answer, not an error.")
    public Map<String, Object> searchKnowledgeGraph(
            @ToolParam(required = false, description = "Comma-separated entity names from the question (e.g. "
                    + "'auth-service, Alice'), not the whole question") String names,
            @ToolParam(required = false, description = "1 = direct relationships (default), 2 = also "
                    + "neighbors of neighbors") Integer hops,
            @ToolParam(required = false, description = "Restrict to these sources - exactly the names the "
                    + "user @mentioned") List<String> sourceNames,
            @ToolParam(required = false, description = "The user's question, in their words") String question) {

        List<String> terms = splitTerms(names);
        if (terms.isEmpty() && (question == null || question.isBlank())) {
            return note("No names given. Pass the specific things to look up, and the user's question.");
        }
        Scope scope = scope(sourceNames);
        if (scope.note() != null) return note(scope.note());

        GraphNeighbourhood found = knowledgeGraph.neighbourhood(terms, hops == null ? 1 : hops, scope.ids(),
                relevantTo(question, scope.connectors()), similarTo(terms));
        counted("search", !found.isEmpty(), question != null && !question.isBlank());
        if (found.isEmpty()) return note(nothingFound(scope));
        return render(found, scope.connectors(), true);
    }

    @Tool(name = "findGraphPath", description = "How two named things are connected in the knowledge graph "
            + "('how is X related to Y'): the chains of relationships between them, up to four steps, shortest "
            + "first, with the documents and passages behind each step. Empty when they do not connect.")
    @ToolResult(
            value = {
                @ToolResultField(name = "paths", type = ToolFieldType.STRING_ARRAY, always = true,
                        description = "Each connection as its relationships in order, shortest first."),
                @ToolResultField(name = "relations", type = ToolFieldType.OBJECT_ARRAY, always = true,
                        description = "The relationships those paths walk, with documents, origin and, for "
                                + "the first few, the passage each was drawn from."),
                @ToolResultField(name = "note", type = ToolFieldType.STRING,
                        description = "Present only when there is nothing to return, explaining why.")
            },
            envelope = false,
            description = "Paths between two names, scoped to what this user may read. Reports no success "
                    + "flag; no path is a valid answer.")
    public Map<String, Object> findGraphPath(
            @ToolParam(description = "The first name") String from,
            @ToolParam(description = "The second name") String to,
            @ToolParam(required = false, description = "Restrict to these sources - exactly the names the "
                    + "user @mentioned") List<String> sourceNames) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) return note("Pass both names.");
        Scope scope = scope(sourceNames);
        if (scope.note() != null) return note(scope.note());

        List<String> ends = List.of(from.trim(), to.trim());
        GraphNeighbourhood found = knowledgeGraph.neighbourhood(ends, KnowledgeGraphService.MAX_HOPS, scope.ids(),
                List.of(), similarTo(ends));
        counted("path", !found.paths().isEmpty(), false);
        if (found.isEmpty()) return note(nothingFound(scope));
        Map<String, Object> result = new LinkedHashMap<>();
        if (found.paths().isEmpty()) {
            result.put("paths", List.of());
            result.put("relations", List.of());
            result.put("note", "Both were looked for, and no chain of up to four relationships connects them "
                    + "in what you can read. They may still be related in ways no document states.");
            return result;
        }
        Set<String> walked = new LinkedHashSet<>();
        found.paths().forEach(path -> path.steps().forEach(st -> walked.add(st.subject() + "|" + st.predicate() + "|" + st.object())));
        List<GraphNeighbourhood.Edge> onPaths = found.edges().stream()
                .filter(e -> walked.contains(e.subject() + "|" + e.predicate() + "|" + e.object())).toList();
        Map<UUID, TrackedDocument> docs = documentsFor(List.of(), onPaths, scope.connectors().keySet());
        result.put("paths", pathLines(found));
        result.put("relations", relationRows(onPaths, docs, scope.connectors()));
        return result;
    }

    @Tool(name = "findGraphPeople", description = "Who works on a named thing, from the knowledge graph "
            + "('who works on X', 'who owns X'): the people connected to it directly or through one item "
            + "(an issue in a component, a pull request on a repository), with how they are connected and "
            + "the documents saying so.")
    @ToolResult(
            value = {
                @ToolResultField(name = "people", type = ToolFieldType.OBJECT_ARRAY, always = true,
                        description = "Each person: name, roles (the statements connecting them, e.g. "
                                + "'PAY-12 assigned to Ana'), and documents. Most connected first."),
                @ToolResultField(name = "note", type = ToolFieldType.STRING,
                        description = "Present only when there is nothing to return, explaining why.")
            },
            envelope = false,
            description = "People around one name, scoped to what this user may read. Reports no success "
                    + "flag; nobody is a valid answer.")
    public Map<String, Object> findGraphPeople(
            @ToolParam(description = "The thing: a project, component, service, issue, page...") String name,
            @ToolParam(required = false, description = "Restrict to these sources - exactly the names the "
                    + "user @mentioned") List<String> sourceNames) {
        if (name == null || name.isBlank()) return note("Pass the name of the thing.");
        Scope scope = scope(sourceNames);
        if (scope.note() != null) return note(scope.note());

        List<String> named = List.of(name.trim());
        GraphNeighbourhood found = knowledgeGraph.neighbourhood(named, 2, scope.ids(), List.of(), similarTo(named));
        if (found.isEmpty()) return note(nothingFound(scope));

        Map<String, GraphNeighbourhood.Entity> byName = new LinkedHashMap<>();
        found.entities().forEach(e -> byName.putIfAbsent(e.name(), e));
        Set<String> seeds = new LinkedHashSet<>();
        found.entities().stream().filter(e -> e.hop() == 0).forEach(e -> seeds.add(e.name()));
        java.util.function.Predicate<String> isPerson = n -> byName.containsKey(n) && "person".equals(byName.get(n).type());

        // Each edge touching a person: a role straight on the thing, or through the one item between.
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        Map<String, Set<UUID>> docsOf = new LinkedHashMap<>();
        Set<String> aboutThing = new LinkedHashSet<>();
        for (GraphNeighbourhood.Edge e : found.edges()) {
            if (seeds.contains(e.subject()) || seeds.contains(e.object())) {
                aboutThing.add(seeds.contains(e.subject()) ? e.object() : e.subject());
            }
        }
        for (GraphNeighbourhood.Edge e : found.edges()) {
            String person = isPerson.test(e.object()) ? e.object() : isPerson.test(e.subject()) ? e.subject() : null;
            if (person == null || seeds.contains(person)) continue;
            String other = person.equals(e.object()) ? e.subject() : e.object();
            if (!seeds.contains(other) && !aboutThing.contains(other)) continue;
            // The statement itself, in the direction it was made - "PAY-12 assigned to Ana", "Bo
            // maintains Ledger" - which no rewording into a role can make less ambiguous.
            roles.computeIfAbsent(person, k -> new LinkedHashSet<>()).add(e.subject() + " " + e.predicate() + " " + e.object());
            docsOf.computeIfAbsent(person, k -> new LinkedHashSet<>()).addAll(e.documentIds());
        }
        counted("people", !roles.isEmpty(), false);
        if (roles.isEmpty()) {
            return note("The graph connects nobody to that in what you can read. Try 'searchIndexedSources'.");
        }
        Set<UUID> allDocs = new LinkedHashSet<>();
        docsOf.values().forEach(allDocs::addAll);
        Map<UUID, TrackedDocument> docs = documentsById(allDocs, scope.connectors().keySet());
        List<Map<String, Object>> people = new ArrayList<>();
        roles.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .forEach(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", entry.getKey());
                    row.put("roles", List.copyOf(entry.getValue()));
                    row.put("documents", titles(new ArrayList<>(docsOf.get(entry.getKey())), docs));
                    people.add(row);
                });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("people", people);
        return result;
    }

    @Tool(name = "findGraphTimeline", description = "The dated history of a named thing from its source's "
            + "metadata ('what happened to X', 'who had X before'): who it was assigned to and when that ended, "
            + "status changes, who created and last updated it, merges, last commits - oldest first. Only what a "
            + "source dates; not text a model read.")
    @ToolResult(
            value = {
                @ToolResultField(name = "events", type = ToolFieldType.OBJECT_ARRAY, always = true,
                        description = "Each: date (YYYY-MM-DD), what (the statement), change ('started', "
                                + "'ended' or 'happened'), and the document stating it."),
                @ToolResultField(name = "note", type = ToolFieldType.STRING,
                        description = "Present only when there is nothing to return, explaining why.")
            },
            envelope = false,
            description = "A dated history, scoped to what this user may read. Reports no success flag; "
                    + "nothing dated is a valid answer.")
    public Map<String, Object> findGraphTimeline(
            @ToolParam(description = "The thing: an issue key, a page, a pull request, a file, a person...") String name,
            @ToolParam(required = false, description = "Restrict to these sources - exactly the names the "
                    + "user @mentioned") List<String> sourceNames) {
        if (name == null || name.isBlank()) return note("Pass the name of the thing.");
        Scope scope = scope(sourceNames);
        if (scope.note() != null) return note(scope.note());

        List<ai.proxyma.core.graph.service.TimelineEntry> entries =
                knowledgeGraph.timeline(List.of(name.trim()), scope.ids());
        counted("timeline", !entries.isEmpty(), false);
        if (entries.isEmpty()) {
            return note("Nothing dated about that in what you can read. Dates come from Jira, Confluence, "
                    + "Bitbucket and SVN metadata; try 'searchKnowledgeGraph' or 'searchIndexedSources'.");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        entries.forEach(e -> { if (e.documentId() != null) ids.add(e.documentId()); });
        Map<UUID, TrackedDocument> docs = documentsById(ids, scope.connectors().keySet());
        List<Map<String, Object>> events = new ArrayList<>();
        for (ai.proxyma.core.graph.service.TimelineEntry e : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", e.at().toString().substring(0, 10));
            row.put("what", e.subject() + " " + e.predicate() + " " + e.object());
            row.put("change", e.change().name().toLowerCase(java.util.Locale.ROOT));
            TrackedDocument doc = docs.get(e.documentId());
            if (doc != null) row.put("document", readableName(doc));
            events.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", events);
        return result;
    }

    @Tool(name = "findGraphThemes", description = "What a corpus is about, from its knowledge graph ('what are "
            + "the main topics of these sources', 'summarise what this space covers'): groups of things its "
            + "documents tie together, each with its central names, sample relationships and documents. Read "
            + "them and say what each group is about. Pass the user's question to rank the groups by it.")
    @ToolResult(
            value = {
                @ToolResultField(name = "themes", type = ToolFieldType.OBJECT_ARRAY, always = true,
                        description = "Each: entities (central names), statements (sample relationships), size "
                                + "(how many entities), documents (titles)."),
                @ToolResultField(name = "note", type = ToolFieldType.STRING,
                        description = "Present only when there is nothing to return, explaining why.")
            },
            envelope = false,
            description = "Themes of the readable sources. Reports no success flag.")
    public Map<String, Object> findGraphThemes(
            @ToolParam(required = false, description = "Restrict to these sources - exactly the names the "
                    + "user @mentioned") List<String> sourceNames,
            @ToolParam(required = false, description = "The user's question, in their words") String question) {
        Scope scope = scope(sourceNames);
        if (scope.note() != null) return note(scope.note());
        List<ai.proxyma.core.graph.service.GraphTheme> themes = knowledgeGraph.themes(scope.ids(),
                relevantTo(question, scope.connectors()), 6);
        counted("themes", !themes.isEmpty(), question != null && !question.isBlank());
        if (themes.isEmpty()) return note(nothingFound(scope));
        Set<UUID> ids = new LinkedHashSet<>();
        themes.forEach(t -> ids.addAll(t.documentIds()));
        Map<UUID, TrackedDocument> docs = documentsById(ids, scope.connectors().keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ai.proxyma.core.graph.service.GraphTheme t : themes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entities", t.entities());
            row.put("statements", t.statements());
            row.put("size", t.size());
            row.put("documents", titles(t.documentIds(), docs));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("themes", rows);
        return result;
    }

    // -- shared ------------------------------------------------------------------------------------

    // Whether the graph earns its cost, in counts only: which tool, whether it found anything, and
    // whether a question was passed to link by meaning. Never a name, a term or a result.
    private static void counted(String tool, boolean found, boolean question) {
        ai.proxyma.core.common.diagnostics.Diagnostics.count(ai.proxyma.core.common.diagnostics.DiagCounter.GRAPH_TOOL,
                "tool", tool, "found", ai.proxyma.core.common.diagnostics.DiagLabels.onOff(found),
                "question", ai.proxyma.core.common.diagnostics.DiagLabels.onOff(question));
    }

    private ai.proxyma.core.registry.service.EntityVectorService entityVectors;

    // Optional: without it a name is matched by its spelling alone.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEntityVectors(ai.proxyma.core.registry.service.EntityVectorService entityVectors) {
        this.entityVectors = entityVectors;
    }

    /** Entities whose names mean something close to each term - one embedding call per term. */
    Map<String, List<ai.proxyma.core.graph.service.SimilarEntity>> similarTo(List<String> terms) {
        if (entityVectors == null || terms.isEmpty()) return Map.of();
        Map<String, List<ai.proxyma.core.graph.service.SimilarEntity>> out = new LinkedHashMap<>();
        for (String term : terms) out.put(term, entityVectors.similar(term, 8, 0.7));
        return out;
    }

    /** The readable sources a call may walk - narrowed to the @-mentioned ones - or why there are none. */
    private record Scope(Map<UUID, RegisteredConnector> connectors, List<UUID> ids, String note) {}

    private Scope scope(List<String> sourceNames) {
        String userId = SearchContext.getUserId();
        List<RegisteredConnector> visible;
        if (sourceNames != null && !sourceNames.isEmpty()) {
            visible = connectorService.resolveVisibleByNames(userId, sourceNames);
            if (visible.isEmpty()) return new Scope(Map.of(), List.of(), "No visible source matches: " + sourceNames);
        } else {
            visible = connectorService.listVisibleTo(userId);
        }
        Map<UUID, RegisteredConnector> connectors = new LinkedHashMap<>();
        visible.stream().filter(c -> !c.isDeleting()).forEach(c -> connectors.put(c.getId(), c));
        if (connectors.isEmpty()) {
            return new Scope(Map.of(), List.of(), "No connectors are visible to you, so the graph has nothing to report.");
        }
        return new Scope(connectors, List.copyOf(connectors.keySet()), null);
    }

    // The two reasons are worth distinguishing to the model. "Nothing extracted" means stop trying
    // this tool; "nothing matched" means try a different name. Asked of the sources THIS user reads.
    private String nothingFound(Scope scope) {
        return knowledgeGraph.hasDataIn(scope.ids())
                ? "Nothing in the graph matches. Try other names, or use 'searchIndexedSources' to search the text instead."
                : "These sources have not been extracted into a knowledge graph, so there is nothing to traverse. "
                        + "Use 'searchIndexedSources' instead.";
    }

    private static Map<String, Object> note(String note) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entities", List.of());
        result.put("relations", List.of());
        result.put("truncated", false);
        result.put("note", note);
        return result;
    }

    private Map<String, Object> render(GraphNeighbourhood found, Map<UUID, RegisteredConnector> connectors,
                                       boolean withEntities) {
        Map<UUID, TrackedDocument> docs = documentsFor(found.entities(), found.edges(), connectors.keySet());
        Map<String, Object> result = new LinkedHashMap<>();
        if (withEntities) {
            List<Map<String, Object>> entities = new ArrayList<>();
            for (GraphNeighbourhood.Entity e : found.entities()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", e.name());
                if (e.type() != null) row.put("type", e.type());
                if (e.description() != null) row.put("description", e.description());
                row.put("hop", e.hop());
                row.put("documents", titles(e.documentIds(), docs));
                // The list is a few; say how many there are when there are more (graph-01).
                if (e.documentCount() > e.documentIds().size()) row.put("documentCount", e.documentCount());
                entities.add(row);
            }
            result.put("entities", entities);
        }
        result.put("relations", relationRows(found.edges(), docs, connectors));
        if (!found.paths().isEmpty()) result.put("paths", pathLines(found));
        result.put("truncated", found.truncated());
        return result;
    }

    private List<Map<String, Object>> relationRows(List<GraphNeighbourhood.Edge> edges, Map<UUID, TrackedDocument> docs,
                                                   Map<UUID, RegisteredConnector> connectors) {
        List<Map<String, Object>> relations = new ArrayList<>();
        int passages = 0;
        for (GraphNeighbourhood.Edge edge : edges) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", edge.subject());
            row.put("predicate", edge.predicate());
            row.put("object", edge.object());
            if (edge.description() != null) row.put("description", edge.description());
            row.put("weight", edge.weight());
            row.put("documents", titles(edge.documentIds(), docs));
            if (edge.fromMetadata()) row.put("origin", "metadata");
            if (passages < PASSAGES) {
                String passage = passage(docs.get(edge.passageDocumentId()), edge.passageChunkRef(), connectors);
                if (passage != null) {
                    row.put("passage", passage);
                    passages++;
                }
            }
            relations.add(row);
        }
        return relations;
    }

    private static List<String> pathLines(GraphNeighbourhood found) {
        return found.paths().stream()
                .map(path -> String.join("; ", path.steps().stream()
                        .map(st -> st.subject() + " " + st.predicate() + " " + st.object()).toList()))
                .toList();
    }

    /**
     * The passages the search finds most relevant to the question, as the graph knows passages: each
     * result's document, and the index of the chunk whose text it is (the order extraction numbered
     * the document's non-blank chunks in). Retrieval only - no expansion or reranking call - since
     * this is a ranking signal, not an answer. Never a reason to fail: no question, a search that
     * fails, a result that matches no chunk all give fewer passages.
     */
    List<RelevantPassage> relevantTo(String question, Map<UUID, RegisteredConnector> connectors) {
        if (question == null || question.isBlank() || connectors.isEmpty()) return List.of();
        List<Document> results;
        try {
            List<String> collections = connectors.keySet().stream().map(SourceCollections::of).toList();
            results = searchService.search(collections, question, QUESTION_RESULTS, 0.3,
                    SearchService.SearchOptions.retrievalOnly());
        } catch (RuntimeException e) {
            return List.of();
        }
        if (results == null || results.isEmpty()) return List.of();
        // Each result's DOCUMENT is its source and sourceId (DocumentKey), never the sourceId alone:
        // two sources both hold a `ticket:1`, and a passage of one was credited to the other's
        // document whenever their chunk texts matched.
        Set<String> sourceIds = new LinkedHashSet<>();
        results.forEach(d -> { DocumentKey k = DocumentKey.of(d); if (k != null) sourceIds.add(k.sourceId()); });
        if (sourceIds.isEmpty()) return List.of();
        Map<DocumentKey, TrackedDocument> byKey = new LinkedHashMap<>();
        for (TrackedDocument doc : documents.findLiveBySourceIds(connectors.keySet(), sourceIds)) {
            byKey.put(new DocumentKey(doc.getConnectorId(), doc.getSourceId()), doc);
        }
        Map<UUID, List<String>> chunksOf = new LinkedHashMap<>();
        List<RelevantPassage> out = new ArrayList<>();
        for (int rank = 0; rank < results.size(); rank++) {
            Document d = results.get(rank);
            TrackedDocument doc = byKey.get(DocumentKey.of(d));
            if (doc == null || d.getText() == null) continue;
            String text = d.getText().strip();
            Object normalized = d.getMetadata().get("normalizedScore");
            double score = normalized instanceof Number n ? n.doubleValue() : 1.0 / (rank + 1);
            List<String> chunks = chunksOf.computeIfAbsent(doc.getId(), id -> usableChunks(doc, connectors));
            int index = -1;
            for (int i = 0; i < chunks.size(); i++) {
                if (chunks.get(i).strip().equals(text)) { index = i; break; }
            }
            if (index >= 0) out.add(new RelevantPassage(doc.getId(), String.valueOf(index), score));
        }
        return out;
    }

    // A document's non-blank chunks in order, as extraction numbered them; the shadow collection's
    // during a model-changed re-index, when the primary holds none.
    private List<String> usableChunks(TrackedDocument doc, Map<UUID, RegisteredConnector> connectors) {
        RegisteredConnector connector = connectors.get(doc.getConnectorId());
        if (connector == null) return List.of();
        try {
            List<String> chunks = bm25IndexService.getChunkTexts(connector.getId().toString().replace("-", ""), doc.getSourceId());
            if (chunks.isEmpty() && connector.getReindexShadowCollectionId() != null) {
                chunks = bm25IndexService.getChunkTexts(connector.getReindexShadowCollectionId(), doc.getSourceId());
            }
            return chunks.stream().filter(c -> c != null && !c.isBlank()).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The documents behind a neighbourhood - only those of a readable source.
     *
     * <p>Resolved HERE rather than inside proxyma-core-graph, which holds no foreign key into
     * tracked_documents. The source filter is the second half of SEC-51: titles were resolved with
     * an unfiltered findAllById, so any document id that reached this point was named.
     */
    private Map<UUID, TrackedDocument> documentsFor(List<GraphNeighbourhood.Entity> entities,
                                                    List<GraphNeighbourhood.Edge> edges, Set<UUID> readable) {
        Set<UUID> ids = new LinkedHashSet<>();
        entities.forEach(e -> ids.addAll(e.documentIds()));
        edges.forEach(e -> ids.addAll(e.documentIds()));
        return documentsById(ids, readable);
    }

    private Map<UUID, TrackedDocument> documentsById(Set<UUID> ids, Set<UUID> readable) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, TrackedDocument> out = new LinkedHashMap<>();
        for (TrackedDocument doc : documents.findAllById(ids)) {
            if (readable.contains(doc.getConnectorId())) out.put(doc.getId(), doc);
        }
        return out;
    }

    private static List<String> titles(List<UUID> ids, Map<UUID, TrackedDocument> docs) {
        return ids.stream().map(docs::get).filter(java.util.Objects::nonNull)
                .map(SearchKnowledgeGraphTool::readableName).filter(java.util.Objects::nonNull)
                .distinct().toList();
    }

    /**
     * The text of the passage an edge was drawn from: the chunk at its index, read from the keyword
     * index the extraction read it from. Null when it cannot be found, which is never an error: the
     * edge still stands.
     */
    private String passage(TrackedDocument doc, String chunkRef, Map<UUID, RegisteredConnector> connectors) {
        if (doc == null || chunkRef == null) return null;
        int index;
        try {
            index = Integer.parseInt(chunkRef.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        List<String> usable = usableChunks(doc, connectors);
        if (index < 0 || index >= usable.size()) return null;
        String text = usable.get(index).strip();
        return text.length() <= MAX_PASSAGE_CHARS ? text : text.substring(0, MAX_PASSAGE_CHARS - 3) + "...";
    }

    /**
     * A citable name, never a path.
     *
     * <p>A file-backed document's sourceId IS an absolute server path, and an absolute path must
     * never reach a client - which includes a tool result, since the model repeats what it is
     * given. The title when there is one, otherwise the last path segment, which is what the
     * system prompt's citation rules ask for on a local file anyway ("**filename**", plain text,
     * never a link).
     */
    private static String readableName(TrackedDocument doc) {
        if (doc.getTitle() != null && !doc.getTitle().isBlank()) return doc.getTitle();
        String sourceId = doc.getSourceId();
        if (sourceId == null || sourceId.isBlank()) return null;
        try {
            Path fileName = Path.of(sourceId).getFileName();
            if (fileName != null) return fileName.toString();
        } catch (Exception ignored) {
            // Not a path (a Jira key, a URL) - those are safe to show as they are.
        }
        return sourceId;
    }

    private static List<String> splitTerms(String names) {
        if (names == null || names.isBlank()) return List.of();
        return Arrays.stream(names.split("[,;\\n]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !NOISE.contains(s.toLowerCase(java.util.Locale.ROOT)))
                .distinct()
                .limit(8)
                .toList();
    }
}
