package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything about one SERVICE a connection talks to: how it signs in, where its server is, which
 * source types read through it, whether the agent has write tools for it, which edition offers it.
 * The connection dialog is rendered from this; the connection service validates against it.
 *
 * @param baseUrl       the server address field - null for a service with no address to type (a
 *                      sign-in to a fixed cloud). Its default value is the vendor's public API, so
 *                      the same connector reaches a self-hosted variant, or a test's loopback
 *                      server, by changing it
 * @param supportsWrite whether the agent has any write tools for this service. A Write switch with
 *                      nothing behind it is the failure .claude/rules/security.md names, so the
 *                      switch is not offered where it would be one
 * @param customDialog  the page keeps a bespoke form for it (an MCP server)
 */
public record ProtocolDescriptor(ConnectionProtocol protocol, String displayName, String mark,
                                 List<AuthMethod> authMethods, FieldSpec baseUrl, Set<ConnectorType> sourceTypes,
                                 boolean supportsWrite, Edition edition, String docsAnchor, boolean customDialog) {

    public ProtocolDescriptor {
        if (protocol == null) throw new IllegalArgumentException("A protocol descriptor needs a protocol");
        authMethods = authMethods == null ? List.of() : List.copyOf(authMethods);
        sourceTypes = sourceTypes == null ? Set.of() : Set.copyOf(sourceTypes);
        if (edition == null) edition = Edition.COMMUNITY;
    }

    public boolean hasBaseUrl() {
        return baseUrl != null;
    }

    public String defaultBaseUrl() {
        return baseUrl == null ? null : baseUrl.defaultValue();
    }

    /** The first way to sign in - the one a connection made before a choice existed uses. */
    public AuthMethod primaryAuth() {
        return authMethods.isEmpty() ? AuthMethod.of(AuthKind.NONE, "None") : authMethods.get(0);
    }

    /** The method of {@code kind}, else the primary one. */
    public AuthMethod auth(AuthKind kind) {
        if (kind != null) {
            for (AuthMethod m : authMethods) if (m.kind() == kind) return m;
        }
        return primaryAuth();
    }

    public boolean offers(AuthKind kind) {
        return authMethods.stream().anyMatch(m -> m.kind() == kind);
    }

    public static Builder builder(ConnectionProtocol protocol) {
        return new Builder(protocol);
    }

    public static final class Builder {
        private final ConnectionProtocol protocol;
        private String displayName;
        private String mark;
        private List<AuthMethod> authMethods = List.of();
        private FieldSpec baseUrl;
        private Set<ConnectorType> sourceTypes = Set.of();
        private boolean supportsWrite;
        private Edition edition = Edition.COMMUNITY;
        private String docsAnchor;
        private boolean customDialog;

        private Builder(ConnectionProtocol protocol) { this.protocol = protocol; }

        public Builder displayName(String value) { this.displayName = value; return this; }
        public Builder mark(String value) { this.mark = value; return this; }
        public Builder auth(AuthMethod... value) { this.authMethods = List.of(value); return this; }
        public Builder baseUrl(FieldSpec value) { this.baseUrl = value; return this; }
        public Builder sourceTypes(ConnectorType... value) { this.sourceTypes = Set.of(value); return this; }
        public Builder supportsWrite() { this.supportsWrite = true; return this; }
        public Builder edition(Edition value) { this.edition = value; return this; }
        public Builder docsAnchor(String value) { this.docsAnchor = value; return this; }
        public Builder customDialog() { this.customDialog = true; return this; }

        public ProtocolDescriptor build() {
            return new ProtocolDescriptor(protocol, displayName == null ? protocol.name() : displayName,
                    mark == null ? protocol.name().toLowerCase(Locale.ROOT) : mark,
                    authMethods, baseUrl, sourceTypes, supportsWrite, edition, docsAnchor, customDialog);
        }
    }
}
