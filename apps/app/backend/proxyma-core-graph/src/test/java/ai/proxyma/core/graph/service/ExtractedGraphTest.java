package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.service.ExtractedGraph.Entity;
import ai.proxyma.core.graph.service.ExtractedGraph.Relation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one chunk's extraction is allowed to hand the writer.
 *
 * <p>Relation weights count INDEPENDENT sources. Gleaning asks the model a second time what it
 * missed, and it restates as often as it adds, so a merge that kept repeats would turn one sentence
 * read twice into corroboration. And a blank type must stay null: the unique index is on
 * (normalizedName, type), so a shared placeholder would silently merge two different things that
 * both failed classification.
 *
 * <p>Proved by breaking it: merging the second pass on the raw name fails
 * {@code mergeDeduplicatesEntities}, and a blank type becoming "UNKNOWN" fails
 * {@code blankTypeIsNull}.
 */
class ExtractedGraphTest {

    private static Entity entity(String name, String type) {
        return new Entity(name, type, null, null);
    }

    private static Relation relation(String subject, String predicate, String object) {
        return new Relation(subject, predicate, object, null);
    }

    @Test
    @DisplayName("a second pass restating an entity under another spelling adds nothing, and the first pass wins")
    void mergeDeduplicatesEntities() {
        ExtractedGraph first = new ExtractedGraph(List.of(entity("Payments Service", "system")), List.of());
        ExtractedGraph second = new ExtractedGraph(
                List.of(entity("payments  service.", "component"), entity("Ledger", "system")), List.of());

        ExtractedGraph merged = first.merge(second);

        assertEquals(List.of(entity("Payments Service", "system"), entity("Ledger", "system")),
                merged.entities());
    }

    @Test
    @DisplayName("a restated relation is one relation, not two - a different predicate is a different one")
    void mergeDeduplicatesRelations() {
        ExtractedGraph first = new ExtractedGraph(List.of(),
                List.of(relation("Payments Service", "calls", "Ledger")));
        ExtractedGraph second = new ExtractedGraph(List.of(), List.of(
                relation("PAYMENTS SERVICE", "Calls", "ledger."),
                relation("Payments Service", "owns", "Ledger")));

        List<Relation> merged = first.merge(second).relations();

        assertEquals(2, merged.size());
        assertEquals("calls", merged.get(0).predicate());
        assertEquals("owns", merged.get(1).predicate());
    }

    @Test
    @DisplayName("a self-edge is unusable even when the two ends are spelled differently")
    void selfEdgeIsUnusable() {
        assertFalse(relation("Acme", "depends on", "ACME.").isUsable());
        assertTrue(relation("Acme", "depends on", "Ledger").isUsable());
    }

    @Test
    @DisplayName("a relation with no predicate or an unusable end is not written")
    void incompleteRelationIsUnusable() {
        assertFalse(relation("Acme", " ", "Ledger").isUsable());
        assertFalse(relation("Acme", null, "Ledger").isUsable());
        assertFalse(relation("x", "calls", "Ledger").isUsable());
    }

    @Test
    @DisplayName("a blank type stays null rather than becoming a shared placeholder")
    void blankTypeIsNull() {
        assertEquals(GraphNames.UNTYPED, entity("Acme", "  ").storedType());
        assertEquals(GraphNames.UNTYPED, entity("Acme", null).storedType());
        assertEquals("system", entity("Acme", " system ").storedType());
        // "Person" and "person" were two types, and so two nodes for one person.
        assertEquals("person", entity("Ana", "Person").storedType());
    }
}
