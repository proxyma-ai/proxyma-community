package ai.proxyma.core.telemetryclient;

import ai.proxyma.contracts.telemetry.TelemetryEventType;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.license.LicenseActivationChangedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The three telemetry events that belong to the installation itself rather than to a feature.
 *
 * <p>Lives in this module because it needs no dependency it does not already have:
 * {@link ApplicationReadyEvent} and {@link ContextClosedEvent} are Spring's own, and
 * proxyma-core-telemetryclient already depends on proxyma-core-license for the tier gate, which
 * is where {@link LicenseActivationChangedEvent} is declared. A listener for anything published
 * by chat, search or registry could NOT live here - proxyma-core-chat depends on this module, so
 * the edge back would close a cycle. Those three instrument themselves; see
 * ChatTelemetryListener, SearchService and ConnectorService.
 *
 * <p>APP_START/APP_STOP are what make every other event a rate rather than a raw count: without
 * a denominator, "1,400 searches" says nothing about whether that is one installation or two
 * hundred.
 */
@Component
public class LifecycleTelemetry {

    private final TelemetryShipper telemetryShipper;

    // "desktop" or "server". Same property FeedbackShipper stamps its edition from, and it
    // matters for the same reason: the two editions are used nothing like each other, so a
    // figure that mixes them describes neither.
    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    private volatile Instant startedAt;

    public LifecycleTelemetry(TelemetryShipper telemetryShipper) {
        this.telemetryShipper = telemetryShipper;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        startedAt = Instant.now();
        // JVM start to ready: the wait a person actually sits through after double-clicking, and
        // the number that regresses silently when a new bean does blocking work at startup.
        try {
            Diagnostics.time(DiagOp.APP_STARTUP,
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime(),
                    "edition", edition());
        } catch (RuntimeException ignored) {
            // No management bean on this runtime - nothing to measure with.
        }
        // os.name only - "Windows 11", "Linux". Never os.version, user.name or the hostname:
        // the useful question is which platforms are actually in use (there is no macOS build,
        // and how much that costs is currently guesswork), not which machine this is.
        telemetryShipper.record(TelemetryEventType.APP_START, Map.of(
                "edition", edition(),
                "os", String.valueOf(System.getProperty("os.name"))));
    }

    /**
     * Fired on ContextClosedEvent rather than from a {@code @PreDestroy}: Spring publishes this
     * BEFORE it destroys beans, so the count lands in the aggregator while proxyma-core-diagnostics'
     * own ContextClosedEvent flush can still write it to disk. Ordered first for that reason: the
     * flush is ordered last.
     *
     * <p>Best-effort by nature: a process that is killed, crashes, or is stopped by a container
     * runtime that does not wait for a graceful shutdown sends nothing. APP_STOP is therefore a
     * lower bound on clean shutdowns, never a count of sessions - do not compute session length
     * by pairing it with APP_START.
     */
    @EventListener
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void onClosed(ContextClosedEvent event) {
        Instant started = startedAt;
        long uptimeSeconds = started == null ? 0 : Duration.between(started, Instant.now()).toSeconds();
        telemetryShipper.record(TelemetryEventType.APP_STOP, Map.of(
                "edition", edition(),
                "uptimeSeconds", uptimeSeconds));
    }

    /**
     * Only the activation half. A release is not an activation, and recording both under one
     * event type would make the count meaningless; the tier every event already carries is what
     * says which license this was.
     */
    @EventListener
    public void onLicenseActivationChanged(LicenseActivationChangedEvent event) {
        if (!event.activated()) return;
        telemetryShipper.record(TelemetryEventType.LICENSE_ACTIVATED, Map.of("edition", edition()));
    }

    private String edition() {
        return multiUser ? "server" : "desktop";
    }
}
