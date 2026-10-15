package ai.proxyma.core.connector.spi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What testing a connection found. {@code message} is the English sentence: "Signed in as Ana", or
 * what to fix. {@code template} is the same sentence with its variable parts as {@code {name}}
 * placeholders, filled from {@code params} - the English text doubles as the page's translation
 * key, so a Vietnamese Settings page can say it in Vietnamese. A message with nothing variable is
 * its own template.
 */
public record ConnectionTestResult(boolean ok, String message, String template, Map<String, String> params) {

    public static ConnectionTestResult ok(String message) {
        return new ConnectionTestResult(true, message, message, Map.of());
    }

    public static ConnectionTestResult failed(String message) {
        return new ConnectionTestResult(false, message, message, Map.of());
    }

    /** {@code params} alternate name, value: {@code ok("Signed in as {user}", "user", "ana")}. */
    public static ConnectionTestResult ok(String template, String... params) {
        return of(true, template, params);
    }

    public static ConnectionTestResult failed(String template, String... params) {
        return of(false, template, params);
    }

    private static ConnectionTestResult of(boolean ok, String template, String... params) {
        Map<String, String> values = new LinkedHashMap<>();
        String message = template;
        for (int i = 0; i + 1 < params.length; i += 2) {
            String value = params[i + 1] == null ? "" : params[i + 1];
            values.put(params[i], value);
            message = message.replace("{" + params[i] + "}", value);
        }
        return new ConnectionTestResult(ok, message, template, Map.copyOf(values));
    }
}
