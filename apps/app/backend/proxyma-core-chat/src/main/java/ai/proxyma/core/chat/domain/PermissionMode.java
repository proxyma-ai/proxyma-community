package ai.proxyma.core.chat.domain;

import java.util.Locale;

/**
 * How much the agent may change without asking, chosen beside the model in the composer and sent
 * with each message. It narrows or skips the approval PAUSE only - it never grants anything: a
 * connection's Write switch and the personal consent settings still decide what a write may reach,
 * in every mode.
 *
 * <ul>
 *   <li>ASK - every change pauses for approval (the default, and what there was before).</li>
 *   <li>AUTO - changes go ahead without the pause, as "accept all" does for a conversation.</li>
 *   <li>READ_ONLY - every change is refused; reads, including opening a local file, work as in ASK.</li>
 * </ul>
 */
public enum PermissionMode {
    ASK, AUTO, READ_ONLY;

    /** An absent or unknown value is ASK - the one that never does more than the user asked. */
    public static PermissionMode parse(String value) {
        if (value == null || value.isBlank()) return ASK;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return ASK;
        }
    }
}
