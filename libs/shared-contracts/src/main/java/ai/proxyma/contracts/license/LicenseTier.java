package ai.proxyma.contracts.license;

/**
 * Two tiers since 2026-10-14: COMMUNITY, free and open source, on Proxyma Desktop and the
 * single-user server; ENTERPRISE, the multi-user Proxyma Enterprise Server, paid or on its 30-day
 * trial (a license whose signed claims carry {@code trial: true}).
 *
 * <p>PERSONAL and PROFESSIONAL are kept as constants because licenses carrying them exist - sold,
 * and every 0.33-0.35 desktop trial - and because Jackson writes and reads this enum BY NAME: the
 * license server reads stored tier strings back into it, and builds already in the field send and
 * receive them. Removing a constant breaks whichever side deploys second. They are never issued
 * again and read as Community everywhere. They go in a later contracts 2.0, once no live license and
 * no supported build carries them.
 */
public enum LicenseTier {
    /** Free, open source. Needs no license; never issued as one. */
    COMMUNITY,
    /** @deprecated Retired. Never issued again; read as COMMUNITY. Kept for the wire and the license database. */
    @Deprecated
    PERSONAL,
    /** @deprecated Retired. Never issued again; read as COMMUNITY. Kept for the wire and the license database. */
    @Deprecated
    PROFESSIONAL,
    /** The multi-user server, paid or on its 30-day trial. */
    ENTERPRISE;

    /** A retired tier: still readable, never issued, and worth exactly what COMMUNITY is. */
    @SuppressWarnings("deprecation")
    public boolean isLegacy() {
        return this == PERSONAL || this == PROFESSIONAL;
    }
}
