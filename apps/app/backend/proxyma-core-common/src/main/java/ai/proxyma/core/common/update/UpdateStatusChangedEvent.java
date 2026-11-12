package ai.proxyma.core.common.update;

/**
 * The updater's status moved - UpdateService publishes one per change, never per byte of a download.
 *
 * <p>So the page can be TOLD an update is waiting rather than asking: the desktop window's "Restart to
 * update" control (2026-11-04) reads /api/v1/update/status once at startup and again on this event,
 * which EventStreamController carries on the page's one /api/v1/events stream. A plain Spring event
 * because that controller (proxyma-core-system) sits in a module the updater does not depend on.
 * Enterprise Server loads no UpdateService, so nothing publishes it there.
 *
 * @param status  the new UpdateService.UpdateStatus name
 * @param version the version the status is about - the one found, downloading or staged - or ""
 */
public record UpdateStatusChangedEvent(String status, String version) {}
