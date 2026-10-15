package ai.proxyma.core.connector.spi;

import java.util.List;

/**
 * One value a person types when adding a source or a connection - the dialog renders it, the
 * backend checks it (ConfigValidator), and the agent's addSource tool lists it. The same record
 * drives all three, which is the point: a field added here appears in the dialog, is validated on
 * the server and is offered to the agent without anything else changing.
 *
 * <p>{@code label}, {@code placeholder} and {@code help} are English; the page passes the label
 * and the help through t(), so a new connector's Vietnamese is catalog entries and nothing else.
 *
 * @param key          the value's name in the stored configuration (and the request body)
 * @param pattern      a regular expression the whole value must match, or null
 * @param advanced     shown behind "Advanced" in the dialog - a base URL override, an exclude list
 * @param min          for NUMBER, the smallest accepted value, or null
 * @param max          for NUMBER, the largest accepted value, or null
 */
public record FieldSpec(String key, String label, FieldType type, boolean required, String placeholder,
                        String help, String defaultValue, List<Option> options, String pattern,
                        boolean advanced, Integer min, Integer max) {

    public FieldSpec {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("A field needs a key");
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** One choice of a SELECT or MULTISELECT field. */
    public record Option(String value, String label) {}

    public static FieldSpec of(String key, String label, FieldType type) {
        return new FieldSpec(key, label, type, false, null, null, null, List.of(), null, false, null, null);
    }

    public static FieldSpec text(String key, String label) { return of(key, label, FieldType.TEXT); }

    public static FieldSpec url(String key, String label) { return of(key, label, FieldType.URL); }

    public static FieldSpec secret(String key, String label) { return of(key, label, FieldType.SECRET); }

    public static FieldSpec number(String key, String label) { return of(key, label, FieldType.NUMBER); }

    public static FieldSpec bool(String key, String label) { return of(key, label, FieldType.BOOLEAN); }

    public static FieldSpec list(String key, String label) { return of(key, label, FieldType.LIST); }

    public static FieldSpec select(String key, String label, List<Option> options) {
        return of(key, label, FieldType.SELECT).withOptions(options);
    }

    public FieldSpec asRequired() {
        return new FieldSpec(key, label, type, true, placeholder, help, defaultValue, options, pattern, advanced, min, max);
    }

    public FieldSpec placeholder(String value) {
        return new FieldSpec(key, label, type, required, value, help, defaultValue, options, pattern, advanced, min, max);
    }

    public FieldSpec help(String value) {
        return new FieldSpec(key, label, type, required, placeholder, value, defaultValue, options, pattern, advanced, min, max);
    }

    public FieldSpec defaultValue(String value) {
        return new FieldSpec(key, label, type, required, placeholder, help, value, options, pattern, advanced, min, max);
    }

    public FieldSpec withOptions(List<Option> value) {
        return new FieldSpec(key, label, type, required, placeholder, help, defaultValue, value, pattern, advanced, min, max);
    }

    public FieldSpec pattern(String value) {
        return new FieldSpec(key, label, type, required, placeholder, help, defaultValue, options, value, advanced, min, max);
    }

    public FieldSpec asAdvanced() {
        return new FieldSpec(key, label, type, required, placeholder, help, defaultValue, options, pattern, true, min, max);
    }

    public FieldSpec range(Integer low, Integer high) {
        return new FieldSpec(key, label, type, required, placeholder, help, defaultValue, options, pattern, advanced, low, high);
    }

    public boolean isSecret() {
        return type == FieldType.SECRET;
    }
}
