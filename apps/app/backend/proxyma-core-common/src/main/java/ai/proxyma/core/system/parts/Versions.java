package ai.proxyma.core.system.parts;

/**
 * Release version order, shared by the updater and the launcher so the two can never disagree
 * about which of two builds is newer.
 */
public final class Versions {

    private Versions() {}

    /** True when {@code candidate} is a newer release than {@code current}. A leading "v" is ignored. */
    public static boolean isNewer(String candidate, String current) {
        int[] c = parse(candidate), cur = parse(current);
        for (int i = 0; i < 3; i++) {
            if (c[i] > cur[i]) return true;
            if (c[i] < cur[i]) return false;
        }
        // Same numbers, so precedence falls to the qualifier. A prerelease ranks BELOW its own
        // release (semver 11.3): 1.2.1 is newer than 1.2.1-rc1, and 1.2.1-rc1 is not newer than
        // 1.2.1.
        //
        // Two DEV builds of the same version ARE ordered, by their number: `-dev<N>` is what the dev
        // pipeline publishes, N is the commit count on dev, and a dev channel that could not tell
        // dev58 from dev57 would never offer anything after the first. Any other pair of
        // prereleases stays unordered - guessing rc2 > rc1 by string order would be wrong the first
        // time somebody writes "beta".
        long cd = devNumber(candidate), curd = devNumber(current);
        if (cd >= 0 && curd >= 0) return cd > curd;
        return hasQualifier(current) && !hasQualifier(candidate);
    }

    /** N for a {@code -devN} qualifier, else -1. */
    public static long devNumber(String v) {
        String core = stripV(v);
        int dash = core.indexOf("-dev");
        if (dash < 0) return -1;
        String n = core.substring(dash + 4);
        int plus = n.indexOf('+');
        if (plus >= 0) n = n.substring(0, plus);
        if (n.isEmpty()) return -1;
        for (int i = 0; i < n.length(); i++) if (!Character.isDigit(n.charAt(i))) return -1;
        try { return Long.parseLong(n); } catch (NumberFormatException e) { return -1; }
    }

    /** True for a PRERELEASE qualifier. Build metadata ({@code +build7}) is not precedence. */
    private static boolean hasQualifier(String v) {
        String core = stripV(v);
        int plus = core.indexOf('+');
        if (plus >= 0) core = core.substring(0, plus);
        return core.indexOf('-') >= 0;
    }

    /** GitHub tags conventionally carry a leading "v". */
    public static String stripV(String v) {
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    private static int[] parse(String v) {
        // THE QUALIFIER IS CUT OFF FIRST. This used to be replaceAll("[^0-9.]", "") over the
        // whole string, which deletes the hyphen and the letters and glues the surviving digits
        // onto the patch number: "1.2.1-rc1" became "1.2.11", so a release candidate read as
        // newer than the next ten releases - see UpdateVersionTest.
        String core = stripV(v);
        int cut = core.length();
        for (int i = 0; i < core.length(); i++) {
            char ch = core.charAt(i);
            if (ch != '.' && (ch < '0' || ch > '9')) { cut = i; break; }
        }
        String[] parts = core.substring(0, cut).split("\\.", 3);
        int[] result = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try { result[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return result;
    }
}
