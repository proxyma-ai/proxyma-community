package ai.proxyma.connectors.workitems;

import ai.proxyma.core.connector.ItemMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The small things every work-management connector (Asana, monday.com, Trello, ClickUp) does the
 * same way: reading a list of ids a person typed, the dates each service writes, a cursor that is
 * several feeds' tokens at once, and a rendered document as the stream a fetch returns.
 */
public final class WorkItems {

    public static final String MARKDOWN = "text/markdown";
    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkItems() {}

    /**
     * The entries of a comma-separated field, each trimmed, each once, sorted - so the same choice
     * typed in another order is the same source. Empty for a blank field.
     */
    public static List<String> sortedList(String raw) {
        TreeSet<String> out = new TreeSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String v = part.strip();
                if (!v.isEmpty()) out.add(v);
            }
        }
        return new ArrayList<>(out);
    }

    /** The entries of a comma-separated field in the order typed, each once. */
    public static List<String> list(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String v = part.strip();
                if (!v.isEmpty()) out.add(v);
            }
        }
        return new ArrayList<>(out);
    }

    /** An ISO-8601 instant or offset date-time ({@code 2026-10-01T10:00:00.000Z}, {@code ...+07:00}); null when unreadable. */
    public static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.strip();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException again) {
                return null;
            }
        }
    }

    /** Milliseconds since the epoch, as a number or a string of digits; null when there is none. */
    public static Instant epochMillis(String millis) {
        if (millis == null || millis.isBlank()) return null;
        try {
            return Instant.ofEpochMilli(Long.parseLong(millis.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The later of two instants, either of which may be null. */
    public static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /** An instant as the documents print it, or "" for none. */
    public static String iso(Instant at) {
        return at == null ? "" : at.toString();
    }

    public static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }

    public static InputStream markdown(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A cursor that is several feeds' tokens, keyed by feed: empty for none or for one unreadable. */
    public static Map<String, String> readCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, String> map = JSON.readValue(cursor, new TypeReference<LinkedHashMap<String, String>>() {});
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** The cursor for several feeds' tokens, or null when there is none to resume from. */
    public static String writeCursor(Map<String, String> tokens) {
        if (tokens == null || tokens.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(tokens);
        } catch (Exception e) {
            return null;
        }
    }
}
