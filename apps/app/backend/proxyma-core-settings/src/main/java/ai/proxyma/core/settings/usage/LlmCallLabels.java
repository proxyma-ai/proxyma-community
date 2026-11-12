package ai.proxyma.core.settings.usage;

import java.util.Collection;
import java.util.Map;

/**
 * WHICH AGENT a conversation's model calls were for - the per-call log's Agent column, worked out
 * when the log is READ rather than stored with each call (LlmCall keeps the conversation's id and
 * nothing else of it). Asked by LlmCallsController once per page or download, for the distinct
 * conversations on it.
 *
 * <p>A PORT because the answer is the chat's - which conversation is a deployed agent, and the
 * modules that hold conversations for saved agents (Constellations) - and the chat sits above this
 * module. Taken as {@code @Autowired(required = false)}: without an implementation every call simply
 * has no agent label, which is the truth for an ordinary chat.
 *
 * <p>Names only - a project, an agent, how its run started - never a word of what was said.
 */
public interface LlmCallLabels {

    /**
     * A label for each of these conversations that has one; a conversation that is no agent's is
     * absent. Bounded work - a few queries for the whole batch, never one per call. Never throws.
     */
    Map<String, Label> labels(Collection<String> conversationIds);

    /**
     * {@code agent} - "Project / Agent" for a saved agent, a deployed agent's title (after its root's
     * label when the root has one); {@code trigger} - how the conversation's work started, as one of
     * the {@code TRIGGER_*} words, or null when not known.
     */
    record Label(String agent, String trigger) {}

    /** A person, in the agent's own panel. */
    String TRIGGER_PERSON = "person";
    /** One of the agent's schedules. */
    String TRIGGER_SCHEDULE = "schedule";
    /** Run now, pressed by a member. */
    String TRIGGER_RUN_NOW = "run now";
    /** A connected agent's message. */
    String TRIGGER_MESSAGE = "message";
    /** A note from the server - approvals decided, a teammate's run ended. */
    String TRIGGER_NOTE = "note";
    /** A webhook called from outside. */
    String TRIGGER_WEBHOOK = "webhook";
    /** New or changed documents in a watched source. */
    String TRIGGER_SOURCE = "source";
    /** A person addressing a project's lead from the main chat. */
    String TRIGGER_CHAT = "chat";
}
