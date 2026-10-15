package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphNames#normalize} is a STORED FORMAT, not a helper.
 *
 * <p>{@code kg_entities.normalizedName} and {@code kg_aliases.normalizedAlias} hold its output, and
 * the unique index that makes "Payments Service" in three files one node keys on it. Two names that
 * normalize together today and apart tomorrow become two entities, and no constraint notices - so
 * a change that fails here is a change to data already on disk, and needs a re-index to go with it.
 *
 * <p>Proved by breaking it: stripping the marks fails {@code diacriticsAreKept}.
 */
class GraphNamesTest {

    @Test
    @DisplayName("case, surrounding space and inner runs of whitespace do not change the name")
    void caseAndWhitespace() {
        assertEquals("payments service", GraphNames.normalize("  Payments \t  SERVICE\n"));
    }

    @Test
    @DisplayName("graph-06: a name ending in + or # keeps it - C++ and C# are names, not the letter C")
    void symbolsThatEndNames() {
        assertEquals("c++", GraphNames.normalize("C++"));
        assertEquals("c#", GraphNames.normalize("C#."));
        assertEquals("f#", GraphNames.normalize("F#"));
        assertNotEquals(GraphNames.normalize("Notepad"), GraphNames.normalize("Notepad++"));
        assertTrue(GraphNames.isUsable("C++"));
        assertTrue(GraphNames.isUsable("C#"));
        // Sentence punctuation still goes.
        assertEquals("payments service", GraphNames.normalize("Payments Service.)"));
    }

    @Test
    @DisplayName("diacritics are KEPT - in Vietnamese they tell different words apart")
    void diacriticsAreKept() {
        // Three different words, which stripping marks made one entity.
        assertNotEquals(GraphNames.normalize("ban"), GraphNames.normalize("b\u00e1n"));
        assertNotEquals(GraphNames.normalize("b\u00e1n"), GraphNames.normalize("b\u00e0n"));
        // Composed and decomposed spellings of one name are one name.
        assertEquals(GraphNames.normalize("C\u00e0 Mau"), GraphNames.normalize("Ca\u0300 Mau"));
    }

    @Test
    @DisplayName("the folded form a query may match takes every mark off, the stroked d included")
    void folding() {
        assertEquals("da nang", GraphNames.fold("\u0110\u00e0 N\u1eb5ng"));
        assertEquals("da nang", GraphNames.fold("Da Nang"));
        assertEquals("cafe munster", GraphNames.fold("Caf\u00e9 M\u00fcnster"));
    }

    @Test
    @DisplayName("types and predicates are stored in lower case, single-spaced")
    void typesAndPredicates() {
        assertEquals("person", GraphNames.type(" Person "));
        assertEquals(GraphNames.UNTYPED, GraphNames.type(null));
        assertEquals("depends on", GraphNames.predicate("Depends   On"));
    }

    @Test
    @DisplayName("trailing punctuation is the sentence and goes; leading punctuation is the name and stays")
    void punctuation() {
        assertEquals("acme corp", GraphNames.normalize("Acme Corp.,"));
        assertEquals(".net", GraphNames.normalize(".NET"));
        assertEquals("@channel", GraphNames.normalize("@channel"));
    }

    @Test
    @DisplayName("corporate suffixes are NOT stripped - Acme Ltd and Acme Inc stay two entities")
    void noOverMerge() {
        assertNotEquals(GraphNames.normalize("Acme Ltd"), GraphNames.normalize("Acme Inc"));
    }

    @Test
    @DisplayName("null normalizes to empty, and an over-long name is clipped to the column rather than refused")
    void nullAndLength() {
        assertEquals("", GraphNames.normalize(null));
        String longName = "a".repeat(KgEntity.MAX_NAME_LENGTH + 40);
        assertEquals(KgEntity.MAX_NAME_LENGTH, GraphNames.normalize(longName).length());
        assertEquals(KgEntity.MAX_NAME_LENGTH, GraphNames.display(longName).length());
    }

    @Test
    @DisplayName("a name needs two characters and at least one letter or digit to be stored")
    void usable() {
        assertFalse(GraphNames.isUsable(null));
        assertFalse(GraphNames.isUsable("   "));
        assertFalse(GraphNames.isUsable("x"));
        assertFalse(GraphNames.isUsable("--"));
        assertTrue(GraphNames.isUsable("AI"));
    }
}
