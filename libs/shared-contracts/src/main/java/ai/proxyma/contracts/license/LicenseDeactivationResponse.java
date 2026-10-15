package ai.proxyma.contracts.license;

/**
 * The result of releasing a machine's registration.
 *
 * <p>Releasing a machine that was never registered reports {@code released = false} with a
 * message rather than failing: an installation tidying up after itself should not have to know
 * whether it ever completed activation, and a customer uninstalling should not see an error for
 * doing the right thing.
 */
public class LicenseDeactivationResponse {

    private boolean released;

    /** Devices still registered against this license after the release. */
    private int devicesUsed;

    /** The tier's device limit. -1 means unlimited. */
    private int devicesAllowed;

    private String message;

    public LicenseDeactivationResponse() {}

    public boolean isReleased() { return released; }
    public void setReleased(boolean released) { this.released = released; }

    public int getDevicesUsed() { return devicesUsed; }
    public void setDevicesUsed(int devicesUsed) { this.devicesUsed = devicesUsed; }

    public int getDevicesAllowed() { return devicesAllowed; }
    public void setDevicesAllowed(int devicesAllowed) { this.devicesAllowed = devicesAllowed; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
