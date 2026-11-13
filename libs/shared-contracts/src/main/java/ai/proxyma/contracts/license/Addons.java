package ai.proxyma.contracts.license;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Add-ons: applications sold on top of an edition, each named in a license's signed {@code addons}
 * claim (a JSON array of the names below).
 *
 * <p>Two kinds of license carry the claim:
 * <ul>
 *   <li>an ADD-ON license, for Proxyma Desktop and Home Server: tier COMMUNITY, its {@code addons}
 *       claim naming what it unlocks - one key per add-on, activated per device, or its trial;</li>
 *   <li>an ENTERPRISE license, which grants EVERY add-on, whatever its claim lists (2026-11-12, the
 *       maintainer: the Terms and the EULA say an Enterprise license includes every add-on). The claim
 *       is written at issue, so a license issued between 2026-10-30 and 2026-11-07 listed Constellations
 *       alone and did not open the YouTube assistant, and none would have opened a later add-on.</li>
 * </ul>
 *
 * <p>Names are strings on the wire, never an enum: a name a build does not know is ignored, so the
 * license server can issue a new add-on before every installation knows it.
 */
public final class Addons {

    /** Constellations: teams of saved agents on a canvas. */
    public static final String CONSTELLATIONS = "constellations";

    /**
     * The YouTube assistant (2026-11-07): the YouTube channel team alone - the projects made from the YouTube
     * templates - for a creator who wants their comments answered, not a canvas of agents. Constellations does not
     * cover those projects (CxLicense, since 2026-11-10).
     */
    public static final String YOUTUBE_ASSISTANT = "youtube-assistant";

    /** Every add-on there is, in display order. */
    public static final List<String> ALL = List.of(CONSTELLATIONS, YOUTUBE_ASSISTANT);

    /** The JWT claim. */
    public static final String CLAIM = "addons";

    private Addons() {}

    /**
     * An add-on's name as a person reads it - in English, the one language a server writes in; a page shows its own
     * words. A name this build does not know is returned as it is.
     */
    public static String title(String name) {
        if (CONSTELLATIONS.equals(name)) return "Constellations";
        if (YOUTUBE_ASSISTANT.equals(name)) return "YouTube assistant";
        return name;
    }

    /** Whether this build knows the name. */
    public static boolean isKnown(String name) {
        return name != null && ALL.contains(name);
    }

    /**
     * The known add-ons among {@code names}, lower-cased, sorted and without repeats. Unknown and
     * blank names are dropped.
     */
    public static Set<String> known(Collection<String> names) {
        Set<String> out = new TreeSet<>();
        if (names == null) return out;
        for (String n : names) {
            if (n == null) continue;
            String k = n.trim().toLowerCase(Locale.ROOT);
            if (isKnown(k)) out.add(k);
        }
        return out;
    }

    /**
     * What a license's claims grant: every add-on for an ENTERPRISE license, whatever its claim; the
     * {@code addons} claim for any other, and none without one.
     */
    public static Set<String> granted(LicenseTier tier, Collection<String> claim) {
        if (tier == LicenseTier.ENTERPRISE) return new TreeSet<>(ALL);
        return claim == null ? new TreeSet<>() : known(claim);
    }
}
