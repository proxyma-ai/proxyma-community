package ai.proxyma.core.connector.spi;

import ai.proxyma.core.common.net.OutboundAddressPolicy;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks a configuration against its descriptor's fields - the same rules the dialog draws, applied
 * on the server, so a request body that skips the dialog (the agent, an MCP client, a script) meets
 * them too. Every refusal is an IllegalArgumentException whose message is the sentence to show.
 *
 * <p>What it returns is what gets stored: only the descriptor's own keys, every value as text, trimmed,
 * on one line where a line break would be a second value (the newline key=value configs of the
 * older types were overwritten that way - a crafted value carried a line of its own).
 */
public final class ConfigValidator {

    static final int MAX_TEXT = 2_000;
    static final int MAX_TEXTAREA = 20_000;

    private ConfigValidator() {}

    /** A new source's configuration: every required field present, defaults filled in. */
    public static Map<String, String> validate(List<FieldSpec> fields, Map<String, ?> input) {
        return validate(fields, input, null);
    }

    /**
     * An edit: {@code stored} is the configuration as it is, and a field the edit leaves out keeps
     * its value. A secret left blank keeps its value too - the page is never sent it, so it cannot
     * send it back.
     */
    public static Map<String, String> validate(List<FieldSpec> fields, Map<String, ?> input, Map<String, String> stored) {
        Map<String, ?> in = input == null ? Map.of() : input;
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldSpec f : fields) {
            boolean given = in.containsKey(f.key());
            String raw = given ? text(in.get(f.key()), f) : null;
            if (raw != null) raw = raw.trim();
            if ((raw == null || raw.isEmpty()) && stored != null && (!given || f.isSecret())) {
                String kept = stored.get(f.key());
                if (kept != null) out.put(f.key(), kept);
                continue;
            }
            if (raw == null || raw.isEmpty()) {
                if (stored == null && f.defaultValue() != null && !f.defaultValue().isEmpty()) {
                    out.put(f.key(), f.defaultValue());
                } else if (f.required()) {
                    throw new IllegalArgumentException(label(f) + " is required.");
                }
                continue;
            }
            out.put(f.key(), check(f, raw));
        }
        return out;
    }

    private static String check(FieldSpec f, String value) {
        if (f.type() != FieldType.TEXTAREA && f.type() != FieldType.LIST
                && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException(label(f) + " must be on one line.");
        }
        int max = f.type() == FieldType.TEXTAREA || f.type() == FieldType.LIST ? MAX_TEXTAREA : MAX_TEXT;
        if (value.length() > max) {
            throw new IllegalArgumentException(label(f) + " is longer than " + max + " characters.");
        }
        String checked = switch (f.type()) {
            case URL -> url(f, value);
            case NUMBER -> number(f, value);
            case BOOLEAN -> bool(f, value);
            case SELECT -> option(f, value);
            case MULTISELECT -> options(f, value);
            case LIST -> list(value);
            default -> value;
        };
        if (f.pattern() != null && !f.pattern().isBlank() && f.type() != FieldType.LIST) {
            boolean matches;
            try {
                matches = Pattern.compile(f.pattern()).matcher(checked).matches();
            } catch (PatternSyntaxException e) {
                matches = true; // a descriptor's own mistake must not refuse every value
            }
            if (!matches) {
                throw new IllegalArgumentException(label(f) + " is not in the form expected"
                        + (f.placeholder() == null ? "." : " (for example " + f.placeholder() + ")."));
            }
        }
        return checked;
    }

    private static String url(FieldSpec f, String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label(f) + " is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException(label(f) + " must start with http:// or https://.");
        }
        OutboundAddressPolicy.check(uri);
        return value.replaceAll("/+$", "");
    }

    private static String number(FieldSpec f, String value) {
        long n;
        try {
            n = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label(f) + " must be a whole number.");
        }
        if (f.min() != null && n < f.min() || f.max() != null && n > f.max()) {
            throw new IllegalArgumentException(label(f) + " must be between " + (f.min() == null ? "0" : f.min())
                    + " and " + (f.max() == null ? String.valueOf(Integer.MAX_VALUE) : f.max()) + ".");
        }
        return String.valueOf(n);
    }

    private static String bool(FieldSpec f, String value) {
        String v = value.toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("false")) return v;
        throw new IllegalArgumentException(label(f) + " must be true or false.");
    }

    private static String option(FieldSpec f, String value) {
        for (FieldSpec.Option o : f.options()) if (o.value().equals(value)) return value;
        throw new IllegalArgumentException(label(f) + " must be one of the choices offered.");
    }

    private static String options(FieldSpec f, String value) {
        List<String> picked = new ArrayList<>();
        for (String part : value.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            option(f, p);
            if (!picked.contains(p)) picked.add(p);
        }
        return String.join(",", picked);
    }

    private static String list(String value) {
        List<String> lines = new ArrayList<>();
        for (String line : value.split("\\r?\\n")) {
            String l = line.trim();
            if (!l.isEmpty()) lines.add(l);
        }
        return String.join("\n", lines);
    }

    // A value as the request sent it - JSON booleans, numbers and arrays included.
    private static String text(Object value, FieldSpec f) {
        if (value == null) return null;
        if (value instanceof Collection<?> c) {
            List<String> parts = new ArrayList<>();
            for (Object o : c) if (o != null) parts.add(o.toString().trim());
            return String.join(f.type() == FieldType.MULTISELECT ? "," : "\n", parts);
        }
        return value.toString();
    }

    private static String label(FieldSpec f) {
        return f.label() == null || f.label().isBlank() ? f.key() : f.label();
    }
}
