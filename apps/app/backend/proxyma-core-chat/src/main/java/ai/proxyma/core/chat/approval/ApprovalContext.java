package ai.proxyma.core.chat.approval;

import ai.proxyma.core.system.util.Utf16;
import ai.proxyma.core.system.util.SensitivePaths;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * WHAT A WRITE THAT WAITS FOR A PERSON IS ABOUT (2026-10-31, the maintainer: the Approvals page must
 * say "which video, which user with which comment and what the agent wants to comment"). Built on the
 * server once, when the request is made, by an {@link ApprovalContextProvider} - the YouTube reply's
 * names the video, the viewer, their comment and the reply - and shown beside the request: in the
 * approvals inbox (ApprovalInbox stores it with the request) and on the chat's own approval card.
 *
 * <p>Every value reaches a page, so {@link #bounded()} holds each to a length, keeps a link only
 * when it is a whole http(s) address, and runs the text through SensitivePaths - applied where one is
 * made and again wherever one is read back, so a row an older build wrote is held to the same rules.
 *
 * @param title  a short heading, or null
 * @param fields in the order a page shows them; {@code key} names the field (video, viewer, comment,
 *               reply - a page translates the ones it knows and shows any other as given)
 *
 * <p><b>A FIELD A PERSON MAY EDIT</b> (2026-11-02, the maintainer: "the user should be able to edit a
 * reply here"): {@code argument} names the call's argument the field shows - the YouTube reply's
 * {@code text}. A page offers to edit that field, and ApprovalInbox runs the approved call with the
 * person's text in that argument and nothing else changed. Only an argument a field names can be
 * edited, so a provider decides what is editable, never the page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalContext(String title, List<Field> fields) {

    public static final String TEXT = "TEXT";
    public static final String QUOTE = "QUOTE";
    public static final String LINK = "LINK";

    public static final int VALUE_MAX = 2_000;
    public static final int TITLE_MAX = 200;
    public static final int KEY_MAX = 64;
    public static final int HREF_MAX = 2_000;
    public static final int FIELDS_MAX = 12;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An argument name a field may name: a plain identifier, as every tool's parameters are. */
    private static final java.util.regex.Pattern ARGUMENT = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /**
     * One line of the context: {@code kind} is TEXT, QUOTE (a person's words, with {@code href} when they
     * can be opened where they were written - a YouTube comment, a reply once posted) or LINK (with
     * {@code href});
     * {@code argument}, when set, is the call's argument this field shows, which the person may edit.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(String key, String value, String href, String kind, String argument) {

        public Field(String key, String value, String href, String kind) {
            this(key, value, href, kind, null);
        }

        /** A person's words the deciding person may rewrite before approving: the call's {@code argument}. */
        public static Field editable(String key, String value, String argument) {
            return new Field(key, value, null, QUOTE, argument);
        }

        public static Field text(String key, String value) {
            return new Field(key, value, null, TEXT);
        }

        public static Field quote(String key, String value) {
            return new Field(key, value, null, QUOTE);
        }

        public static Field link(String key, String value, String href) {
            return new Field(key, value, href, LINK);
        }
    }

    /**
     * This context held to what a page may be handed: at most {@link #FIELDS_MAX} fields, each key,
     * value and title clipped and screened for server paths, a link kept only as a whole http(s)
     * address (a LINK without one is TEXT), an unknown kind TEXT, and a field with no key or no value
     * dropped. Empty when nothing is left.
     */
    public Optional<ApprovalContext> bounded() {
        List<Field> out = new ArrayList<>();
        for (Field f : fields == null ? List.<Field>of() : fields) {
            if (out.size() >= FIELDS_MAX) break;
            if (f == null) continue;
            String key = clean(f.key(), KEY_MAX);
            String value = clean(f.value(), VALUE_MAX);
            if (key == null || value == null) continue;
            String href = href(f.href());
            String kind = f.kind() == null ? TEXT : f.kind().strip().toUpperCase(Locale.ROOT);
            if (!QUOTE.equals(kind) && !LINK.equals(kind)) kind = TEXT;
            if (LINK.equals(kind) && href == null) kind = TEXT;
            String argument = f.argument() != null && ARGUMENT.matcher(f.argument().strip()).matches()
                    && !LINK.equals(kind) ? f.argument().strip() : null;
            out.add(new Field(key, value, LINK.equals(kind) || QUOTE.equals(kind) ? href : null, kind, argument));
        }
        if (out.isEmpty()) return Optional.empty();
        return Optional.of(new ApprovalContext(clean(title, TITLE_MAX), List.copyOf(out)));
    }

    /** The fields a person may edit, by the argument each shows (first wins). */
    public Map<String, Field> editable() {
        Map<String, Field> out = new java.util.LinkedHashMap<>();
        for (Field f : fields == null ? List.<Field>of() : fields) {
            if (f != null && f.argument() != null) out.putIfAbsent(f.argument(), f);
        }
        return out;
    }

    /** This context with the field showing {@code argument} now showing {@code value}. */
    public ApprovalContext withArgument(String argument, String value) {
        List<Field> out = new ArrayList<>();
        for (Field f : fields == null ? List.<Field>of() : fields) {
            out.add(f != null && argument.equals(f.argument()) ? new Field(f.key(), value, f.href(), f.kind(), f.argument()) : f);
        }
        return new ApprovalContext(title, out);
    }

    /**
     * This context showing a newer draft of the same write: each editable field takes the value its
     * argument has in {@code arguments}, and loses a link to where the old words were posted. Fields
     * whose argument is missing or not text are left as they were.
     */
    public ApprovalContext withArguments(Map<String, Object> arguments) {
        List<Field> out = new ArrayList<>();
        for (Field f : fields == null ? List.<Field>of() : fields) {
            Object v = f == null || f.argument() == null || arguments == null ? null : arguments.get(f.argument());
            out.add(v instanceof String text && !text.isBlank()
                    ? new Field(f.key(), text.strip(), null, f.kind(), f.argument()) : f);
        }
        return new ApprovalContext(title, out);
    }

    /**
     * This context with the field showing {@code argument} linked to {@code href} - where the approved
     * write put those words. Unchanged when no field shows it.
     */
    public ApprovalContext withLink(String argument, String href) {
        List<Field> out = new ArrayList<>();
        for (Field f : fields == null ? List.<Field>of() : fields) {
            out.add(f != null && argument.equals(f.argument()) ? new Field(f.key(), f.value(), href, f.kind(), f.argument()) : f);
        }
        return new ApprovalContext(title, out);
    }

    /** As stored in ToolApprovalRequest.context, and as a chat event's data. Null when it cannot be written. */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            return null;
        }
    }

    /** A stored context read back, held to {@link #bounded()}; empty for null, blank or unreadable text. */
    public static Optional<ApprovalContext> fromJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            ApprovalContext read = JSON.readValue(json, ApprovalContext.class);
            return read == null ? Optional.empty() : read.bounded();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = SensitivePaths.scrub(s.strip());
        if (t == null || t.isBlank()) return null;
        return t.length() <= max ? t : Utf16.cut(t, max - 3) + "...";
    }

    private static String href(String raw) {
        if (raw == null) return null;
        String h = raw.strip();
        if (h.length() > HREF_MAX || h.chars().anyMatch(c -> c <= ' ')) return null;
        String lower = h.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? h : null;
    }
}
