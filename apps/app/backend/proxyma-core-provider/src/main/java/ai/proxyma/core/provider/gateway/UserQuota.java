package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.settings.usage.CostCenterService;
import ai.proxyma.core.settings.usage.UserBudgetGate;

/**
 * THE per-user quota rule, for every metered path: a service charged to the user (CostCenterSettings)
 * is refused once that user's quota is used up; one the platform pays never touches it.
 *
 * <p>One rule in one place because it was in one place and not on the path that mattered: the
 * gateway applied it, while the @Primary embedding model every vector store calls (ingest and
 * search alike) checked only the global limit - so with embeddings charged to users, a user past
 * their quota kept embedding every query and every sync, and the quota kept growing past its limit.
 */
public final class UserQuota {

    private UserQuota() {}

    /** Throws UserBudgetExceededException when the subject's quota is used up for this role. */
    public static void check(UserBudgetGate gate, CostCenterService centers, LlmRole role, SpendSubject subject) {
        if (gate == null || centers == null || subject == null) return;
        if (subject.kind() != SpendSubject.Kind.USER) return;
        if (!centers.chargedToUser(role.name())) return;
        gate.checkOrRefuse(subject.id());
    }
}
