package ai.proxyma.core.connector.svn;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.FailedSyncIterator;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One SVN source, as its owner - see {@link SvnConnector}. The repository URL, the connection's root,
 * the credential and the scope are this session's own and fixed when it opens: every command it runs
 * is handed them, so two repositories sync side by side and a fetch never reads another source's.
 *
 * <p>A full listing every pass ({@code svn list --depth infinity}): the listing carries each file's
 * last commit and date, which is what tells a changed file from an unchanged one, and only a full
 * listing can show that a file went away.
 */
final class SvnSession implements ConnectorSession {

    private static final Logger log = LoggerFactory.getLogger(SvnSession.class);

    private final SvnConnector svn;
    private final String svnUrl;
    /**
     * The server root of the source's SVN connection. A credential is only ever sent to a URL that
     * lies under it - see {@link #requireOnRoot}.
     */
    private final String rootUrl;
    private final String username;
    private final String password;
    /** The source's file types, else the owner's setting; null means the built-in list. */
    private final Set<String> extensions;
    /** Whether the listing descends into subfolders. */
    private final boolean recursive;

    SvnSession(SvnConnector svn, String svnUrl, String rootUrl, String username, String password,
               Set<String> extensions, boolean recursive) {
        this.svn = svn;
        this.svnUrl = svnUrl;
        this.rootUrl = rootUrl;
        this.username = username;
        this.password = password;
        this.extensions = extensions;
        this.recursive = recursive;
    }

    /**
     * The registration probe: one `svn info` against the URL just typed, with the source's
     * credential (spec 04 section 5.5).
     *
     * <p>`info`, not a listing - registration only needs to know the URL is a repository path this
     * account can read, and `list --depth infinity` on a large repository is the slowest call this
     * type makes. Never throws: the detail goes straight to the person who typed the credential,
     * so svn's own "E170013 Unable to connect" is translated into what they should do about it.
     */
    @Override
    public AccessCheck verifyAccess() {
        String url = this.svnUrl;
        if (url == null || url.isBlank()) {
            return AccessCheck.failed("No SVN repository URL was given.");
        }
        try {
            requireOnRoot(url);
            svn.probeAs(url, username, password);
            return AccessCheck.verified();
        } catch (Exception e) {
            return AccessCheck.failed(SvnConnector.describeFailure(e, url, hasCredential()));
        }
    }

    // A LISTING THAT FAILS IS AN ERROR, NOT AN EMPTY REPOSITORY, since 2026-09-14. Both branches below
    // returned an empty iterator, which SyncService cannot tell from a repository holding nothing, so
    // a wrong URL, a 401, a missing svn CLI or a 120-second timeout all showed as "0 items, ACTIVE"
    // with the real svn message only in the backend log. A failed listing carries that message to
    // RegisteredConnector.lastSyncError, and an incomplete sync also skips the purge.
    @Override
    public Listing list(ListRequest request) {
        if (svnUrl == null || svnUrl.isBlank()) {
            log.warn("[SVN] list: not configured");
            return Listings.of(new FailedSyncIterator("No SVN repository URL is configured for this source"));
        }
        try {
            requireOnRoot(svnUrl);
            List<SvnConnector.SvnFileEntry> entries = svn.listFiles(svnUrl, "", extensions, recursive, username, password);
            List<ItemMetadata> items = new ArrayList<>(entries.size());
            for (SvnConnector.SvnFileEntry entry : entries) {
                ItemMetadata meta = new ItemMetadata();
                meta.setId(entry.filePath());
                meta.setTitle(entry.filePath());
                meta.setSourceType(ConnectorType.SVN);
                meta.setContentType(svn.deriveContentType(entry.filePath()));
                // The citation link. Without it every indexed SVN chunk cited a bare relative path,
                // which is not clickable and does not say which repository it came from - the one
                // piece of an indexed document a reader acts on.
                meta.putExtra("url", SvnConnector.fileUrl(svnUrl, "", entry.filePath()));
                // Who last committed the file - the listing states it, and the knowledge graph
                // records it as "last changed by" (GraphExtractionOrchestrator.sourceFacts).
                if (entry.author() != null && !entry.author().isBlank()) {
                    meta.putExtra(ItemMetadata.AUTHOR, entry.author());
                }
                meta.setLastModified(entry.lastModified() != null
                        ? entry.lastModified().atZone(ZoneOffset.UTC).toInstant()
                        : Instant.now());
                items.add(meta);
            }
            return Listings.of(items);
        } catch (IOException e) {
            log.error("[SVN] Failed to list files at {}: {}", svnUrl, e.getMessage());
            return Listings.of(new FailedSyncIterator(SvnConnector.describeFailure(e, svnUrl, hasCredential())));
        }
    }

    @Override
    public InputStream fetch(ItemMetadata item) {
        if (item == null) {
            throw new IllegalArgumentException("ItemMetadata must not be null");
        }
        SvnConnector.SvnTarget target = SvnConnector.resolveTarget(item, svnUrl);
        try {
            requireOnRoot(SvnConnector.fileUrl(target.repoUrl(), target.repoPath(), target.filePath()));
            byte[] bytes = svn.catFileBinary(target.repoUrl(), target.repoPath(), target.filePath(), username, password);
            return new ByteArrayInputStream(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch SVN file content: " + e.getMessage(), e);
        }
    }

    private boolean hasCredential() {
        return username != null && !username.isBlank();
    }

    /**
     * Refuses to send the source's credential to a URL that is not on its connection's server.
     *
     * <p>A source used to be able to name any host: a repository URL registered by the agent from a
     * pasted link, or a row edited in the database, and its probe and every sync then sent the
     * connection's password (Basic, or an NTLM exchange through Negotiate) there. Registration now
     * refuses such a URL; this is the check under it, before every command that carries a
     * credential, so a source registered before that rule is stopped too. Anonymous commands carry
     * nothing to leak and are not affected.
     */
    void requireOnRoot(String targetUrl) throws IOException {
        if (username == null || username.isBlank() || password == null || password.isBlank()) return;
        if (SvnUrls.isUnder(rootUrl, targetUrl)) return;
        throw new IOException("This source's repository URL is not on the server of its SVN connection"
                + (rootUrl == null || rootUrl.isBlank() ? "" : " (" + SvnUrls.canonical(rootUrl) + ")")
                + ", and the saved SVN credential is only sent there. Remove the source and register the "
                + "repository through a connection to its own server.");
    }
}
