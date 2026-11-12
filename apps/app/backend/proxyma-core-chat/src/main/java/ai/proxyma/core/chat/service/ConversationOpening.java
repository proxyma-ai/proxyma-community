package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.AgentInbox;
import ai.proxyma.core.chat.module.ChatModule;

import java.util.List;

/**
 * HOW A CONVERSATION NOBODY TYPED INTO IS TITLED, from its first message (2026-11-05).
 *
 * <p>A conversation's title starts as its first message and is then named by the model in "the language
 * of the message" (ConversationTitleService). For a conversation a product module or an agent opened -
 * a Constellations run, a note for its lead, a teammate's message - that message starts with a header
 * written for the model, in English: the list showed "[Scheduled run, 2026-11-05 09:00 UTC]..." until
 * the model answered, and then a title in English whatever the reader's language (the maintainer runs
 * Proxyma in Vietnamese).
 *
 * <p>So such a conversation gets a STRUCTURED title instead, the way its transcript draws the same
 * message (ChatModule.shownOpening, AgentInbox.itemsIn): a {@code kind} the page words in the reader's
 * language - the same kinds as the transcript's mail lines - and, as {@code title}, only the words that
 * are data: a run's task, a sender's name, or nothing. No model call is spent on it (a team's scheduled
 * runs would each cost one). The person's own words relayed under a header (a question from the main
 * chat to a project's lead) are titled as a person's message is, from those words alone, so the model
 * answers in their language.
 *
 * <p>Asked only for a turn no person started: a person who types "[Alarm]" in the chat gets their own
 * words as the title. Text checks only; never throws.
 */
public final class ConversationOpening {

    /**
     * {@code title}: what the conversation is titled with now - the data words, or null with a kind and
     * nothing to say. {@code kind}: what the page words, or null for an ordinary title. {@code nameFrom}:
     * the words the model may name the conversation from, or null when it is not asked.
     */
    public record Opening(String title, String kind, String nameFrom) {
        static Opening plain(String message) {
            return new Opening(message, null, message);
        }
    }

    private ConversationOpening() {
    }

    /** The opening of a conversation whose first message no person typed. */
    public static Opening of(String message, List<ChatModule> modules) {
        if (message == null || message.isBlank()) return Opening.plain(message);
        ChatService.ShownBy shownBy = ChatService.ShownBy.of(modules);
        String content = ChatService.sanitizeUserContent(message).content();
        if (AgentInbox.isInboxMessage(content)) {
            List<AgentInbox.Item> items = AgentInbox.itemsIn(content);
            if (!items.isEmpty()) {
                AgentInbox.Item first = shownBy.mail(items.get(0));
                return new Opening(blankToNull(first.title()), first.kind(), null);
            }
        }
        ChatModule.Shown shown = shownBy.opening(content).orElse(null);
        if (shown == null) return Opening.plain(message);
        if (shown.mail() != null) {
            AgentInbox.Item mail = shown.mail();
            // The line's own name when it has one (a teammate's), else the words under it (a run's task).
            String data = mail.title() != null && !mail.title().isBlank() ? mail.title() : firstLine(mail.text());
            return new Opening(blankToNull(data), mail.kind(), null);
        }
        String said = shown.personText();
        return said == null || said.isBlank() ? new Opening(null, null, null) : Opening.plain(said.strip());
    }

    // A task can run to paragraphs; its first line names it.
    private static String firstLine(String text) {
        if (text == null) return null;
        String s = text.strip();
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl).strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
