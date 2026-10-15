package ai.proxyma.contracts.license;

public enum LicenseStatus {
    VALID,
    EXPIRED,
    REVOKED,
    INVALID,

    /**
     * Activation refused: the license is genuine and current, but every device slot its tier
     * allows is already taken. Distinct from INVALID because the customer's remedy is different
     * - they release a machine they no longer use rather than buying anything.
     *
     * <p>Only ever returned from the activation endpoints, which are new in 1.1.0. Older clients
     * call /validate, which cannot produce this value, so adding it does not break their
     * deserialisation.
     */
    DEVICE_LIMIT_REACHED,

    /**
     * Trial refused: this deployment (or, on the retired desktop trial, this computer) has had its
     * trial already - see LicenseTrialRequest. Only ever returned from /trial and /trial-key.
     */
    TRIAL_USED
}
