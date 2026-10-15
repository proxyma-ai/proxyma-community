package ai.proxyma.core.common.audit;

import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.spend.SpendSubject;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that happened, as the Audit Logs page shows it.
 *
 * @param at             when it happened
 * @param category       an {@link AuditCategory}
 * @param action         what happened, a short code such as {@code tool.call} or {@code signin.failed}
 * @param outcome        OK, FAILED, DENIED, BLOCKED, REDACTED and the like - see {@link #outcome}
 * @param actor          who did it, or SYSTEM. On Desktop and Home Server the writer files every
 *                       person as the one local user, as the LLM call log does
 * @param actorName      a display name captured NOW, since the account may be gone when it is read
 * @param target         what it was done to - a tool name, a user's email, a source's name
 * @param conversationId the conversation it happened in, if any
 * @param summary        one line a person can read
 * @param durationMs     how long it took, where that means something
 * @param detail         everything else, as labelled values. Never a secret, and every string in it
 *                       is passed through the path scrubber before it is written
 */
public record AuditEntry(Instant at, String category, String action, String outcome,
                         SpendSubject actor, String actorName, String target, String conversationId,
                         String summary, Long durationMs, Map<String, Object> detail) {

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final String DENIED = "DENIED";
    public static final String ALLOWED = "ALLOWED";
    public static final String REDACTED = "REDACTED";
    public static final String BLOCKED = "BLOCKED";

    /**
     * {@code text} cut to {@code max} characters, saying how much was left out. For anything of
     * unbounded size headed for an entry - a tool's arguments or result, a page of a search.
     */
    public static String cap(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max) + "... [" + (text.length() - max) + " more characters]";
    }

    /** Starts an entry. Nothing is recorded until {@link Builder#record()}. */
    public static Builder of(String category, String action) {
        return new Builder(category, action);
    }

    public static final class Builder {
        private final String category;
        private final String action;
        private String outcome = OK;
        private SpendSubject actor;
        private String actorName;
        private String target;
        private String conversationId;
        private String summary;
        private Long durationMs;
        private final Map<String, Object> detail = new LinkedHashMap<>();

        private Builder(String category, String action) {
            this.category = category;
            this.action = action;
        }

        public Builder outcome(String outcome) { this.outcome = outcome; return this; }

        /** A signed-in account, or ANONYMOUS when the id is blank. */
        public Builder user(String userId) { this.actor = SpendSubject.user(userId); return this; }

        public Builder user(String userId, String name) {
            this.actor = SpendSubject.user(userId);
            this.actorName = name;
            return this;
        }

        public Builder actor(SpendSubject actor) { this.actor = actor; return this; }

        public Builder system() { this.actor = SpendSubject.SYSTEM; return this; }

        public Builder target(String target) { this.target = target; return this; }

        public Builder conversation(String conversationId) { this.conversationId = conversationId; return this; }

        public Builder summary(String summary) { this.summary = summary; return this; }

        public Builder duration(long ms) { this.durationMs = ms; return this; }

        /** Adds one labelled value; a null value is left out rather than written as "null". */
        public Builder detail(String key, Object value) {
            if (value != null) detail.put(key, value);
            return this;
        }

        public AuditEntry build() {
            // No actor given: whoever the thread's work is billed to, which every chat turn, tool
            // worker and sync sets - and SYSTEM where nothing has, never null.
            SpendSubject who = actor != null ? actor : SpendContext.current();
            return new AuditEntry(Instant.now(), category, action, outcome == null ? OK : outcome,
                    who, actorName, target, conversationId, summary, durationMs,
                    detail.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(detail)));
        }

        /** Hands the entry to {@link Audit}. Never throws. */
        public void record() {
            Audit.record(this);
        }
    }
}
