package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectorType;

import java.util.List;
import java.util.Set;

/**
 * One way a connection signs in, and what is typed for it. A field of type SECRET is kept encrypted
 * on the connection and never sent back to the page; the field keyed {@code username} is kept in
 * the connection's username column; every other field is a non-secret setting (a workspace id, a
 * region) kept in its {@code settingsJson}.
 *
 * <p>For {@link AuthKind#OAUTH2_CODE} the fields are the OAuth client a person registered with the
 * service, asked for on the single-user editions; on Enterprise Server the client is the
 * deployment's, set by an administrator, and the dialog shows a sign-in button and nothing else.
 *
 * @param sourceTypes the source types a connection signed in THIS way serves, where that is fewer
 *                    than its service's {@link ProtocolDescriptor#sourceTypes()} - empty for all of
 *                    them. An Atlassian Cloud sign-in reaches a whole site, Jira and Confluence
 *                    alike; a Data Center token reaches the one server it was made on, so the Data
 *                    Center method of the Jira service serves Jira alone.
 */
public record AuthMethod(AuthKind kind, String label, List<FieldSpec> fields, Set<ConnectorType> sourceTypes) {

    /** The field whose value goes into the connection's username column. */
    public static final String USERNAME = "username";

    public AuthMethod {
        fields = fields == null ? List.of() : List.copyOf(fields);
        sourceTypes = sourceTypes == null ? Set.of() : Set.copyOf(sourceTypes);
    }

    public AuthMethod(AuthKind kind, String label, List<FieldSpec> fields) {
        this(kind, label, fields, Set.of());
    }

    public static AuthMethod of(AuthKind kind, String label, FieldSpec... fields) {
        return new AuthMethod(kind, label, List.of(fields));
    }

    /** This method, serving only {@code types} of its service's source types. */
    public AuthMethod servingOnly(ConnectorType... types) {
        return new AuthMethod(kind, label, fields, Set.of(types));
    }

    /** Whether a connection signed in this way may serve {@code type}, which its service lists. */
    public boolean serves(ConnectorType type) {
        return sourceTypes.isEmpty() || sourceTypes.contains(type);
    }

    /** The secret fields, in order. */
    public List<FieldSpec> secretFields() {
        return fields.stream().filter(FieldSpec::isSecret).toList();
    }

    /** The fields that are neither secret nor the username - kept as the connection's settings. */
    public List<FieldSpec> settingFields() {
        return fields.stream().filter(f -> !f.isSecret() && !USERNAME.equals(f.key())).toList();
    }

    public boolean hasUsername() {
        return fields.stream().anyMatch(f -> USERNAME.equals(f.key()));
    }
}
