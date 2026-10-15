package ai.proxyma.core.tools.svn;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.svn.SvnConnector;
import ai.proxyma.core.connector.svn.SvnUrls;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.tools.ToolConnectorResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared resolution for the SVN browse tools.
 *
 * <p>Same split as JiraToolService: READ permission is enforced here, at the choke point every
 * read tool funnels through, so one added later is guarded by construction rather than by the
 * author remembering.
 *
 * <p>There is no write half. SVN's write verbs commit to a repository, and nothing about browsing
 * needs them - adding one later means adding the guard alongside it, not extending this.
 */
@Service
class SvnToolService {

    private static final String LABEL = "SVN";

    /** Bounds one listing, for the reason BitbucketConnector.listFiles gives: this feeds a model's
     *  context, and a recursive listing of a large repository would fill it. */
    private static final int MAX_ENTRIES = 2000;

    /** Bounds one file read. SVN has no line-range fetch, so `svn cat` would send the whole file -
     *  it is stopped once enough has been read for this many characters. */
    private static final int MAX_CHARS = 200_000;

    private final ToolConnectorResolver resolver;
    private final SvnConnector svnConnector;
    private final McpGuardService mcpGuard;
    private final RestClient http;

    SvnToolService(ToolConnectorResolver resolver, SvnConnector svnConnector,
                   McpGuardService mcpGuard,
                   @Qualifier("externalRestClientBuilder") RestClient.Builder externalRestClientBuilder) {
        this.resolver = resolver;
        this.svnConnector = svnConnector;
        this.mcpGuard = mcpGuard;
        this.http = externalRestClientBuilder.build();
    }

    // PER-SOURCE OVERRIDES DO NOT REACH THE SVN TOOLS, and that is a limitation rather than an
    // omission. Every other connector's tools receive the key registration stored as the
    // connector's NAME - a Jira project, a Confluence space, a Bitbucket PROJECT/repo - so
    // ConnectorEndpointResolver can find the source being talked about. SVN's tools receive a
    // repository PATH relative to the root, while its connector name is a display label the user
    // typed at registration and its URL lives in config. There is no reliable way from one to the
    // other, and a guess would silently authenticate against the wrong server.
    //
    // So these resolve the caller's own root and credential, exactly as before. An SVN source with
    // its own URL is still SYNCED from it (ConnectorEndpointResolver.resolve, off the connector
    // itself, needs no key) - it is only the live tools that cannot see the override.
    Map<String, Object> listFiles(String path, boolean recursive) throws Exception {
        // SVN's own read permission (connector-tools.svn-read), not connector-management. Browsing
        // a repository is a read, and gating it on the permission that also authorizes removing a
        // source asked the user for far more than the action needed - from a settings page that
        // did not render that permission at all.
        mcpGuard.checkSvnRead(SearchContext.getUserId());
        String[] credential = resolver.resolveSvnCredential(LABEL);
        String baseUrl = resolver.resolveBaseUrl(ConnectorType.SVN, LABEL);

        path = relativeToRoot(baseUrl, path);
        List<SvnConnector.SvnTreeEntry> found;
        boolean repositoryIndex = false;
        try {
            // Stopped at one past the bound, so a recursive listing of a large repository is not
            // listed whole into memory to keep its first MAX_ENTRIES.
            found = svnConnector.listFilesAs(
                    baseUrl, normalize(path), credential[0], credential[1], recursive, MAX_ENTRIES);
        } catch (IOException e) {
            if (!isRepositoryIndex(e)) throw e;
            found = repositoriesAt(baseUrl, normalize(path), credential[0], credential[1]);
            if (found.isEmpty()) throw e;
            repositoryIndex = true;
        }

        // `svn list` names each entry relative to the LISTED path, while readSvnFile takes a path
        // relative to the root - so "README" from a listing of trunk/src sent readSvnFile to a
        // README at the root. Prefixed here, so a listed path is always one readSvnFile accepts.
        // Listing a FILE answers with that file's own name, which is already the listed path.
        String listed = normalize(path);
        boolean listedIsFile = found.size() == 1 && "FILE".equals(found.get(0).kind())
                && (listed.equals(found.get(0).path()) || listed.endsWith("/" + found.get(0).path()));
        String prefix = listed.isEmpty() || listedIsFile ? "" : listed + "/";

        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        for (SvnConnector.SvnTreeEntry e : found) {
            if (entries.size() >= MAX_ENTRIES) {
                truncated = true;
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", listedIsFile ? listed : prefix + e.path());
            entry.put("kind", e.kind());
            if (e.size() != null) entry.put("size", e.size());
            if (e.revision() != null) entry.put("revision", e.revision());
            if (e.lastModified() != null) entry.put("lastModified", e.lastModified().toString());
            entries.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", listed);
        result.put("entries", entries);
        result.put("count", entries.size());
        if (truncated) result.put("truncated", true);
        if (repositoryIndex) {
            result.put("note", "This address holds repositories rather than being one. List one by its path.");
        }
        return result;
    }

    // A SERVER ROOT THAT HOLDS REPOSITORIES, since 2026-09-30. Apache's SVNParentPath - how most
    // company SVN servers are set up - answers its own URL with an HTML index of the repositories
    // under it, not with a repository, and `svn list` there fails with "E130003: The XML response
    // contains invalid XML". So listing the root of the server set in Settings failed in the
    // maintainer's self test while every repository below it listed fine. The index is read
    // instead, over the same proxy and trust as every other connector call, and its entries are
    // the repositories - each one a path `svn list` accepts.
    static boolean isRepositoryIndex(IOException e) {
        String m = e.getMessage();
        return m != null && (m.contains("E130003") || m.contains("contains invalid XML"));
    }

    private List<SvnConnector.SvnTreeEntry> repositoriesAt(String baseUrl, String path, String username,
                                                           String password) {
        if (!baseUrl.matches("(?i)^https?://.*")) return List.of();
        String url = baseUrl.replaceAll("/+$", "") + (path.isEmpty() ? "" : "/" + path) + "/";
        String html;
        try {
            html = http.get()
                    .uri(URI.create(url))
                    .headers(h -> {
                        if (username != null && !username.isBlank()) {
                            String pair = username + ":" + (password == null ? "" : password);
                            h.set(HttpHeaders.AUTHORIZATION, "Basic "
                                    + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
                        }
                    })
                    .accept(MediaType.TEXT_HTML, MediaType.TEXT_XML, MediaType.ALL)
                    .retrieve()
                    .body(String.class);
        } catch (Exception unreadable) {
            return List.of();
        }
        List<SvnConnector.SvnTreeEntry> repos = new ArrayList<>();
        for (String name : repositoryNames(html)) {
            repos.add(new SvnConnector.SvnTreeEntry(name, "DIRECTORY", null, null, null));
        }
        return repos;
    }

    private static final java.util.regex.Pattern INDEX_HREF =
            java.util.regex.Pattern.compile("href\\s*=\\s*\"([^\"?#:]+)/\"", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The repository names in an SVNParentPath index - its HTML list, or the XML one a server with
     * SVNIndexXSLT sends - which link each repository as {@code href="name/"}. The parent link, an
     * absolute path and anything with a scheme are not repositories.
     */
    static List<String> repositoryNames(String index) {
        if (index == null) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        java.util.regex.Matcher m = INDEX_HREF.matcher(index);
        while (m.find()) {
            String name = m.group(1).trim();
            if (name.isEmpty() || name.startsWith("/") || name.startsWith(".") || name.contains("/")) continue;
            names.add(java.net.URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8));
        }
        return new ArrayList<>(names);
    }

    Map<String, Object> readFile(String filePath) throws Exception {
        // Same read permission as listFiles above.
        mcpGuard.checkSvnRead(SearchContext.getUserId());
        String[] credential = resolver.resolveSvnCredential(LABEL);
        String baseUrl = resolver.resolveBaseUrl(ConnectorType.SVN, LABEL);

        filePath = relativeToRoot(baseUrl, filePath);
        // Read no further than the bound can use - four bytes a character at most - and svn is
        // stopped there, rather than the whole file read into memory and cut afterwards.
        SvnConnector.SvnText read = svnConnector.catFileAs(baseUrl, "", normalize(filePath),
                credential[0], credential[1], MAX_CHARS * 4);
        String content = read.text();
        boolean truncated = read.truncated() || (content != null && content.length() > MAX_CHARS);
        if (content != null && content.length() > MAX_CHARS) content = content.substring(0, MAX_CHARS);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", normalize(filePath));
        result.put("content", content == null ? "" : content);
        if (truncated) result.put("truncated", true);
        return result;
    }

    // A full URL is accepted when it lies under the SVN server set in Settings, and turned into the
    // path below it. Users paste the URL they have, and the tools used to take only a relative path
    // - so a model asked to browse one said the repository had to be REGISTERED first, which is
    // not true (feedback, 2026-09-29). A URL on any OTHER server is refused rather than followed:
    // the call authenticates with the user's saved SVN credential, and following a URL the model
    // was handed would send that credential wherever the URL pointed.
    //
    // The rule is SvnUrls', shared with registration and with the connector's own check before every
    // command it runs with a credential - one rule, so the three cannot disagree.
    static String relativeToRoot(String baseUrl, String path) {
        if (path == null) return "";
        String candidate = path.trim();
        if (!SvnUrls.isUrl(candidate)) return candidate;
        String relative = SvnUrls.pathUnder(baseUrl, candidate);
        if (relative != null) return relative;
        throw new IllegalArgumentException("That URL is not on the SVN server set in Settings ("
                + SvnUrls.canonical(baseUrl) + "). The live SVN tools reach only that server, with the user's "
                + "saved SVN credential. If the user means another server, tell them to add a connection to it "
                + "in Settings > Connections and switch to it with useServer.");
    }

    // The base URL is the server root and a tool's path is relative to it, so a leading slash would
    // produce a double one. Trailing slashes go too: `svn list` accepts them but they make the
    // echoed path in the result disagree with what the caller passed.
    private static String normalize(String path) {
        if (path == null) return "";
        String p = path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
