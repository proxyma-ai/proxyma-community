package ai.proxyma.core.chat.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a sub-agent hands back: its own verdict, and its report in its own words.
 *
 * <p>THE REPORT IS PROSE, since 2026-10-02. It was a JSON object (status, summary, findings,
 * remaining) forced on the model by a schema appended to its brief - which is what made a
 * sub-agent unlike the agent that started it: its last message could not be streamed to the person
 * watching, a model that wrote a good answer as text had to be asked a second time for the same
 * answer in braces, and a third of SubAgentService was the machinery for when it still did not
 * parse. A sub-agent now ends the way the main agent does, with a message.
 *
 * <p>THE VERDICT SURVIVES, as one line. The schema existed because free text could not tell "here
 * is the answer" from "I'll start by examining the file": both read as a result, and both were
 * handed on as a success. So the sub-agent still has to say which it is - {@code STATUS:
 * COMPLETED}, {@code PARTIAL} or {@code FAILED} on the last line of its report - and a report that
 * does not say is taken at the more cautious reading the caller names. The line is taken out of
 * the text the reader and the main agent are shown; the status travels beside it.
 */
public record SubAgentReport(Status status, String report) {

    public enum Status {
        /** The task is done. */
        COMPLETED,
        /** Some of it is done: out of steps, out of time, or the sub-agent said so itself. */
        PARTIAL,
        /** None of it could be done, or the run broke. */
        FAILED,
        /** Somebody stopped it. Never a verdict the sub-agent gives - it is not asked. */
        STOPPED,
    }

    // The LAST line that states a status. Tolerant of what models actually write around it: bold
    // markers, a trailing full stop or remark, a different case.
    private static final Pattern STATUS_LINE = Pattern.compile(
            "(?im)^[\\s>*_`#-]*STATUS[\\s*_`]*:[\\s*_`]*(COMPLETED|PARTIAL|FAILED)\\b.*$");

    public SubAgentReport {
        if (status == null) status = Status.PARTIAL;
        if (report == null) report = "";
    }

    /**
     * Reads a sub-agent's final message: the status its last STATUS line states, and the message
     * without that line. {@code whenUnstated} is what a message with no such line counts as -
     * COMPLETED when the loop simply ended, PARTIAL when it was cut short.
     */
    public static SubAgentReport fromFinalMessage(String text, Status whenUnstated) {
        String body = text == null ? "" : text.strip();
        Matcher matcher = STATUS_LINE.matcher(body);
        int start = -1;
        int end = -1;
        Status stated = null;
        while (matcher.find()) {
            start = matcher.start();
            end = matcher.end();
            stated = Status.valueOf(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
        }
        if (stated == null) return new SubAgentReport(whenUnstated, body);
        String without = (body.substring(0, start) + body.substring(end)).strip();
        return new SubAgentReport(stated, without);
    }

    public static SubAgentReport failed(String why) {
        return new SubAgentReport(Status.FAILED, why);
    }

    public static SubAgentReport partial(String report) {
        return new SubAgentReport(Status.PARTIAL, report);
    }

    public static SubAgentReport stopped(String why) {
        return new SubAgentReport(Status.STOPPED, why);
    }

    /** The same report under a more cautious status - a COMPLETED the run cannot stand behind. */
    public SubAgentReport atMost(Status ceiling) {
        return status == Status.COMPLETED ? new SubAgentReport(ceiling, report) : this;
    }
}
