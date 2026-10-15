package ai.proxyma.core.settings.usage;

import java.time.Instant;

/**
 * A call charged to a user who has used up their quota. Carries the same three things the chat
 * page shows for a refused message: which window, when it resets, and the sentence to show.
 */
public class UserBudgetExceededException extends RuntimeException {

    private final String window;
    private final Instant resetsAt;

    public UserBudgetExceededException(String window, Instant resetsAt, String message) {
        super(message);
        this.window = window;
        this.resetsAt = resetsAt;
    }

    public String window() { return window; }
    public Instant resetsAt() { return resetsAt; }
}
