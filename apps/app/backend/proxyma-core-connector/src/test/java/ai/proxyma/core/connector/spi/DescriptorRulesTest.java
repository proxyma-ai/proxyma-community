package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a descriptor decides - the questions that used to be a switch per caller, now asked of the
 * catalog - and the server-side check of a configuration against its fields.
 */
class DescriptorRulesTest {

    private final ConnectorCatalog catalog = ConnectorCatalog.descriptorsOnly();

    @Test
    @DisplayName("every type that predates the framework is described, and answers what its old predicates did")
    void legacyTypesKeepTheirAnswers() {
        for (ConnectorType type : List.of(ConnectorType.LOCAL_FOLDER, ConnectorType.UPLOADED_FILES, ConnectorType.FILES,
                ConnectorType.JIRA, ConnectorType.CONFLUENCE, ConnectorType.BITBUCKET, ConnectorType.SVN,
                ConnectorType.NETWORK_SHARE, ConnectorType.WEB_PAGE)) {
            assertThat(catalog.descriptor(type)).as("%s", type).isNotNull();
        }
        // isShareable: the content was read through an access check nobody else passed.
        assertThat(catalog.isShareable(ConnectorType.JIRA)).isTrue();
        assertThat(catalog.isShareable(ConnectorType.WEB_PAGE)).isTrue();
        assertThat(catalog.isShareable(ConnectorType.LOCAL_FOLDER)).isTrue();
        assertThat(catalog.isShareable(ConnectorType.NETWORK_SHARE)).isFalse();
        // hasFileTypeFilter / hasRecursiveScope.
        assertThat(catalog.hasFileTypeFilter(ConnectorType.SVN)).isTrue();
        assertThat(catalog.hasRecursiveScope(ConnectorType.SVN)).isTrue();
        assertThat(catalog.hasFileTypeFilter(ConnectorType.BITBUCKET)).isFalse();
        assertThat(catalog.requiresEnterprise(ConnectorType.JIRA)).isFalse();
        // What the agent's status listing names: a field for a repository URL, a share path or a seed
        // URL; nothing for a key read on a server (the server is named instead), or for a folder.
        assertThat(catalog.descriptor(ConnectorType.SVN).target("{\"repoPath\":\"https://svn.example/r\"}"))
                .isEqualTo("https://svn.example/r");
        assertThat(catalog.descriptor(ConnectorType.WEB_PAGE).target("{\"seedUrl\":\"https://docs.example\"}"))
                .isEqualTo("https://docs.example");
        assertThat(catalog.descriptor(ConnectorType.BITBUCKET).target("{\"projectKey\":\"P\",\"repoSlug\":\"r\"}"))
                .isNull();
        assertThat(catalog.descriptor(ConnectorType.LOCAL_FOLDER).target("C:/docs")).isNull();
        // ConnectionProtocol.forSourceType.
        assertThat(catalog.protocolFor(ConnectorType.SVN)).isEqualTo(ConnectionProtocol.SVN);
        assertThat(catalog.protocolFor(ConnectorType.WEB_PAGE)).isNull();
    }

    @Test
    @DisplayName("Microsoft 365 is described by its own module alone: a catalog without it has no such type or service")
    void microsoftIsNotACoreType() {
        // Its descriptors are Proxyma Enterprise's (proxyma-ee-connector-microsoft365, where
        // MicrosoftDescriptorsTest pins what they say). The Community editions have no plugin and no
        // descriptor, which is how every registration and connection of it is refused.
        for (ConnectorType type : List.of(ConnectorType.ONEDRIVE, ConnectorType.SHAREPOINT, ConnectorType.TEAMS,
                ConnectorType.TEAMS_CHAT, ConnectorType.OUTLOOK)) {
            assertThat(catalog.descriptor(type)).as("%s", type).isNull();
            assertThat(catalog.isShareable(type)).as("%s", type).isFalse();
        }
        assertThat(catalog.protocol(ConnectionProtocol.MICROSOFT_365)).isNull();
        assertThat(catalog.visibleProtocols(true)).extracting(ProtocolDescriptor::protocol)
                .doesNotContain(ConnectionProtocol.MICROSOFT_365);
    }

    @Test
    @DisplayName("the sync cadence a setting can override is named by the descriptor")
    void syncIntervalSettings() {
        assertThat(catalog.descriptor(ConnectorType.JIRA).syncIntervalSetting()).isEqualTo("jira.sync-interval-seconds");
        assertThat(catalog.descriptor(ConnectorType.BITBUCKET).syncIntervalSetting()).isEqualTo("bitbucket.sync-interval-seconds");
        assertThat(catalog.descriptor(ConnectorType.NETWORK_SHARE).syncIntervalSetting())
                .isEqualTo("network-share.sync-interval-seconds");
        assertThat(catalog.descriptor(ConnectorType.WEB_PAGE).defaultSyncInterval().toHours()).isEqualTo(24);
        ConnectorDescriptor fresh = ConnectorDescriptor.builder(ConnectorType.JIRA).build();
        assertThat(fresh.syncIntervalSetting()).isEqualTo("jira.sync-interval-seconds");
        assertThat(fresh.target("{\"projectKey\":\"P\"}")).isNull();
    }

    @Test
    @DisplayName("an Enterprise type is offered on Enterprise Server alone; a plugin's descriptor wins over the built-in one")
    void editionFilterAndPlugins() {
        ConnectorDescriptor custom = ConnectorDescriptor.builder(ConnectorType.TEAMS)
                .displayName("Custom Teams").capabilities(Capabilities.builder().edition(Edition.ENTERPRISE).build()).build();
        ConnectorPlugin teams = new ConnectorPlugin() {
            @Override public ConnectorDescriptor descriptor() { return custom; }
            @Override public ConnectorSession open(SourceContext context) { return null; }
        };
        ConnectorDescriptor jiraDescriptor = CoreDescriptors.of(ConnectorType.JIRA);
        ConnectorPlugin jira = new ConnectorPlugin() {
            @Override public ConnectorDescriptor descriptor() { return jiraDescriptor; }
            @Override public ConnectorSession open(SourceContext context) { return null; }
        };
        ConnectorCatalog withPlugins = ConnectorCatalog.of(List.of(teams, jira), List.of());

        assertThat(withPlugins.visibleTypes(false)).extracting(ConnectorDescriptor::type).containsExactly(ConnectorType.JIRA);
        assertThat(withPlugins.visibleTypes(true)).extracting(ConnectorDescriptor::type)
                .containsExactly(ConnectorType.JIRA, ConnectorType.TEAMS);
        assertThat(withPlugins.descriptor(ConnectorType.TEAMS).displayName()).isEqualTo("Custom Teams");
        assertThat(withPlugins.typeForWireName("jira")).isEqualTo(ConnectorType.JIRA);
        assertThat(withPlugins.visibleProtocols(false)).extracting(ProtocolDescriptor::protocol)
                .doesNotContain(ConnectionProtocol.MICROSOFT_365);
    }

    @Test
    @DisplayName("two plugins for one type are refused when the catalog is built, not resolved by load order")
    void duplicatePluginsAreRefused() {
        ConnectorDescriptor d = CoreDescriptors.of(ConnectorType.JIRA);
        ConnectorPlugin a = new ConnectorPlugin() {
            @Override public ConnectorDescriptor descriptor() { return d; }
            @Override public ConnectorSession open(SourceContext context) { return null; }
        };
        ConnectorPlugin b = new ConnectorPlugin() {
            @Override public ConnectorDescriptor descriptor() { return d; }
            @Override public ConnectorSession open(SourceContext context) { return null; }
        };
        assertThatThrownBy(() -> ConnectorCatalog.of(List.of(a, b), List.of()).types())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Two plugins serve JIRA");
    }

    @Test
    @DisplayName("a wire name is the constructor's; an enum name of any type reads as its wire name")
    void wireNames() {
        assertThat(ConnectorType.NETWORK_SHARE.wireName()).isEqualTo("networkshare");
        assertThat(ConnectorType.normalizeWireName("TEAMS_CHAT")).isEqualTo("teamschat");
        assertThat(ConnectorType.normalizeWireName("UPLOADED_FILES")).isEqualTo("files");
        assertThat(ConnectorType.normalizeWireName("something_else")).isEqualTo("something_else");
        assertThat(ConnectorType.forWireName("files")).containsExactly(
                ConnectorType.LOCAL_FOLDER, ConnectorType.UPLOADED_FILES, ConnectorType.FILES);
    }

    // -- ConfigValidator -------------------------------------------------------------------------

    private static final List<FieldSpec> FIELDS = List.of(
            FieldSpec.text("repository", "Repository").asRequired().pattern("[\\w.-]+/[\\w.-]+").placeholder("owner/repo"),
            FieldSpec.bool("includeDocs", "Docs files").defaultValue("false"),
            FieldSpec.number("depth", "Depth").range(0, 5),
            FieldSpec.select("state", "State", List.of(new FieldSpec.Option("open", "Open"), new FieldSpec.Option("all", "All"))),
            FieldSpec.secret("token", "Token"),
            FieldSpec.list("exclude", "Exclude"));

    @Test
    @DisplayName("a configuration is checked as the dialog draws it: required, pattern, ranges, choices and one line")
    void configurationIsValidated() {
        Map<String, String> ok = ConfigValidator.validate(FIELDS, Map.of("repository", " acme/web ", "depth", 3,
                "state", "all", "exclude", List.of("a/", " b/ "), "unknown", "dropped"));
        assertThat(ok).containsEntry("repository", "acme/web").containsEntry("includeDocs", "false")
                .containsEntry("depth", "3").containsEntry("state", "all").containsEntry("exclude", "a/\nb/")
                .doesNotContainKey("unknown");

        assertThatThrownBy(() -> ConfigValidator.validate(FIELDS, Map.of()))
                .hasMessage("Repository is required.");
        assertThatThrownBy(() -> ConfigValidator.validate(FIELDS, Map.of("repository", "not a repo")))
                .hasMessage("Repository is not in the form expected (for example owner/repo).");
        assertThatThrownBy(() -> ConfigValidator.validate(FIELDS, Map.of("repository", "a/b", "depth", "9")))
                .hasMessage("Depth must be between 0 and 5.");
        assertThatThrownBy(() -> ConfigValidator.validate(FIELDS, Map.of("repository", "a/b", "state", "closed")))
                .hasMessage("State must be one of the choices offered.");
        assertThatThrownBy(() -> ConfigValidator.validate(FIELDS, Map.of("repository", "a/b\nmaxPages=9999")))
                .hasMessage("Repository must be on one line.");
    }

    @Test
    @DisplayName("an edit keeps what it leaves out, and a secret it sends blank")
    void editsKeepStoredValues() {
        Map<String, String> stored = Map.of("repository", "acme/web", "includeDocs", "true", "token", "s3cret");
        Map<String, String> next = ConfigValidator.validate(FIELDS, Map.of("token", "", "depth", "2"), stored);
        assertThat(next).containsEntry("repository", "acme/web").containsEntry("includeDocs", "true")
                .containsEntry("token", "s3cret").containsEntry("depth", "2");
    }

    @Test
    @DisplayName("a configuration round-trips through its stored JSON")
    void configJson() {
        String json = ConfigJson.write(Map.of("repository", "acme/web"));
        assertThat(ConfigJson.parse(json)).containsExactly(Map.entry("repository", "acme/web"));
        assertThat(ConfigJson.parse("https://jira.example.com")).isEmpty();
        assertThat(ConfigJson.parse(null)).isEmpty();
    }
}
