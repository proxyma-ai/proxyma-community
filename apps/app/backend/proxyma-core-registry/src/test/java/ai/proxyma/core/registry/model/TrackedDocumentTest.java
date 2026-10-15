package ai.proxyma.core.registry.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three things about this row that have to hold before anything writes one.
 *
 * <p>A failure message that does not fit fails the SAVE, not the item - so the drain run recording
 * one bad document fails whole. A lastTouchedAt that can move backwards reorders the list a user is
 * reading. And a new column declared NOT NULL never arrives at all on an existing install, because
 * ddl-auto=update cannot add one to a populated table: PostgreSQL refuses, Hibernate logs it at
 * WARN and carries on, and the app then runs with the column missing.
 */
class TrackedDocumentTest {

    @Test
    @DisplayName("a failure message longer than the column is cut to fit, and says it was cut")
    void failureMessageIsTruncatedWithAMarker() {
        // What this is really about: an upstream answering a 500 with an HTML error page.
        String hugeErrorPage = "<html>" + "x".repeat(50_000) + "</html>";

        TrackedDocument doc = new TrackedDocument();
        doc.setFailureMessage(hugeErrorPage);

        assertEquals(TrackedDocument.FAILURE_MESSAGE_MAX_CHARS, doc.getFailureMessage().length(),
                "must fit the column exactly, marker included");
        assertTrue(doc.getFailureMessage().endsWith("[truncated]"),
                "a message that stops mid-sentence must say why");
    }

    @Test
    @DisplayName("a message that already fits is stored verbatim, and null stays null")
    void shortFailureMessagesAreUntouched() {
        TrackedDocument doc = new TrackedDocument();

        doc.setFailureMessage("Storage quota exceeded - connector sync paused");
        assertEquals("Storage quota exceeded - connector sync paused", doc.getFailureMessage());

        // Cleared on requeue by retryFailed() and the auto-retry sweep - truncation must not turn
        // that into an empty string, which reads as "failed, reason unknown".
        doc.setFailureMessage(null);
        assertNull(doc.getFailureMessage());
    }

    @Test
    @DisplayName("exactly the column width is not truncated")
    void aMessageOfExactlyTheColumnWidthFits() {
        TrackedDocument doc = new TrackedDocument();
        String exact = "y".repeat(TrackedDocument.FAILURE_MESSAGE_MAX_CHARS);

        doc.setFailureMessage(exact);

        assertEquals(exact, doc.getFailureMessage());
    }

    @Test
    @DisplayName("lastTouchedAt only ever moves forward")
    void touchNeverGoesBackwards() {
        Instant indexedNow = Instant.parse("2026-09-18T10:00:00Z");
        // A file that has sat unchanged since last year, reconciled after an indexing run finished.
        Instant sourceModifiedLongAgo = Instant.parse("2025-01-02T03:04:05Z");

        TrackedDocument doc = new TrackedDocument();
        doc.touch(indexedNow);
        doc.touch(sourceModifiedLongAgo);

        assertEquals(indexedNow, doc.getLastTouchedAt(),
                "an older source mtime must not push the row back down the list");
    }

    @Test
    @DisplayName("touch sets the first value it is given, and ignores null")
    void touchHandlesTheFirstValueAndNull() {
        Instant when = Instant.parse("2026-09-18T10:00:00Z");

        TrackedDocument doc = new TrackedDocument();
        doc.touch(null);
        assertNull(doc.getLastTouchedAt());

        doc.touch(when);
        assertEquals(when, doc.getLastTouchedAt());

        doc.touch(null);
        assertEquals(when, doc.getLastTouchedAt());
    }

    @Test
    @DisplayName("the three run-timing columns are nullable, or ddl-auto never adds them")
    void theRunTimingColumnsStayNullable() throws Exception {
        for (String name : new String[] {"lastTouchedAt", "indexStartedAt", "indexMs"}) {
            Field field = TrackedDocument.class.getDeclaredField(name);
            Column column = field.getAnnotation(Column.class);
            assertTrue(column.nullable(),
                    name + " must stay nullable - tracked_documents is populated on every existing"
                            + " install, and ddl-auto=update cannot add a NOT NULL column to one");
        }
    }

    @Test
    @DisplayName("title declares a length, rather than inheriting 255 from nobody's decision")
    void titleHasAnExplicitLength() throws Exception {
        Column column = TrackedDocument.class.getDeclaredField("title").getAnnotation(Column.class);

        assertEquals(TrackedDocument.TITLE_MAX_CHARS, column.length());
        assertTrue(column.length() > 255,
                "a remote item's title is whatever the remote system calls it; 255 is the JPA"
                        + " default, not a limit anybody chose");
    }
}
