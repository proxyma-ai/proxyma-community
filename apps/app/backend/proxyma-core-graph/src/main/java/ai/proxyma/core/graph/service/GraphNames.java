package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgEntity;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Name normalisation - the whole of entity-resolution step 1, and the reason
 * {@code kg_entities.normalizedName} and {@code kg_aliases.normalizedAlias} are stored columns
 * rather than expressions.
 *
 * <p>Whatever this does, it does to BOTH sides of every comparison and to every value ever
 * written, so a change here is a change to what has already been stored: two names that normalise
 * together today and apart tomorrow become two entities, and the unique constraints will not
 * notice. Treat it as a persisted format, not a helper.
 *
 * <p>Deliberately conservative. It case-folds, composes, collapses whitespace and drops trailing
 * punctuation - transformations that cannot change which thing is being named. It does NOT stem,
 * strip stopwords, or remove corporate suffixes: "Acme Ltd" and "Acme Inc" are plausibly two
 * companies, and merging them here would be an over-merge no operator could see.
 *
 * <p><b>It keeps diacritics</b> (since 2026-10-13). It used to strip every combining mark, which in
 * Vietnamese merges different words - "ban", "b&aacute;n" and "b&agrave;n" are three words that
 * became one entity - while leaving the stroked d alone, so "&#272;&agrave; N&#7861;ng" and
 * "Da Nang" never met. Resolution now keeps the marks, and {@link #fold} is the ASCII form a QUERY
 * may also match on; nothing is merged on it.
 */
public final class GraphNames {

    private GraphNames() {}

    /** An entity whose type the extractor could not tell. Empty rather than NULL so the unique key
     *  on (normalizedName, type) holds for it too - two NULLs are distinct to a unique index. */
    public static final String UNTYPED = "";

    /** The matching form. Never shown to anyone - a mention's surface form is what gets displayed. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        // Trailing punctuation only. A leading one can be part of the name (".NET", "@channel"),
        // where a trailing full stop or comma is almost always the sentence the name was lifted out
        // of. Except '+' and '#', which END names: stripped, "C++", "C#" and "F#" were one letter -
        // too short to store, so every relation touching them was dropped - and "Notepad++" was
        // "Notepad".
        s = s.replaceAll("[\\p{Punct}&&[^+#]]+$", "").trim();
        return truncate(s);
    }

    /**
     * The name with its marks taken off, for a QUERY that typed it without them: "da nang" finds
     * "&#272;&agrave; N&#7861;ng". The stroked d and a few other letters that carry no combining mark
     * are mapped by hand, since decomposition leaves them as they are.
     */
    public static String fold(String raw) {
        String s = Normalizer.normalize(normalize(raw), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\u0111' -> out.append('d');   // d with stroke
                case '\u0142' -> out.append('l');   // l with stroke
                case '\u00f8' -> out.append('o');   // o with stroke
                case '\u00df' -> out.append("ss");
                case '\u00e6' -> out.append("ae");
                case '\u0153' -> out.append("oe");
                default -> out.append(c);
            }
        }
        return truncate(out.toString());
    }

    /**
     * The folded name with everything but letters and digits taken out: "auth-service",
     * "Auth Service" and "AuthService" are one key. What a query matches spelling variants by, and
     * what the traversal treats as a SYNONYM - never what resolution merges on (EntityResolver).
     * Empty below four characters, where it would join unrelated short names (".NET" and "net").
     */
    public static String squash(String raw) {
        String s = fold(raw).replaceAll("[^\\p{L}\\p{N}]", "");
        return s.length() < 4 ? "" : s;
    }

    /**
     * The initials of a name of several words - "single sign-on" is "sso" - for a query that types
     * the acronym. Empty for a one-word name, or initials longer than eight letters.
     */
    public static String initials(String raw) {
        StringBuilder out = new StringBuilder();
        for (String token : fold(raw).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) out.append(token.charAt(0));
        }
        return out.length() < 2 || out.length() > 8 ? "" : out.toString();
    }

    /**
     * An entity type as stored: lower case, single-spaced, at most 64 characters, {@link #UNTYPED}
     * when there is none. "Person" and "person" were two types, and so two nodes for one person.
     */
    public static String type(String raw) {
        if (raw == null) return UNTYPED;
        String t = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return t.length() <= 64 ? t : t.substring(0, 64);
    }

    /** A relation's predicate as stored: lower case, single-spaced, at most 128 characters. */
    public static String predicate(String raw) {
        if (raw == null) return "";
        String p = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return p.length() <= 128 ? p : p.substring(0, 128);
    }

    /**
     * The display form, trimmed and length-capped.
     *
     * <p>Truncated rather than rejected, matching {@link KgEntity#MAX_NAME_LENGTH}'s own note: a
     * clipped name is still a usable entity, and failing a whole document's ingest because one
     * extraction produced a paragraph where a name belonged is not a trade worth making.
     */
    public static String display(String raw) {
        return raw == null ? "" : truncate(raw.trim().replaceAll("\\s+", " "));
    }

    /**
     * Whether a name is worth storing at all.
     *
     * <p>An extractor that returns "", "-" or a single letter has produced a row that will match
     * something eventually and mean nothing when it does - and because resolution keys on the
     * normalised form, one junk name shared across documents merges genuinely unrelated mentions
     * into a single confident-looking entity. (Vague words such as "the system" pass; telling those
     * apart is the extraction prompt's job, not a length check's.)
     */
    public static boolean isUsable(String raw) {
        String n = normalize(raw);
        return n.length() >= 2 && n.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static String truncate(String s) {
        return s.length() <= KgEntity.MAX_NAME_LENGTH ? s : s.substring(0, KgEntity.MAX_NAME_LENGTH);
    }
}
