package ai.proxyma.contracts.telemetry.diagnostics;

import java.util.ArrayList;
import java.util.List;

/**
 * One installation's diagnostics for one or more whole days, sent as ONE request.
 *
 * <p>Posted gzip-compressed to {@code POST /api/v1/telemetry/daily}, at most about once a day,
 * replacing the per-event {@code /batch} path for clients from 0.13.0 on. The server removed
 * {@code /batch} on 2026-09-24.
 *
 * <p>Wire-compatibility rule, as for every DTO in this library: fields may be ADDED (a receiver
 * ignores what it does not know), never renamed or removed - a client and the server never deploy
 * at the same instant.
 */
public class DiagnosticsReport {

    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    /** The random installation id - also sent as the X-Instance-Id header. */
    private String instanceId;
    private String appVersion;
    /** COMMUNITY, PERSONAL, PROFESSIONAL. Never ENTERPRISE: that tier sends nothing. */
    private String tier;
    /** "desktop", "home_server" or "server". */
    private String edition;
    /** ISO instant the report was built. */
    private String generatedAt;
    private List<DiagnosticsDay> days = new ArrayList<>();

    public DiagnosticsReport() {}

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getEdition() { return edition; }
    public void setEdition(String edition) { this.edition = edition; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public List<DiagnosticsDay> getDays() { return days; }
    public void setDays(List<DiagnosticsDay> days) { this.days = days == null ? new ArrayList<>() : days; }
}
