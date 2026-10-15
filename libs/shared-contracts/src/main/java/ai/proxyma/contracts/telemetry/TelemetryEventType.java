package ai.proxyma.contracts.telemetry;

public enum TelemetryEventType {
    // Lifecycle
    APP_START,
    APP_STOP,

    // Core features
    SEARCH,
    CHAT,
    INDEXING,

    // License
    LICENSE_ACTIVATED,

    // Connector management
    SOURCE_REGISTERED,
    SOURCE_REMOVED,

    // Errors
    ERROR
}
