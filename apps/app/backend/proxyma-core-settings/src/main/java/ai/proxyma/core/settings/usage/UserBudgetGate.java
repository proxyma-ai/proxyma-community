package ai.proxyma.core.settings.usage;

/**
 * The per-user quota, asked by the gateway before a call charged to that user (CostCenterSettings).
 * Implemented in proxyma-core-auth, on Enterprise Server only; nothing implements it elsewhere, so
 * the gateway skips the check.
 */
public interface UserBudgetGate {

    /**
     * @throws UserBudgetExceededException when this user has used up a quota window
     */
    void checkOrRefuse(String userId);
}
