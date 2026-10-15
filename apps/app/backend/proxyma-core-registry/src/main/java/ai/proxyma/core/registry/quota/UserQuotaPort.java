package ai.proxyma.core.registry.quota;

// Port owned by this module (connector cannot depend on auth, where User/UserQuotaService live -
// see CLAUDE.md's module dependency graph). Implemented by proxyma-core-auth's UserQuotaService,
// which IS allowed to depend on connector (auth --> connector). Consumers (FileService, SyncService)
// take this as @Autowired(required = false) - null in desktop mode, where no implementation bean
// exists and quota enforcement is a no-op.
public interface UserQuotaPort {

    boolean hasCapacityFor(String userId, long incomingBytes);

    void addUsedBytes(String userId, long deltaBytes);

    // Overwrites usedStorageBytes with the real, measured total (uploaded file bytes + actual
    // vector/BM25 store bytes) for one user - used wherever a real measurement is available
    // (after an embed completes, after a connector is removed) instead of an estimated delta.
    void reconcile(String userId);

    // Cheap, DB-row-only check (no filesystem/vector-store query) - used on hot paths that run
    // per-item or every few seconds (SyncOrchestratorService's drain loop) to decide whether to
    // pause a user's processing entirely. Relies on usedStorageBytes already being kept
    // reasonably fresh by addUsedBytes()/reconcile() rather than measuring on every call.
    boolean isOverQuota(String userId);

    // Self-service usage snapshot (current user checking their own remaining headroom, e.g. the
    // file upload modal's pre-upload check) - same cheap DB-row read as isOverQuota().
    QuotaUsage getUsage(String userId);

    record QuotaUsage(long usedBytes, long quotaBytes) {}
}
