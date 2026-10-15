package ai.proxyma.core.license;

/**
 * Published when this installation's activation state changes - by Enterprise Server's license
 * activation (Proxyma Enterprise, {@code ai.proxyma.ee.license}); nothing publishes it elsewhere.
 *
 * <p>An event rather than a direct call because {@code LicenseRecheckService} already depends on
 * {@code LicenseActivationService} - it has to read the stored license to re-present it - and
 * calling back the other way would close the cycle. This keeps the dependency one-way and matches
 * how the rest of the modules talk to each other.
 *
 * @param activated true when a license was just activated (which is itself a successful
 *                  conversation with the license server, so the re-check clock resets), false when
 *                  it was released and there is nothing left to re-check.
 */
public record LicenseActivationChangedEvent(boolean activated) {
}
