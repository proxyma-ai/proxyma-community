package ai.proxyma.core.settings.usage;

/**
 * Thrown when the deployment-wide LLM spend ceiling has been reached and a call is refused.
 *
 * The message is written to be READ BY AN OPERATOR, not just logged: on the ingest path it becomes
 * TrackedDocument.failureMessage via SyncService.markFailed() and is shown against the document in
 * the Sources page, and on the chat path it surfaces in the conversation. Somebody seeing "sync
 * failed" needs to know it was a budget decision rather than a broken connector.
 *
 * Unchecked, because it can be raised from deep inside a provider call stack that has no business
 * declaring it, and because every caller's correct response is the same: stop.
 */
public class GlobalQuotaExceededException extends RuntimeException {

    private final double limitUsd;
    private final double totalUsd;

    public GlobalQuotaExceededException(String activity, double limitUsd, double totalUsd) {
        super(("The deployment's monthly AI spend limit has been reached, so %s was not run. "
                + "Spent $%.2f of the $%.2f limit. Raise the limit in Settings > Cost Settings, "
                + "or wait for the quota to reset.").formatted(activity, totalUsd, limitUsd));
        this.limitUsd = limitUsd;
        this.totalUsd = totalUsd;
    }

    public double getLimitUsd() { return limitUsd; }
    public double getTotalUsd() { return totalUsd; }
}
