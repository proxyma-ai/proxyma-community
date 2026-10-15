package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proves a connection works with one cheap authenticated call - "who am I" on the service. One
 * implementation per family of protocols; the controller picks the one that supports the protocol.
 *
 * MUST NOT throw: every failure is a {@link Result} whose message the person who just typed the
 * credential can act on, never a stack trace or an HTML error page.
 */
public interface ConnectionTester {

    boolean supports(ConnectionProtocol protocol);

    Result test(ResolvedConnection connection);

    /**
     * {@code message} is the English sentence: "Signed in as Ana", or what to fix. {@code template}
     * is the same sentence with its variable parts as {@code {name}} placeholders, filled from
     * {@code params} - the English text doubles as the frontend's translation key, so a Vietnamese
     * Settings page can say it in Vietnamese. A message with nothing variable is its own template.
     */
    record Result(boolean ok, String message, String template, Map<String, String> params) {
        public static Result ok(String message) { return new Result(true, message, message, Map.of()); }
        public static Result failed(String message) { return new Result(false, message, message, Map.of()); }

        /** {@code params} alternate name, value: {@code ok("Signed in as {user}", "user", "ana")}. */
        public static Result ok(String template, String... params) { return of(true, template, params); }
        public static Result failed(String template, String... params) { return of(false, template, params); }

        private static Result of(boolean ok, String template, String... params) {
            Map<String, String> values = new LinkedHashMap<>();
            String message = template;
            for (int i = 0; i + 1 < params.length; i += 2) {
                String value = params[i + 1] == null ? "" : params[i + 1];
                values.put(params[i], value);
                message = message.replace("{" + params[i] + "}", value);
            }
            return new Result(ok, message, template, Map.copyOf(values));
        }
    }
}
