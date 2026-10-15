package ai.proxyma.core.registry.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.proxyma.core.registry.model.IngestedDocument;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One document as a sender described it - a JSON body's fields, a multipart form's, or the agent's
 * arguments - and the rules it is checked against. Every limit of the ingestion API that is about a
 * document is here, in one place, so the endpoints, the batch and the agent's tool cannot disagree
 * about what one may be.
 *
 * <p>Every refusal is an {@link IngestRefusedException} whose message names the field and the limit.
 */
public record IngestDraft(String externalId, String title, String text, String format, String url, String author,
                          String createdAt, String updatedAt, Map<String, ?> metadata) {

    /** The longest text a document may carry - about a 400-page book. */
    public static final int MAX_TEXT_CHARS = 1_000_000;
    /** Metadata: how many entries, how long a key and a value, and the whole of it as JSON. */
    public static final int MAX_METADATA_ENTRIES = 50;
    public static final int MAX_METADATA_KEY = 64;
    public static final int MAX_METADATA_VALUE = 1_000;
    public static final int MAX_METADATA_JSON = 16_384;

    // Letters, digits and a few separators: an id travels in a URL path (DELETE .../documents/{id}),
    // where a slash would end it and a space would have to be escaped by every script.
    static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@~+=-]{0,"
            + (IngestedDocument.EXTERNAL_ID_MAX - 1) + "}");
    private static final Pattern METADATA_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.-]*");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A draft from a JSON object - a single upsert's body or one entry of a batch. */
    public static IngestDraft fromJson(Map<String, ?> body) {
        if (body == null) throw new IngestRefusedException("The request has no document in it.");
        Object metadata = body.get("metadata");
        if (metadata != null && !(metadata instanceof Map<?, ?>)) {
            throw new IngestRefusedException("metadata must be an object of names and values.");
        }
        @SuppressWarnings("unchecked")
        Map<String, ?> meta = (Map<String, ?>) metadata;
        return new IngestDraft(str(body.get("id")), str(body.get("title")), str(body.get("text")),
                str(body.get("format")), str(body.get("url")), str(body.get("author")),
                str(body.get("createdAt")), str(body.get("updatedAt")), meta);
    }

    private static String str(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IngestRefusedException("A document's fields other than metadata must be text.");
    }

    /** The checked id. */
    public String checkedExternalId() {
        return checkExternalId(externalId);
    }

    static String checkExternalId(String raw) {
        String id = raw == null ? "" : raw.strip();
        if (id.isEmpty()) throw new IngestRefusedException("id is required - your own id for the document, used to replace or delete it.");
        if (id.length() > IngestedDocument.EXTERNAL_ID_MAX) {
            throw new IngestRefusedException("id is longer than " + IngestedDocument.EXTERNAL_ID_MAX + " characters.");
        }
        if (!EXTERNAL_ID.matcher(id).matches()) {
            throw new IngestRefusedException("id may use letters, digits and . _ : @ ~ + = - only, and must start "
                    + "with a letter or a digit.");
        }
        return id;
    }

    /** The checked title; {@code fallback} (a file's name) when none was sent. */
    public String checkedTitle(String fallback) {
        String t = title == null || title.isBlank() ? fallback : title;
        t = t == null ? "" : oneLine(t);
        if (t.isEmpty()) throw new IngestRefusedException("title is required.");
        if (t.length() > IngestedDocument.TITLE_MAX) {
            throw new IngestRefusedException("title is longer than " + IngestedDocument.TITLE_MAX + " characters.");
        }
        return t;
    }

    /** The checked text of a text document. */
    public String checkedText() {
        if (text == null || text.isBlank()) throw new IngestRefusedException("text is required, or send a file instead.");
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IngestRefusedException.TooLarge("text is longer than " + MAX_TEXT_CHARS
                    + " characters. Send a document this long as a file.");
        }
        // NUL is refused by every store the text reaches (PostgreSQL, Lucene, ChromaDB).
        return text.replace("\0", "");
    }

    /** True for plain text, false for markdown (the default). */
    public boolean plainText() {
        if (format == null || format.isBlank()) return false;
        String f = format.strip().toLowerCase(Locale.ROOT);
        if (f.equals("markdown") || f.equals("md")) return false;
        if (f.equals("text") || f.equals("plain")) return true;
        throw new IngestRefusedException("format must be markdown or text.");
    }

    public String checkedUrl() {
        if (url == null || url.isBlank()) return null;
        String u = url.strip();
        if (u.length() > IngestedDocument.URL_MAX) {
            throw new IngestRefusedException("url is longer than " + IngestedDocument.URL_MAX + " characters.");
        }
        URI uri;
        try {
            uri = URI.create(u);
        } catch (IllegalArgumentException e) {
            throw new IngestRefusedException("url is not a valid web address.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IngestRefusedException("url must start with http:// or https://.");
        }
        return u;
    }

    public String checkedAuthor() {
        if (author == null || author.isBlank()) return null;
        String a = oneLine(author);
        if (a.length() > IngestedDocument.AUTHOR_MAX) {
            throw new IngestRefusedException("author is longer than " + IngestedDocument.AUTHOR_MAX + " characters.");
        }
        return a;
    }

    public Instant checkedCreatedAt() {
        return instant("createdAt", createdAt);
    }

    public Instant checkedUpdatedAt() {
        return instant("updatedAt", updatedAt);
    }

    // An instant with its offset (2026-10-14T09:30:00Z, +07:00), or a bare date taken as its start in UTC.
    static Instant instant(String field, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.strip();
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException notADateTime) {
            try {
                return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADate) {
                throw new IngestRefusedException(field + " must be an ISO-8601 date or time, such as "
                        + "2026-10-14T09:30:00Z.");
            }
        }
    }

    /** The metadata as stored - compact JSON - or null for none. */
    public String checkedMetadataJson() {
        if (metadata == null || metadata.isEmpty()) return null;
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw new IngestRefusedException("metadata has more than " + MAX_METADATA_ENTRIES + " entries.");
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : metadata.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().strip();
            if (key.isEmpty() || key.length() > MAX_METADATA_KEY || !METADATA_KEY.matcher(key).matches()) {
                throw new IngestRefusedException("A metadata name must be 1 to " + MAX_METADATA_KEY
                        + " letters, digits, spaces and . _ - characters.");
            }
            Object value = e.getValue();
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IngestRefusedException("A metadata value must be text, a number or true or false.");
            }
            if (value instanceof String s) {
                value = oneLine(s);
                if (((String) value).length() > MAX_METADATA_VALUE) {
                    throw new IngestRefusedException("A metadata value is longer than " + MAX_METADATA_VALUE
                            + " characters.");
                }
            }
            clean.put(key, value);
        }
        try {
            String json = JSON.writeValueAsString(clean);
            if (json.length() > MAX_METADATA_JSON) {
                throw new IngestRefusedException("metadata is larger than " + (MAX_METADATA_JSON / 1024) + " KB.");
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new IngestRefusedException("metadata could not be read.");
        }
    }

    /** Stored metadata read back, in its order; an empty map for none. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    // Line breaks and control characters out of a one-line field: a title or an author is rendered
    // into the document's header, where a line break would start a line of its own.
    static String oneLine(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') sb.append(' ');
            else if (!(cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp <= 0x9F))) sb.appendCodePoint(cp);
        });
        return sb.toString().strip();
    }

    static String sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                md.update(p);
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
