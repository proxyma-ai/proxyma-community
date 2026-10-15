package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.webpage.WebCrawlConnector;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The descriptors of the source types and services the application's own modules name - files,
 * web pages, Jira, Confluence, Bitbucket Data Center, SVN and network shares, and their services -
 * the facts that used to be a switch per caller (ConnectorType's predicates, ConnectionProtocol's
 * table, isDueForSync, the SPA's per-type maps), written down once.
 *
 * <p>DATA, kept in core-connector so every module - and every test that builds a catalog with no
 * plugins ({@link ConnectorCatalog#descriptorsOnly()}) - reads what these types are without a
 * Spring context: the registry's own rules for a folder, a website or a share, the tools' write
 * gates. Each type's plugin serves its descriptor from here - the ones in proxyma-core-connector
 * beside the code the registry and the tools share with them, the Atlassian ones in
 * proxyma-connectors. A type that only its own plugin needs to describe (GitHub, Slack and the other
 * connectors of proxyma-connectors) keeps its descriptor beside its plugin instead - as do the
 * Microsoft 365 types, a Proxyma Enterprise module's: a catalog without that module describes them
 * not at all, which is how the Community editions refuse them (no such type, no such service).
 *
 * <p>All of these types are built on sessions now; nothing here describes an older way of storing or
 * syncing a source. What differs from a new type's defaults is said in plain descriptor fields: a
 * sync-interval setting under another name ({@code syncSetting}), the field the agent's status
 * listing names ({@code targetField}).
 */
public final class CoreDescriptors {

    private CoreDescriptors() {}

    private static final Duration HOURLY = Duration.ofHours(1);

    private static final FieldSpec FOLDER_PATH = FieldSpec.text("path", "Folder path").asRequired()
            .placeholder("C:/documents");

    private static ConnectorDescriptor files(ConnectorType type, String noun, boolean listed) {
        ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(type)
                .displayName("Files")
                .description("A folder of documents - PDF, Office, text and more.")
                .noun(noun, "Files")
                .addTitle("Add a folder")
                .order(10)
                .category(Category.FILES)
                .mark("files")
                .fields(FOLDER_PATH)
                .capabilities(Capabilities.builder().fileTypeFilter().recursiveScope().shareable()
                        .customDialog().liveRead().build())
                .syncEvery(HOURLY)
                .docsAnchor("files");
        if (!listed) b.unlisted();
        return b.build();
    }

    private static final List<ConnectorDescriptor> TYPES = List.of(
            files(ConnectorType.LOCAL_FOLDER, "Local folder", true),
            files(ConnectorType.UPLOADED_FILES, "Upload files", true),
            files(ConnectorType.FILES, "Local folder", false),

            ConnectorDescriptor.builder(ConnectorType.WEB_PAGE)
                    .displayName("Web pages")
                    .description("A website, crawled from a starting address to a depth and page count you set.")
                    .noun("Web page", "Web pages")
                    .addTitle("Add a web page")
                    .order(20)
                    .category(Category.WEB)
                    .mark("web")
                    .fields(
                            FieldSpec.url("seedUrl", "Starting URL").asRequired().placeholder("https://docs.example.com/"),
                            FieldSpec.text("name", "Name"),
                            FieldSpec.number("maxDepth", "Crawl depth")
                                    .defaultValue(String.valueOf(WebCrawlConnector.DEFAULT_MAX_DEPTH))
                                    .range(0, WebCrawlConnector.MAX_CRAWL_DEPTH),
                            FieldSpec.number("maxPages", "Max pages")
                                    .defaultValue(String.valueOf(WebCrawlConnector.DEFAULT_MAX_PAGES))
                                    .range(1, WebCrawlConnector.MAX_CRAWL_PAGES),
                            FieldSpec.bool("sameDomainOnly", "Stay on the same site").defaultValue("true"),
                            FieldSpec.bool("renderJs", "Render JavaScript").defaultValue("false").asAdvanced(),
                            FieldSpec.text("includePattern", "Only addresses matching").asAdvanced())
                    .capabilities(Capabilities.builder().shareable().customDialog().build())
                    .syncEvery(Duration.ofDays(1))
                    .docsAnchor("web-pages")
                    .targetField("seedUrl")
                    .build(),

            ConnectorDescriptor.builder(ConnectorType.JIRA)
                    .protocol(ConnectionProtocol.JIRA)
                    .displayName("Jira")
                    .description("A Jira project's issues, with their comments and history.")
                    .noun("Jira project", "Jira projects")
                    .addTitle("Add a Jira project")
                    .order(30)
                    .category(Category.TICKETS)
                    .mark("jira")
                    .fields(FieldSpec.text("projectKey", "Project key").asRequired().placeholder("PROJ")
                            .help("The letters its issue numbers start with: PROJ in PROJ-123."))
                    .capabilities(Capabilities.builder().incremental().shareable().liveRead().build())
                    .syncEvery(HOURLY)
                    .docsAnchor("jira")
                    .build(),

            ConnectorDescriptor.builder(ConnectorType.CONFLUENCE)
                    .protocol(ConnectionProtocol.CONFLUENCE)
                    .displayName("Confluence")
                    .description("A Confluence space's pages and their comments.")
                    .noun("Confluence space", "Confluence spaces")
                    .addTitle("Add a Confluence space")
                    .order(40)
                    .category(Category.DOCS)
                    .mark("confluence")
                    .fields(FieldSpec.text("spaceKey", "Space key").asRequired().placeholder("DOCS"))
                    .capabilities(Capabilities.builder().incremental().shareable().liveRead().build())
                    .syncEvery(HOURLY)
                    .docsAnchor("confluence")
                    .build(),

            ConnectorDescriptor.builder(ConnectorType.BITBUCKET)
                    .protocol(ConnectionProtocol.BITBUCKET)
                    .displayName("Bitbucket")
                    .description("A Bitbucket repository's pull requests and commits.")
                    .noun("Bitbucket repository", "Bitbucket repositories")
                    .addTitle("Add a Bitbucket repository")
                    .order(50)
                    .category(Category.CODE)
                    .mark("bitbucket")
                    .fields(FieldSpec.text("projectKey", "Project key").asRequired().placeholder("PROJ"),
                            FieldSpec.text("repoSlug", "Repository slug").asRequired().placeholder("my-repo"))
                    .capabilities(Capabilities.builder().incremental().shareable().liveRead().build())
                    .syncEvery(HOURLY)
                    .docsAnchor("bitbucket")
                    .build(),

            ConnectorDescriptor.builder(ConnectorType.SVN)
                    .protocol(ConnectionProtocol.SVN)
                    .displayName("SVN")
                    .description("The files of a Subversion repository, by path under the connection's server.")
                    .noun("SVN repository", "SVN repositories")
                    .addTitle("Add an SVN repository")
                    .order(60)
                    .category(Category.CODE)
                    .mark("subversion")
                    .fields(FieldSpec.text("repoPath", "Repository path").asRequired().placeholder("projects/my-repo"),
                            FieldSpec.text("repoName", "Name").asAdvanced()
                                    .help("The last folder of the path, when left empty."))
                    .capabilities(Capabilities.builder().fileTypeFilter().recursiveScope().shareable().liveRead().build())
                    .syncEvery(HOURLY)
                    .docsAnchor("svn")
                    .targetField("repoPath")
                    .build(),

            ConnectorDescriptor.builder(ConnectorType.NETWORK_SHARE)
                    .protocol(ConnectionProtocol.NETWORK_SHARE)
                    .displayName("Network shares")
                    .description("A folder on a Windows file server, read as your own domain account.")
                    .noun("Network share", "Network shares")
                    .addTitle("Add a network share")
                    .order(70)
                    .category(Category.FILES)
                    .mark("network")
                    .fields(FieldSpec.text("uncPath", "Network path").asRequired()
                            .placeholder("\\\\fileserver\\share\\folder"))
                    .capabilities(Capabilities.builder().fileTypeFilter().recursiveScope().customDialog().liveRead().build())
                    .syncEvery(Duration.ofHours(6))
                    .docsAnchor("network-shares")
                    .syncSetting("network-share.sync-interval-seconds")
                    .targetField("uncPath")
                    .build());

    private static final FieldSpec TOKEN = FieldSpec.secret("token", "Personal access token").asRequired()
            .help("A personal access token for this server. Yours alone - it is never shared with other users.");

    private static FieldSpec serverUrl(String placeholder) {
        return FieldSpec.url("baseUrl", "Server URL").asRequired().placeholder(placeholder);
    }

    // Jira and Confluence each sign in two ways, and the way says which product line the server is:
    // a personal access token is Data Center or Server, an email and an API token is Cloud. The
    // token comes first, so a connection saved before Cloud was offered keeps meaning what it did.
    private static final AuthMethod DATA_CENTER_TOKEN = AuthMethod.of(AuthKind.TOKEN,
            "Personal access token (Data Center or Server)", TOKEN);

    private static final AuthMethod CLOUD_API_TOKEN = AuthMethod.of(AuthKind.BASIC, "Email and API token (Cloud)",
            FieldSpec.text(AuthMethod.USERNAME, "Email address").asRequired().placeholder("you@example.com")
                    .help("The email address you sign in to Atlassian with."),
            FieldSpec.secret("token", "API token").asRequired()
                    .help("Create one at id.atlassian.com under Security > API tokens. Yours alone - it is never "
                            + "shared with other users."));

    // An Atlassian Cloud sign-in reaches a SITE, which is Jira and Confluence at once (Confluence under
    // /wiki), so a Cloud connection of either service serves both products' sources. A Data Center or
    // Server token reaches the one server it was issued on, so it serves its own product alone.
    private static FieldSpec atlassianUrl(String placeholder) {
        return serverUrl(placeholder).help("Data Center or Server: the address you open it at. "
                + "Cloud: your site, https://your-site.atlassian.net");
    }

    private static final List<ProtocolDescriptor> PROTOCOLS = List.of(
            ProtocolDescriptor.builder(ConnectionProtocol.JIRA)
                    .displayName("Jira").mark("jira")
                    .auth(DATA_CENTER_TOKEN.servingOnly(ConnectorType.JIRA), CLOUD_API_TOKEN)
                    .baseUrl(atlassianUrl("https://jira.example.com"))
                    .sourceTypes(ConnectorType.JIRA, ConnectorType.CONFLUENCE)
                    .supportsWrite()
                    .docsAnchor("jira")
                    .build(),
            ProtocolDescriptor.builder(ConnectionProtocol.CONFLUENCE)
                    .displayName("Confluence").mark("confluence")
                    .auth(DATA_CENTER_TOKEN.servingOnly(ConnectorType.CONFLUENCE), CLOUD_API_TOKEN)
                    .baseUrl(atlassianUrl("https://confluence.example.com"))
                    .sourceTypes(ConnectorType.CONFLUENCE, ConnectorType.JIRA)
                    .supportsWrite()
                    .docsAnchor("confluence")
                    .build(),
            ProtocolDescriptor.builder(ConnectionProtocol.BITBUCKET)
                    .displayName("Bitbucket").mark("bitbucket")
                    .auth(AuthMethod.of(AuthKind.TOKEN, "HTTP access token", TOKEN))
                    .baseUrl(serverUrl("https://bitbucket.example.com"))
                    .sourceTypes(ConnectorType.BITBUCKET)
                    .supportsWrite()
                    .docsAnchor("bitbucket")
                    .build(),
            ProtocolDescriptor.builder(ConnectionProtocol.SVN)
                    .displayName("SVN").mark("subversion")
                    .auth(AuthMethod.of(AuthKind.BASIC, "User name and password",
                            FieldSpec.text(AuthMethod.USERNAME, "Username").asRequired().placeholder("DOMAIN\\you")
                                    .help("Windows Authentication takes a domain-qualified name (DOMAIN\\you); "
                                            + "Subversion Authentication takes a name from the server's own user list."),
                            FieldSpec.secret("password", "Password").asRequired()))
                    .baseUrl(FieldSpec.url("baseUrl", "Repository root URL").asRequired()
                            .placeholder("https://svn.example.com/svn")
                            .help("The repository root. Each SVN source's path is added to it."))
                    .sourceTypes(ConnectorType.SVN)
                    .docsAnchor("svn")
                    .build(),
            ProtocolDescriptor.builder(ConnectionProtocol.NETWORK_SHARE)
                    .displayName("Network share").mark("network")
                    .auth(AuthMethod.of(AuthKind.BASIC, "Domain account",
                            FieldSpec.text(AuthMethod.USERNAME, "Domain account").asRequired().placeholder("DOMAIN\\you")
                                    .help("Your Windows domain account, as DOMAIN\\you or you@domain. "
                                            + "Only files it can open are indexed."),
                            FieldSpec.secret("password", "Password").asRequired()))
                    .baseUrl(FieldSpec.text("baseUrl", "File server").asRequired().placeholder("fileserver.corp.local")
                            .help("The server your network paths start with. Its folders are read as this account, "
                                    + "and no other server is."))
                    .sourceTypes(ConnectorType.NETWORK_SHARE)
                    .docsAnchor("network-shares")
                    .build(),
            ProtocolDescriptor.builder(ConnectionProtocol.MCP_SERVER)
                    .displayName("MCP server").mark("plug")
                    .auth(AuthMethod.of(AuthKind.TOKEN, "Bearer token", FieldSpec.secret("token", "Bearer token")))
                    .baseUrl(FieldSpec.url("baseUrl", "Server URL"))
                    .customDialog()
                    .docsAnchor("mcp-servers")
                    .build());

    private static final Map<ConnectorType, ConnectorDescriptor> BY_TYPE = TYPES.stream()
            .collect(Collectors.toUnmodifiableMap(ConnectorDescriptor::type, Function.identity()));
    private static final Map<ConnectionProtocol, ProtocolDescriptor> BY_PROTOCOL = PROTOCOLS.stream()
            .collect(Collectors.toUnmodifiableMap(ProtocolDescriptor::protocol, Function.identity()));

    public static List<ConnectorDescriptor> types() {
        return TYPES;
    }

    public static List<ProtocolDescriptor> protocols() {
        return PROTOCOLS;
    }

    /** The descriptor of one of these types, or null for any other. */
    public static ConnectorDescriptor of(ConnectorType type) {
        return BY_TYPE.get(type);
    }

    public static ProtocolDescriptor of(ConnectionProtocol protocol) {
        return BY_PROTOCOL.get(protocol);
    }
}
