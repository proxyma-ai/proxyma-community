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
 *   <li>an ENTERPRISE license, which lists every add-on today. One with no claim at all - every
 *       Enterprise license issued before the claim existed - is read as listing all of them, so
 *       selling an add-on apart from Enterprise later is a change of what is issued, not of code.</li>
 * </ul>
 *
 * <p>Names are strings on the wire, never an enum: a name a build does not know is ignored, so the
 * license server can issue a new add-on before every installation knows it.
 */
public final class Addons {

    /** Constellations: teams of saved agents on a canvas. */
    public static final String CONSTELLATIONS = "constellations";

    /** Every add-on there is, in display order. */
    public static final List<String> ALL = List.of(CONSTELLATIONS);

    /** The JWT claim. */
    public static final String CLAIM = "addons";

    private Addons() {}

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
     * What a license's claims grant: the {@code addons} claim when present, else every add-on for
     * an ENTERPRISE license and none for any other.
     */
    public static Set<String> granted(LicenseTier tier, Collection<String> claim) {
        if (claim != null) return known(claim);
        return tier == LicenseTier.ENTERPRISE ? new TreeSet<>(ALL) : new TreeSet<>();
    }
}
