package ai.proxyma.core.telemetryclient;

import ai.proxyma.contracts.telemetry.TelemetryEventType;
import ai.proxyma.core.common.diagnostics.DiagCounter;
import ai.proxyma.core.common.diagnostics.DiagLabels;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The product events - a start, a search, a source added - counted into the daily diagnostics
 * report. NOTHING HERE TOUCHES THE NETWORK ANY MORE.
 *
 * <p>Until 0.13.0 this was a queue that POSTed every event to {@code /api/v1/telemetry/batch} within
 * a minute of it happening. It now only counts: the event becomes a counter in the in-memory
 * aggregator, proxyma-core-diagnostics keeps it on disk while "Send diagnostics to Proxyma" is on,
 * and it leaves in the one compressed report a day. The name and {@link #record} stay because the
 * call sites in chat, search and registry read naturally against them and did not need to change.
 *
 * <p>The payload a caller passes is READ, never forwarded. Only the fields named in the switch
 * below become labels, and each goes through {@link DiagLabels} - so a caller that one day passes a
 * query length, a title or a path adds nothing to the report. Numbers are dropped except the one
 * this class knows the meaning of (uptime).
 *
 * <p>The receiving server removed {@code /batch} on 2026-09-24; nothing had sent to it since 0.13.0.
 */
@Component
public class TelemetryShipper {

    public void record(TelemetryEventType type, Map<String, Object> payload) {
        if (type == null) return;
        Map<String, Object> p = payload == null ? Map.of() : payload;
        switch (type) {
            case APP_START -> Diagnostics.count(DiagCounter.APP_START);
            case APP_STOP -> {
                Diagnostics.count(DiagCounter.APP_STOP);
                if (p.get("uptimeSeconds") instanceof Number n) {
                    Diagnostics.count(DiagCounter.APP_UPTIME_MINUTES, n.longValue() / 60);
                }
            }
            case SEARCH -> Diagnostics.count(DiagCounter.SEARCH,
                    "expansion", flag(p.get("queryExpansion")),
                    "rerank", flag(p.get("reranking")));
            case CHAT -> Diagnostics.count(DiagCounter.CHAT_TURN);
            case LICENSE_ACTIVATED -> Diagnostics.count(DiagCounter.LICENSE_ACTIVATED);
            case SOURCE_REGISTERED -> Diagnostics.count(DiagCounter.SOURCE_REGISTERED,
                    "connector", token(p.get("connectorType")));
            case SOURCE_REMOVED -> Diagnostics.count(DiagCounter.SOURCE_REMOVED,
                    "connector", token(p.get("connectorType")));
            // SyncService records the richer sync.outcome itself, with a duration and an error
            // category this event never carried.
            case INDEXING, ERROR -> { }
        }
    }

    private static String flag(Object value) {
        return DiagLabels.onOff(Boolean.TRUE.equals(value));
    }

    private static String token(Object value) {
        return DiagLabels.clean(value == null ? null : value.toString());
    }
}
