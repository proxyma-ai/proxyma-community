package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * A SERVICE'S BUILT-IN OAUTH CLIENT, for the person who types none (2026-11-07, the maintainer: most people "don't know
 * or don't want to setup Google Cloud ... they'll just install the app, click login with their Youtube account"). A
 * browser sign-in on a connection with no Client ID of its own goes through the client named by
 * {@code proxyma.oauth.builtin.<service>.client-id} and {@code .client-secret} - the YouTube one is
 * {@code proxyma.oauth.builtin.youtube.*} - and a person who types their own client uses theirs instead, which the
 * connection's edit rules already treat as a new sign-in (ConnectorConnectionService.forgetGrantIfClientChanged).
 *
 * <p>DESKTOP ONLY. Such a client is a Google "Desktop app" client: it signs in to a loopback address, which only the
 * Desktop window has - a Home Server is reached by its host's name - and Enterprise Server sets its deployment's own
 * client (OAuth2ConnectionService.deploymentClient). Its secret is not a secret in Google's sense for that client type:
 * an installed application cannot keep one, and PKCE is what protects the code. Unset, nothing is offered and every
 * connection asks for its own client as before.
 *
 * <p>BEHIND AN ADD-ON, WHEN ONE IS NAMED. A client's quota is the whole of Proxyma's Google project, shared by everybody
 * who signs in through it; {@code .requires-addon} names the add-ons - any one in force, a trial included - that open it
 * (the YouTube one: the YouTube assistant or Constellations), so it serves the people it was made for. Without one in
 * force the connection asks for its own client, and one signed in through the built-in client needs signing in again.
 */
@Component
public class BuiltInOAuthClients {

    static final String PREFIX = "proxyma.oauth.builtin.";

    /** A client's id and secret. */
    public record Client(String id, String secret) {}

    private final Environment env;
    private final boolean multiUser;
    private final boolean homeServer;
    // The add-ons in force (FeatureGateService); optional, so a test can build this without them.
    private java.util.function.Supplier<java.util.Set<String>> addons = java.util.Set::of;

    public BuiltInOAuthClients(Environment env, @Value("${proxyma.multi-user:false}") boolean multiUser,
                               @Value("${proxyma.home-server:false}") boolean homeServer) {
        this.env = env;
        this.multiUser = multiUser;
        this.homeServer = homeServer;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFeatureGate(ai.proxyma.core.license.FeatureGateService gate) {
        if (gate != null) this.addons = () -> gate.getFeatures().addons();
    }

    /** Tests: the add-ons in force. */
    void setAddonsForTest(java.util.function.Supplier<java.util.Set<String>> addons) {
        this.addons = addons;
    }

    /** The built-in client for a service on this edition, or empty: none set, or not Desktop. */
    public Optional<Client> of(ConnectionProtocol protocol) {
        if (protocol == null || multiUser || homeServer || env == null) return Optional.empty();
        String base = PREFIX + protocol.name().toLowerCase(Locale.ROOT).replace('_', '-');
        String id = env.getProperty(base + ".client-id", "").trim();
        if (id.isEmpty() || !addonInForce(env.getProperty(base + ".requires-addon", ""))) return Optional.empty();
        String secret = env.getProperty(base + ".client-secret", "").trim();
        return Optional.of(new Client(id, secret.isEmpty() ? null : secret));
    }

    /**
     * Whether this edition carries a built-in client for the service at all, offered or not - one whose add-on lapsed
     * included: a connection signed in through it then must stop (OAuth2ConnectionService.refuseBeforeSending).
     */
    public boolean configured(ConnectionProtocol protocol) {
        if (protocol == null || multiUser || homeServer || env == null) return false;
        String base = PREFIX + protocol.name().toLowerCase(Locale.ROOT).replace('_', '-');
        return !env.getProperty(base + ".client-id", "").trim().isEmpty();
    }

    // None named, or one of those named in force.
    private boolean addonInForce(String required) {
        if (required == null || required.isBlank()) return true;
        java.util.Set<String> inForce = addons.get();
        for (String name : required.split(",")) {
            if (inForce.contains(name.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Whether a service signs in with no client typed, here. */
    public boolean available(ConnectionProtocol protocol) {
        return of(protocol).isPresent();
    }
}
