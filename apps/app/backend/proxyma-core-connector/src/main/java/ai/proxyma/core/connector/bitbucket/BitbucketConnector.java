package ai.proxyma.core.connector.bitbucket;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.spi.AtlassianServer;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.FailedSyncIterator;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.RestPassthrough;
import ai.proxyma.core.connector.SyncErrorAware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class BitbucketConnector implements ConnectorPlugin {

    private static final Logger log = LoggerFactory.getLogger(BitbucketConnector.class);
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ONE SOURCE'S, OR NONE. The Spring bean - the plugin, and the client of every live tool, which
    // passes its caller's token explicitly - holds none of these. A session gets an instance of its
    // own (forSource) holding its repository and credential, so the sync-path methods below that
    // read them can never read another source's. Until the move to sessions they were written by
    // auth() on the one shared bean, and a source whose token could not be resolved once synced with
    // the previous source's credential against the previous source's repository.
    private final String authBaseUrl;
    private final String authAccessToken;
    private final String authProjectKey;
    private final String authRepoSlug;

    // @Autowired because there are two constructors - Spring only picks one by itself when a class
    // declares exactly one.
    @Autowired
    public BitbucketConnector(
            @Qualifier("externalRestClientBuilder") RestClient.Builder restClientBuilder) {
        // A clone: the builder is shared, and the guard is this client's alone - see DotSegmentGuard.
        this(restClientBuilder.clone().requestInterceptor(new ai.proxyma.core.connector.DotSegmentGuard()).build(),
                null, null, null, null);
    }

    private BitbucketConnector(RestClient restClient, String baseUrl, String token, String projectKey,
                               String repoSlug) {
        this.restClient = restClient;
        // Kept apart, so verifyAccess() can say WHICH piece is missing: a source with a server but no
        // token used to arrive there with the server blanked too, and was told to configure a URL it
        // already had. The server's root, whatever page of it the address was pasted from.
        this.authBaseUrl = (baseUrl == null || baseUrl.isBlank()) ? null : AtlassianServer.bitbucketRoot(baseUrl);
        this.authAccessToken = (token == null || token.isBlank()) ? null : token;
        this.authProjectKey = projectKey;
        this.authRepoSlug = repoSlug;
    }

    /** One repository's view of this client, with its server and credential - a session's. */
    BitbucketConnector forSource(String baseUrl, String token, String projectKey, String repoSlug) {
        return new BitbucketConnector(restClient, baseUrl, token, projectKey, repoSlug);
    }

    // - The Bitbucket Data Center source type --------------------------------------

    @Override
    public ConnectorDescriptor descriptor() {
        return CoreDescriptors.of(ConnectorType.BITBUCKET);
    }

    /**
     * A session over one repository, as its OWNER: the connection's server and credential - a token,
     * or user:password for a Basic one - and the project and repository from the source's fields. A
     * row stored before its fields were JSON named them only as {@code PROJ/repo}, its name.
     */
    @Override
    public ConnectorSession open(SourceContext context) {
        String projectKey = context.get("projectKey");
        String repoSlug = context.get("repoSlug");
        String name = context.name();
        int slash = name == null ? -1 : name.indexOf('/');
        if ((projectKey == null || repoSlug == null) && slash > 0) {
            projectKey = name.substring(0, slash);
            repoSlug = name.substring(slash + 1);
        }
        ResolvedCredentials connection = context.connection();
        return new BitbucketSession(forSource(connection.baseUrl(), credential(connection), projectKey, repoSlug));
    }

    // The credential in the one-string shape every call here sends: the token, or user:password.
    private static String credential(ResolvedCredentials c) {
        if (c.authKind() == AuthKind.BASIC) {
            String password = c.secret("password") != null ? c.secret("password") : c.soleSecret();
            if (c.username() == null || c.username().isBlank() || password == null || password.isBlank()) return null;
            return c.username() + ":" + password;
        }
        String token = c.secret("token");
        return token != null ? token : c.soleSecret();
    }

    /** The repository's page on its server. */
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String projectKey = config.get("projectKey");
        String repoSlug = config.get("repoSlug");
        if (serverUrl == null || serverUrl.isBlank() || projectKey == null || repoSlug == null) return null;
        return AtlassianServer.bitbucketRoot(serverUrl) + "/projects/" + projectKey + "/repos/" + repoSlug;
    }

    // NOT CONFIGURED IS AN ERROR, not an empty repository - see FailedSyncIterator. A source with no
    // resolvable credential ends up here, and it says so instead of reporting "0 items".
    private static final String NOT_CONFIGURED =
            "Bitbucket is not configured for this source - check the server URL and personal access token in Settings > Connections";

    // PULL REQUESTS AND COMMITS, and nothing else. Branch, tag and repository items were indexed
    // between 2026-09-14 and 2026-09-18; they are out again because they are not what this connector
    // is for - a ref is a name and a pointer, it carries no text anyone would search for, and the
    // live tools (listBitbucketBranches, listBitbucketTags, the diff tools) read all three on demand,
    // which is where that question belongs. Source FILES are not indexed here either: a repository's
    // documentation reaches search through a file-based source.
    Iterator<ItemMetadata> sync() {
        if (authBaseUrl == null || authBaseUrl.isBlank() ||
                authAccessToken == null || authAccessToken.isBlank() ||
                authProjectKey == null || authProjectKey.isBlank() ||
                authRepoSlug == null || authRepoSlug.isBlank()) {
            log.warn("BitbucketConnector.sync(): not configured");
            return new FailedSyncIterator(NOT_CONFIGURED);
        }
        // PRs first, then commit history walked across every branch (see BitbucketCommitIterator) -
        // order doesn't matter for indexing, this just keeps PR enumeration (cheap, already
        // working) unaffected by however long the commit walk takes.
        return new ConcatIterator<>(List.of(
                new BitbucketPrIterator(authBaseUrl, authProjectKey, authRepoSlug),
                new BitbucketCommitIterator(authBaseUrl, authProjectKey, authRepoSlug, null)));
    }

    // WIDENED BY 15 MINUTES, and compared as an INSTANT. Both halves were wrong until 2026-09-18:
    // the cutoff went in exactly as SyncService handed it over, and every comparison ran through a
    // system-zone LocalDateTime. Epoch milliseconds - which is what Bitbucket sends and what
    // `since` already is - need no time zone at all, and going through one made the cutoff wrong by
    // the UTC offset on any machine not set to UTC, and ambiguous for an hour at a DST change.
    // The overlap is for the remaining gap: this clock and Bitbucket's differ, and a PR updated
    // while the previous sync was running has an `updatedDate` before the cutoff that sync recorded.
    // Re-reading a handful of unchanged PRs is free (SyncService skips an unchanged item without a
    // write); missing an edit costs a day, because nothing else looks again until the daily full sync.
    private static final Duration INCREMENTAL_OVERLAP = Duration.ofMinutes(15);

    Iterator<ItemMetadata> syncSince(Instant since) {
        if (authBaseUrl == null || authBaseUrl.isBlank() ||
                authAccessToken == null || authAccessToken.isBlank() ||
                authProjectKey == null || authProjectKey.isBlank() ||
                authRepoSlug == null || authRepoSlug.isBlank()) {
            log.warn("BitbucketConnector.syncSince(): not configured");
            return new FailedSyncIterator(NOT_CONFIGURED);
        }
        Instant cutoff = since.minus(INCREMENTAL_OVERLAP);
        return new ConcatIterator<>(List.of(
                new BitbucketModifiedPrIterator(authBaseUrl, authProjectKey, authRepoSlug, cutoff),
                new BitbucketCommitIterator(authBaseUrl, authProjectKey, authRepoSlug, cutoff)));
    }

    // The no-token wording is the registration one on purpose: whoever reads it is standing in the
    // register dialog, and "add a token, then register again" is the whole of what they must do.
    private static final String NO_TOKEN =
            "No Bitbucket access token is configured. Add one in Settings > Connections > Bitbucket, "
            + "then register the repository again.";
    private static final String BAD_NAME =
            "The repository must be given as <projectKey>/<repoSlug>.";
    // The same sentence as the connection's Test, so the two share one translation.
    private static final String PAGE_NOT_API =
            "The server answered with a page instead of its API. Check the URL is the server's own address.";

    // 401 and 403 are two different problems with two different fixes, and used to share one
    // sentence ("authentication failed. Check access token.") - which sent the owner of a perfectly
    // good token off to replace it when the real answer was a permission on the repository. A 401
    // is the token itself: wrong, expired or revoked. A 403 is Bitbucket knowing exactly who is
    // asking and refusing that account this repository.
    private static final String TOKEN_REJECTED =
            "Bitbucket did not accept the access token - it may be wrong, expired or revoked. "
            + "Check it in Settings > Connections > Bitbucket.";

    // Null parts for the call sites that only hold a URL; "this repository" still says WHERE the
    // permission is missing, which is the half of the sentence the user needs.
    private static String noAccess(String projectKey, String repoSlug) {
        String repo = projectKey != null && repoSlug != null
                ? "repository " + projectKey + "/" + repoSlug : "this repository";
        return "Bitbucket accepted the access token, but its account has no permission on " + repo
                + " - ask a Bitbucket administrator to grant it access.";
    }

    // A repository-scoped listing that 404s means the repository (or its project) is not there -
    // renamed, deleted, or misspelled at registration. Only used where that is the ONLY thing a 404
    // can mean: a commit listing also 404s for a branch deleted mid-sync, and saying "repository
    // not found" for that would be wrong.
    private static String repoNotFound(String baseUrl, String projectKey, String repoSlug) {
        return "Repository " + projectKey + "/" + repoSlug + " was not found on " + baseUrl
                + " - check the name, and that it has not been renamed or deleted.";
    }

    /**
     * One authenticated GET of the repository itself, so a wrong token or a misspelled slug is
     * refused while the person who typed it is still looking at the form.
     *
     * <p>Reads this instance's own repository and credential - a session's (forSource). Never
     * throws: {@code fetchRepoWith} already phrases each answer the way this has to report it
     * (token rejected, no permission on the repository, not found, "Cannot reach Bitbucket at ..."), so
     * the failure detail is its message rather than a second wording that would drift from it.
     */
    AccessCheck verifyAccess() {
        if (authBaseUrl == null || authBaseUrl.isBlank()) {
            return AccessCheck.failed("No Bitbucket server URL is configured. Add one in "
                    + "Settings > Connections > Bitbucket, then register the repository again.");
        }
        if (authAccessToken == null || authAccessToken.isBlank()) {
            return AccessCheck.failed(NO_TOKEN);
        }
        // A source's fields are its project and repository - and an old row's name, "<projectKey>/
        // <repoSlug>", is split by open() - so a name with no slash arrives here as a missing
        // repoSlug, and saying "not found" for it would send the user looking at the server.
        if (authProjectKey == null || authProjectKey.isBlank()
                || authRepoSlug == null || authRepoSlug.isBlank()) {
            return AccessCheck.failed(BAD_NAME);
        }
        try {
            fetchRepoWith(authBaseUrl, authAccessToken, authProjectKey, authRepoSlug);
            return AccessCheck.verified();
        } catch (Exception e) {
            String detail = e.getMessage();
            return AccessCheck.failed(detail != null && !detail.isBlank()
                    ? detail
                    : "Bitbucket refused the check for " + authProjectKey + "/" + authRepoSlug + ".");
        }
    }

    // Sequential chain, no external dependency needed for something this small. Each iterator is
    // only touched once the one before it is exhausted, so BitbucketCommitIterator's own
    // (potentially slow, multi-branch) first fetchNextBatch() call never delays PR enumeration.
    //
    // SyncErrorAware FORWARDS EVERY PART, since 2026-09-14 - SyncService only asks the iterator it was
    // handed, so a wrapper that does not pass the question through hides every error inside it.
    private static final class ConcatIterator<T> implements Iterator<T>, SyncErrorAware {
        private final List<Iterator<T>> parts;
        private int index = 0;

        ConcatIterator(List<Iterator<T>> parts) {
            this.parts = parts;
        }

        @Override
        public boolean hasNext() {
            while (index < parts.size()) {
                if (parts.get(index).hasNext()) return true;
                index++;
            }
            return false;
        }

        @Override
        public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            return parts.get(index).next();
        }

        @Override
        public boolean hasErrored() {
            return parts.stream().anyMatch(ConcatIterator::errored);
        }

        @Override
        public String getErrorMessage() {
            for (Iterator<T> part : parts) {
                if (errored(part)) return ((SyncErrorAware) part).getErrorMessage();
            }
            return null;
        }

        private static boolean errored(Iterator<?> it) {
            return it instanceof SyncErrorAware aware && aware.hasErrored();
        }
    }

    // The error state every paginating iterator below records when a fetch throws, instead of stopping
    // as if the data had run out. A 401 from a wrong token used to end pagination silently here, and
    // the source showed "0 items, ACTIVE". Only the FIRST error is kept - it is the cause; later ones
    // are usually the same failure again.
    private abstract static class ErrorTrackingIterator implements Iterator<ItemMetadata>, SyncErrorAware {
        private String errorMessage;

        protected void recordError(Exception e) {
            if (errorMessage == null) errorMessage = SyncErrorAware.summarize(e);
        }

        @Override public boolean hasErrored() { return errorMessage != null; }
        @Override public String getErrorMessage() { return errorMessage; }
    }

    private class BitbucketPrIterator extends ErrorTrackingIterator {
        private final String baseUrl;
        private final String projectKey;
        private final String repoSlug;
        private final List<ItemMetadata> buffer = new ArrayList<>();
        private int bufferIndex = 0;
        private int start = 0;
        private boolean hasMore = true;
        private static final int PAGE_SIZE = 100;

        BitbucketPrIterator(String baseUrl, String projectKey, String repoSlug) {
            this.baseUrl = baseUrl;
            this.projectKey = projectKey;
            this.repoSlug = repoSlug;
        }

        @Override
        public boolean hasNext() {
            if (bufferIndex < buffer.size()) return true;
            if (!hasMore) return false;
            fetchNextBatch();
            return bufferIndex < buffer.size();
        }

        @Override
        public ItemMetadata next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            return buffer.get(bufferIndex++);
        }

        private void fetchNextBatch() {
            try {
                PrBatchResult result = fetchPrsBatch(baseUrl, projectKey, repoSlug, start, PAGE_SIZE);
                buffer.clear();
                bufferIndex = 0;
                for (BitbucketPrDto pr : result.prs) {
                    buffer.add(toItemMetadata(pr, baseUrl, projectKey, repoSlug));
                }
                start = result.nextStart;
                hasMore = result.hasMore;
            } catch (Exception e) {
                log.error("[Bitbucket] Failed to fetch PR batch at start={}: {}", start, e.getMessage());
                recordError(e);
                hasMore = false;
            }
        }
    }

    // Incremental variant backed by fetchModifiedPrsBatch(). Must NEVER be used to drive a purge -
    // see BitbucketSession.list.
    private class BitbucketModifiedPrIterator extends ErrorTrackingIterator {
        private final String baseUrl;
        private final String projectKey;
        private final String repoSlug;
        private final Instant since;
        private final List<ItemMetadata> buffer = new ArrayList<>();
        private int bufferIndex = 0;
        private int start = 0;
        private boolean hasMore = true;
        private static final int PAGE_SIZE = 100;

        BitbucketModifiedPrIterator(String baseUrl, String projectKey, String repoSlug, Instant since) {
            this.baseUrl = baseUrl;
            this.projectKey = projectKey;
            this.repoSlug = repoSlug;
            this.since = since;
        }

        @Override
        public boolean hasNext() {
            if (bufferIndex < buffer.size()) return true;
            if (!hasMore) return false;
            fetchNextBatch();
            return bufferIndex < buffer.size();
        }

        @Override
        public ItemMetadata next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            return buffer.get(bufferIndex++);
        }

        private void fetchNextBatch() {
            try {
                PrBatchResult result = fetchModifiedPrsBatch(baseUrl, projectKey, repoSlug, since, start, PAGE_SIZE);
                buffer.clear();
                bufferIndex = 0;
                for (BitbucketPrDto pr : result.prs) {
                    buffer.add(toItemMetadata(pr, baseUrl, projectKey, repoSlug));
                }
                start = result.nextStart;
                hasMore = result.hasMore;
            } catch (Exception e) {
                log.error("[Bitbucket] Failed to fetch modified PR batch at start={}: {}", start, e.getMessage());
                recordError(e);
                hasMore = false;
            }
        }
    }

    // Walks every branch's commit history (not just the default branch), deduplicating by commit
    // id (a commit's hash is a globally unique, branch-independent identifier - the same commit
    // frequently appears in more than one branch's history, e.g. an unmerged feature branch off
    // main) so it is only ever indexed once regardless of how many branches contain it. Branches
    // are listed once up front (cheap, one paginated call), then walked one at a time; within each
    // branch, commits come back newest-first, so `since` (non-null only for syncSince()) lets each
    // branch's walk stop as soon as it reaches a commit older than the cutoff, mirroring
    // fetchModifiedPrsBatch()'s exact same early-stop shape for PRs. Like
    // BitbucketModifiedPrIterator, the incremental form must never drive a purge (only ever adds
    // newly-reachable commits, never observes the full current set) - see
    // BitbucketSession.list.
    private class BitbucketCommitIterator extends ErrorTrackingIterator {
        private final String baseUrl;
        private final String projectKey;
        private final String repoSlug;
        private final Instant since;
        // The same commit is reachable from many branches and is indexed once: a commit is new
        // exactly when this set does not already hold its hash.
        private final Set<String> seen = new HashSet<>();
        private final List<ItemMetadata> buffer = new ArrayList<>();
        private int bufferIndex = 0;
        private List<String> branchIds;
        private int branchIndex = 0;
        private int commitStart = 0;
        private boolean done = false;
        private static final int PAGE_SIZE = 100;

        BitbucketCommitIterator(String baseUrl, String projectKey, String repoSlug, Instant since) {
            this.baseUrl = baseUrl;
            this.projectKey = projectKey;
            this.repoSlug = repoSlug;
            this.since = since;
        }

        @Override
        public boolean hasNext() {
            if (bufferIndex < buffer.size()) return true;
            if (done) return false;
            fetchNextBatch();
            return bufferIndex < buffer.size();
        }

        @Override
        public ItemMetadata next() {
            if (!hasNext()) throw new NoSuchElementException();
            return buffer.get(bufferIndex++);
        }

        // Refills buffer with the next non-empty batch of newly-seen commits, advancing across
        // branches as each one is exhausted (or, for an incremental sync, once its newest commits
        // are already older than `since`). Loops rather than returning after one API call so a
        // branch whose every commit was already seen under an earlier branch doesn't produce a
        // spurious empty batch that looks like exhaustion to hasNext().
        private void fetchNextBatch() {
            try {
                if (branchIds == null) {
                    branchIds = fetchAllBranchIds(baseUrl, projectKey, repoSlug);
                }
                buffer.clear();
                bufferIndex = 0;
                while (buffer.isEmpty() && branchIndex < branchIds.size()) {
                    String branch = branchIds.get(branchIndex);
                    CommitBatchResult result;
                    try {
                        result = fetchCommitsBatch(baseUrl, projectKey, repoSlug, branch,
                                since, commitStart, PAGE_SIZE);
                    } catch (BranchGoneException gone) {
                        // Deleted after the branches were listed: nothing of it is left to list,
                        // and the rest of the branches still are. The listing stays complete.
                        log.info("[Bitbucket] {} - skipping it", gone.getMessage());
                        branchIndex++;
                        commitStart = 0;
                        continue;
                    }
                    for (BitbucketCommitDto c : result.commits) {
                        if (seen.add(c.id)) {
                            buffer.add(toItemMetadata(c, baseUrl, projectKey, repoSlug));
                        }
                    }
                    if (result.hasMore) {
                        commitStart = result.nextStart;
                    } else {
                        branchIndex++;
                        commitStart = 0;
                    }
                }
                if (branchIndex >= branchIds.size()) done = true;
            } catch (Exception e) {
                log.error("[Bitbucket] Failed to fetch commit batch (branch {}/{}): {}",
                        branchIndex, branchIds != null ? branchIds.size() : 0, e.getMessage());
                recordError(e);
                done = true;
            }
        }
    }

    // LEGACY, and read-only: sync() no longer emits this id, nor BRANCH-/TAG-. Installs that ran
    // 0.9.x still have those rows in tracked_documents, and the drain loop can hand one back here
    // before the next full sync purges it - so getContent() still renders them rather than failing
    // an item that was legitimately indexed by the version that wrote it.
    private static final String REPO_ITEM_ID = "REPO";

    private ItemMetadata toItemMetadata(BitbucketCommitDto commit, String baseUrl, String projectKey, String repoSlug) {
        ItemMetadata meta = new ItemMetadata();
        meta.setId("COMMIT-" + commit.id);
        meta.setTitle(commitTitle(commit));
        meta.setSourceType(ConnectorType.BITBUCKET);
        meta.setContentType("text/markdown");
        meta.putExtra("baseUrl", baseUrl);
        meta.putExtra("projectKey", projectKey);
        meta.putExtra("repoSlug", repoSlug);
        meta.putExtra("commitId", commit.id);
        meta.putExtra("url", commit.commitUrl);
        meta.setLastModified(commit.authorTimestampMs > 0
                ? Instant.ofEpochMilli(commit.authorTimestampMs) : Instant.now());
        return meta;
    }

    // First line of the commit message, capped - mirrors the conventional "git log --oneline"
    // summary rather than dumping a possibly-multi-paragraph message into the item title.
    private static String commitTitle(BitbucketCommitDto commit) {
        String msg = commit.message != null ? commit.message : "";
        int newline = msg.indexOf('\n');
        String firstLine = (newline >= 0 ? msg.substring(0, newline) : msg).trim();
        if (firstLine.isBlank()) firstLine = commit.displayId != null ? commit.displayId : commit.id;
        return firstLine.length() > 200 ? firstLine.substring(0, 200) : firstLine;
    }

    private ItemMetadata toItemMetadata(BitbucketPrDto pr, String baseUrl, String projectKey, String repoSlug) {
        ItemMetadata meta = new ItemMetadata();
        meta.setId("PR-" + pr.id);
        meta.setTitle(pr.title);
        meta.setSourceType(ConnectorType.BITBUCKET);
        meta.setContentType("text/markdown");
        meta.putExtra("baseUrl", baseUrl);
        meta.putExtra("projectKey", projectKey);
        meta.putExtra("repoSlug", repoSlug);
        meta.putExtra("prId", String.valueOf(pr.id));
        meta.putExtra("url", pr.prUrl);
        // STRAIGHT FROM THE EPOCH MILLISECONDS Bitbucket sent, not from the LocalDateTime rendered
        // for display beside it: that one is in this machine's zone, and turning it back into an
        // instant is ambiguous for the hour a DST change repeats - which would move an item's
        // lastModified without the PR having changed, or hide a change that did happen.
        meta.setLastModified(pr.updatedTimestampMs > 0
                ? Instant.ofEpochMilli(pr.updatedTimestampMs) : Instant.now());
        return meta;
    }

    InputStream getContent(ItemMetadata metadata) {
        String id = metadata.getId();
        if (id != null && id.startsWith("COMMIT-")) {
            return getCommitContent(metadata, id.substring("COMMIT-".length()));
        }
        if (id != null && (id.startsWith("BRANCH-") || id.startsWith("TAG-") || id.equals(REPO_ITEM_ID))) {
            return getRefContent(metadata, id);
        }
        return getPrContent(metadata);
    }

    // Branches, tags and the repository. Coordinates come from extras or, for an item rebuilt from a
    // TrackedDocument row, from the session's own source - the same fallback getPrContent explains. A ref that no longer
    // exists reads as empty, like a vanished PR; the next full sync purges it.
    private InputStream getRefContent(ItemMetadata metadata, String id) {
        String baseUrl = firstNonBlank(metadata.getExtra("baseUrl"), this.authBaseUrl);
        String projectKey = firstNonBlank(metadata.getExtra("projectKey"), this.authProjectKey);
        String repoSlug = firstNonBlank(metadata.getExtra("repoSlug"), this.authRepoSlug);
        if (baseUrl == null || projectKey == null || repoSlug == null) {
            throw new IllegalArgumentException("BitbucketConnector.getContent() requires baseUrl, projectKey and "
                    + "repoSlug (from extraProperties or a prior auth() call). Got id=" + id);
        }
        String markdown;
        if (id.equals(REPO_ITEM_ID)) {
            markdown = repoToMarkdown(baseUrl, projectKey, repoSlug);
        } else if (id.startsWith("BRANCH-")) {
            markdown = branchToMarkdown(baseUrl, projectKey, repoSlug, id.substring("BRANCH-".length()));
        } else {
            markdown = tagToMarkdown(baseUrl, projectKey, repoSlug, id.substring("TAG-".length()));
        }
        return new ByteArrayInputStream((markdown != null ? markdown : "").getBytes(StandardCharsets.UTF_8));
    }

    String branchToMarkdown(String baseUrl, String projectKey, String repoSlug, String name) {
        JsonNode branch = findRef(baseUrl, projectKey, repoSlug, "branches?details=true", name);
        if (branch == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("# Branch ").append(name).append("\n\n");
        sb.append("## Branch Details\n| Field | Value |\n|---|---|\n");
        sb.append("| Repository | ").append(projectKey).append('/').append(repoSlug).append(" |\n");
        sb.append("| Name | ").append(name).append(" |\n");
        sb.append("| Default branch | ").append(branch.path("isDefault").asBoolean(false) ? "Yes" : "No").append(" |\n");
        sb.append("| URL | ").append(joinUrl(baseUrl, String.format("projects/%s/repos/%s/browse?at=%s",
                projectKey, repoSlug, encodePathSegment("refs/heads/" + name)))).append(" |\n");
        appendPointedCommit(sb, "Latest Commit", baseUrl, projectKey, repoSlug, branch.path("latestCommit").asText(""));
        appendJiraKeys(sb, extractJiraIssueKeys(name), "the branch name");
        return sb.toString().trim();
    }

    String tagToMarkdown(String baseUrl, String projectKey, String repoSlug, String name) {
        JsonNode tag = findRef(baseUrl, projectKey, repoSlug, "tags?", name);
        if (tag == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("# Tag ").append(name).append("\n\n");
        sb.append("## Tag Details\n| Field | Value |\n|---|---|\n");
        sb.append("| Repository | ").append(projectKey).append('/').append(repoSlug).append(" |\n");
        sb.append("| Name | ").append(name).append(" |\n");
        sb.append("| URL | ").append(joinUrl(baseUrl, String.format("projects/%s/repos/%s/browse?at=%s",
                projectKey, repoSlug, encodePathSegment("refs/tags/" + name)))).append(" |\n");
        appendPointedCommit(sb, "Tagged Commit", baseUrl, projectKey, repoSlug, tag.path("latestCommit").asText(""));
        return sb.toString().trim();
    }

    String repoToMarkdown(String baseUrl, String projectKey, String repoSlug) {
        JsonNode repo = getJsonAt(repoApi(baseUrl, projectKey, repoSlug, ""), "read repository", true);
        if (repo == null) return null;
        StringBuilder sb = new StringBuilder();
        String name = repo.path("name").asText(repoSlug);
        sb.append("# Repository ").append(projectKey).append('/').append(repoSlug);
        if (!name.equals(repoSlug)) sb.append(" - ").append(name);
        sb.append("\n\n## Repository Details\n| Field | Value |\n|---|---|\n");
        sb.append("| Name | ").append(name).append(" |\n");
        sb.append("| Slug | ").append(repo.path("slug").asText(repoSlug)).append(" |\n");
        JsonNode project = repo.path("project");
        sb.append("| Project | ").append(project.path("name").asText(projectKey))
                .append(" (").append(project.path("key").asText(projectKey)).append(") |\n");
        String defaultBranch = defaultBranchName(baseUrl, projectKey, repoSlug);
        if (defaultBranch != null) sb.append("| Default branch | ").append(defaultBranch).append(" |\n");
        sb.append("| Visibility | ").append(repo.path("public").asBoolean(false) ? "Public" : "Private").append(" |\n");
        sb.append("| Forkable | ").append(repo.path("forkable").asBoolean(false) ? "Yes" : "No").append(" |\n");
        JsonNode origin = repo.path("origin");
        if (origin.isObject()) {
            sb.append("| Fork of | ").append(origin.path("project").path("key").asText("?"))
                    .append('/').append(origin.path("slug").asText("?")).append(" |\n");
        }
        if (repo.hasNonNull("state")) sb.append("| State | ").append(repo.path("state").asText()).append(" |\n");
        if (repo.hasNonNull("scmId")) sb.append("| SCM | ").append(repo.path("scmId").asText()).append(" |\n");
        sb.append("| URL | ").append(firstLink(repo.path("links").path("self"),
                joinUrl(baseUrl, String.format("projects/%s/repos/%s/browse", projectKey, repoSlug)))).append(" |\n");
        String description = repo.path("description").asText("");
        sb.append("\n## Description\n").append(description.isBlank() ? "(no description)" : description).append('\n');
        JsonNode clone = repo.path("links").path("clone");
        if (clone.isArray() && clone.size() > 0) {
            sb.append("\n## Clone URLs\n");
            for (JsonNode c : clone) {
                sb.append("- ").append(c.path("name").asText("url")).append(": ").append(c.path("href").asText()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    // The commit a branch or tag points at, as its own section: a ref's name alone says almost nothing.
    private void appendPointedCommit(StringBuilder sb, String heading, String baseUrl, String projectKey,
                                     String repoSlug, String commitId) {
        if (commitId == null || commitId.isBlank()) return;
        BitbucketCommitDto commit = fetchCommitDetails(baseUrl, projectKey, repoSlug, commitId);
        sb.append("\n## ").append(heading).append("\n| Field | Value |\n|---|---|\n");
        sb.append("| Hash | ").append(commitId).append(" |\n");
        if (commit == null) return;
        sb.append("| Author | ").append(commit.authorName);
        if (commit.authorEmail != null && !commit.authorEmail.isBlank()) sb.append(" <").append(commit.authorEmail).append('>');
        sb.append(" |\n");
        if (commit.authorTimestampMs > 0) {
            sb.append("| Date | ").append(epochMsToLocalDateTime(commit.authorTimestampMs).format(DISPLAY_FORMATTER)).append(" |\n");
        }
        sb.append("| URL | ").append(commit.commitUrl).append(" |\n");
        sb.append("\n### Message\n").append(commit.message != null && !commit.message.isBlank() ? commit.message : "(no message)").append('\n');
    }

    // Detected keys are a naming-convention HINT - see JIRA_KEY_PATTERN - and the heading says where
    // they came from so neither a reader nor the agent takes one for a verified link.
    private static void appendJiraKeys(StringBuilder sb, List<String> keys, String where) {
        if (keys.isEmpty()) return;
        sb.append("\n## Jira Issues Referenced\nDetected in ").append(where).append(": ")
                .append(String.join(", ", keys)).append('\n');
    }

    // filterText is a SUBSTRING match, so "main" also returns "maintenance" - the exact displayId is
    // picked out of the pages, and the walk is bounded in case the filter matches thousands.
    private JsonNode findRef(String baseUrl, String projectKey, String repoSlug, String listPath, String name) {
        String sep = listPath.endsWith("?") ? "" : "&";
        int start = 0;
        for (int page = 0; page < 20; page++) {
            JsonNode root = getJsonAt(repoApi(baseUrl, projectKey, repoSlug, listPath + sep + "filterText="
                    + encodePathSegment(name) + "&start=" + start + "&limit=100"), "find ref", true);
            if (root == null) return null;
            for (JsonNode v : root.path("values")) {
                if (name.equals(v.path("displayId").asText())) return v;
            }
            if (root.path("isLastPage").asBoolean(true)) return null;
            start = root.path("nextPageStart").asInt(start + 100);
        }
        return null;
    }

    // default-branch is the current endpoint (7.x); branches/default is the older one, still answered
    // by servers that predate it. Neither answering is not an error - an empty repository has none.
    private String defaultBranchName(String baseUrl, String projectKey, String repoSlug) {
        for (String path : List.of("default-branch", "branches/default")) {
            try {
                JsonNode node = getJsonAt(repoApi(baseUrl, projectKey, repoSlug, path), "default branch", true);
                if (node != null && !node.path("displayId").asText("").isBlank()) return node.path("displayId").asText();
            } catch (IllegalStateException e) {
                log.debug("[Bitbucket] {} not answered: {}", path, e.getMessage());
            }
        }
        return null;
    }

    private static String firstLink(JsonNode links, String fallback) {
        return links.isArray() && links.size() > 0 ? links.get(0).path("href").asText(fallback) : fallback;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private InputStream getPrContent(ItemMetadata metadata) {
        String baseUrl = metadata.getExtra("baseUrl");
        String projectKey = metadata.getExtra("projectKey");
        String repoSlug = metadata.getExtra("repoSlug");
        String prIdStr = metadata.getExtra("prId");

        // Fallback path for an ItemMetadata reconstructed from a TrackedDocument row (no live
        // enumeration pass, extraProperties empty) - baseUrl/projectKey/repoSlug are connector-
        // constant and equal to what auth() already stored on this connector instance; prId is
        // recoverable by stripping the literal "PR-" prefix toItemMetadata() always embeds in id.
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = this.authBaseUrl;
        if (projectKey == null || projectKey.isBlank()) projectKey = this.authProjectKey;
        if (repoSlug == null || repoSlug.isBlank()) repoSlug = this.authRepoSlug;
        if ((prIdStr == null || prIdStr.isBlank())
                && metadata.getId() != null && metadata.getId().startsWith("PR-")) {
            prIdStr = metadata.getId().substring("PR-".length());
        }

        if (baseUrl == null || projectKey == null || repoSlug == null || prIdStr == null) {
            throw new IllegalArgumentException(
                    "BitbucketConnector.getContent() requires baseUrl, projectKey, repoSlug, prId "
                    + "(from extraProperties or a prior auth() call). Got extras: "
                    + metadata.getExtraProperties() + ", id=" + metadata.getId());
        }

        int prId;
        try {
            prId = Integer.parseInt(prIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid prId (not an integer): " + prIdStr, e);
        }

        BitbucketPrDto dto = fetchPrDetails(baseUrl, projectKey, repoSlug, prId);

        // GONE IS NOT EMPTY. An empty stream reaches the pipeline as "no extractable text", which is
        // recorded as a failure and retried on every sync until the next full one purges the row;
        // saying it no longer exists says what actually happened, once.
        if (dto == null) {
            throw new ai.proxyma.core.connector.ItemGoneException("Pull request PR-" + prId + " no longer exists");
        }

        addPrScope(dto, baseUrl, projectKey, repoSlug);
        String markdown = prToMarkdown(dto);
        return new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8));
    }

    // Same fallback shape as getPrContent() above - baseUrl/projectKey/repoSlug recovered from
    // this connector's own auth() state when extraProperties is empty (a reconstructed
    // ItemMetadata, no live enumeration pass); the commit hash is already in hand from the
    // caller-stripped "COMMIT-" id prefix, so there is no equivalent of prId's int-parse step.
    private InputStream getCommitContent(ItemMetadata metadata, String commitIdFromPrefix) {
        String baseUrl = metadata.getExtra("baseUrl");
        String projectKey = metadata.getExtra("projectKey");
        String repoSlug = metadata.getExtra("repoSlug");
        String commitId = metadata.getExtra("commitId");

        if (baseUrl == null || baseUrl.isBlank()) baseUrl = this.authBaseUrl;
        if (projectKey == null || projectKey.isBlank()) projectKey = this.authProjectKey;
        if (repoSlug == null || repoSlug.isBlank()) repoSlug = this.authRepoSlug;
        if (commitId == null || commitId.isBlank()) commitId = commitIdFromPrefix;

        if (baseUrl == null || projectKey == null || repoSlug == null || commitId == null || commitId.isBlank()) {
            throw new IllegalArgumentException(
                    "BitbucketConnector.getContent() requires baseUrl, projectKey, repoSlug, commitId "
                    + "(from extraProperties or a prior auth() call). Got extras: "
                    + metadata.getExtraProperties() + ", id=" + metadata.getId());
        }

        BitbucketCommitDto dto = fetchCommitDetails(baseUrl, projectKey, repoSlug, commitId);

        // See getPrContent: a 404 here is a deleted or rewritten history, not an empty document.
        if (dto == null) {
            throw new ai.proxyma.core.connector.ItemGoneException("Commit " + commitId + " no longer exists");
        }

        addCommitScope(dto, baseUrl, projectKey, repoSlug);
        String markdown = commitToMarkdown(dto);
        return new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8));
    }

    // - Bitbucket REST API -------------------------------------------------------

    public BitbucketRepoInfo fetchRepo(String baseUrl, String projectKey, String repoSlug) {
        return fetchRepoWith(baseUrl, requireConfig(baseUrl), projectKey, repoSlug);
    }

    /**
     * Checks a repository is reachable with THIS token, before a source is registered.
     *
     * <p>Takes the token as an argument rather than reading the bean's auth fields: this connector is
     * shared by every Bitbucket source and a sync may be using those fields right now. Throws with
     * the reason - token rejected, no permission, repository not found - which the register call returns
     * to the user instead of accepting a source that will index nothing.
     */
    public BitbucketRepoInfo probeRepoAs(String baseUrl, String accessToken, String projectKey, String repoSlug) {
        // The registration wording, not the tool one: requireExplicitToken talks about Settings in
        // the abstract, and the person reading this is halfway through registering a repository.
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException(NO_TOKEN);
        }
        if (projectKey == null || projectKey.isBlank() || repoSlug == null || repoSlug.isBlank()) {
            throw new IllegalStateException(BAD_NAME);
        }
        return fetchRepoWith(AtlassianServer.bitbucketRoot(baseUrl), accessToken, projectKey, repoSlug);
    }

    private BitbucketRepoInfo fetchRepoWith(String baseUrl, String token, String projectKey, String repoSlug) {
        String url = joinUrl(baseUrl,
                String.format("rest/api/1.0/projects/%s/repos/%s", projectKey, repoSlug));

        try {
            String body = restClient.get()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, token))
                    .retrieve()
                    .onStatus(status -> status.value() == 401,
                            (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                    .onStatus(status -> status.value() == 403,
                            (req, resp) -> { throw new IllegalStateException(noAccess(projectKey, repoSlug)); })
                    .onStatus(status -> status.value() == 404,
                            (req, resp) -> { throw new IllegalStateException(
                                    repoNotFound(baseUrl, projectKey, repoSlug)); })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> { throw new IllegalStateException(
                                    "Bitbucket API error: " + resp.getStatusCode().value()
                                    + " " + resp.getStatusText()); })
                    .body(String.class);

            // A 200 that is a page - a sign-in or SSO gateway in front of the server, or the address
            // of something that is not Bitbucket - reached a server; it is not "cannot reach".
            if (body != null && body.stripLeading().startsWith("<")) {
                throw new IllegalStateException(PAGE_NOT_API);
            }
            JsonNode root = objectMapper.readTree(body);
            String slug = root.path("slug").asText();
            String name = root.path("name").asText();
            return new BitbucketRepoInfo(slug, name);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // An unreachable host (unknown name, refused, TLS) lands here, and it is as much a reason
            // to refuse a registration as a 401: say which server and why, from the innermost cause.
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String why = root instanceof Exception rootEx ? SyncErrorAware.summarize(rootEx) : root.toString();
            throw new IllegalStateException("Cannot reach Bitbucket at " + baseUrl + ": " + why, e);
        }
    }

    public PrBatchResult fetchPrsBatch(String baseUrl, String projectKey, String repoSlug,
                                       int start, int limit) {
        String token = requireConfig(baseUrl);
        String url = joinUrl(baseUrl, String.format(
                "rest/api/1.0/projects/%s/repos/%s/pull-requests?state=ALL&order=NEWEST&start=%d&limit=%d",
                projectKey, repoSlug, start, limit));

        try {
            String body = restClient.get()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, token))
                    .retrieve()
                    .onStatus(status -> status.value() == 401,
                            (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                    .onStatus(status -> status.value() == 403,
                            (req, resp) -> { throw new IllegalStateException(noAccess(projectKey, repoSlug)); })
                    .onStatus(status -> status.value() == 404,
                            (req, resp) -> { throw new IllegalStateException(repoNotFound(baseUrl, projectKey, repoSlug)); })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> { throw new IllegalStateException(
                                    "Bitbucket API error: " + resp.getStatusCode().value()
                                    + " " + resp.getStatusText()); })
                    .body(String.class);

            JsonNode root = objectMapper.readTree(body);
            List<BitbucketPrDto> prs = parsePrValues(root);
            boolean hasMore = !root.path("isLastPage").asBoolean(true);
            int nextStart = root.path("nextPageStart").asInt(start + prs.size());

            return new PrBatchResult(prs, hasMore, nextStart);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Bitbucket PRs for: " + projectKey + "/" + repoSlug, e);
        }
    }

    // `since` is an INSTANT and is compared as epoch milliseconds - see INCREMENTAL_OVERLAP. The
    // listing is ordered NEWEST first, so the first PR older than the cutoff ends the walk.
    public PrBatchResult fetchModifiedPrsBatch(String baseUrl, String projectKey, String repoSlug,
                                               Instant since, int start, int limit) {
        String token = requireConfig(baseUrl);
        String url = joinUrl(baseUrl, String.format(
                "rest/api/1.0/projects/%s/repos/%s/pull-requests?state=ALL&order=NEWEST&start=%d&limit=%d",
                projectKey, repoSlug, start, limit));

        try {
            String body = restClient.get()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, token))
                    .retrieve()
                    .onStatus(status -> status.value() == 401,
                            (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                    .onStatus(status -> status.value() == 403,
                            (req, resp) -> { throw new IllegalStateException(noAccess(projectKey, repoSlug)); })
                    .onStatus(status -> status.value() == 404,
                            (req, resp) -> { throw new IllegalStateException(repoNotFound(baseUrl, projectKey, repoSlug)); })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> { throw new IllegalStateException(
                                    "Bitbucket API error: " + resp.getStatusCode().value()
                                    + " " + resp.getStatusText()); })
                    .body(String.class);

            JsonNode root = objectMapper.readTree(body);
            JsonNode values = root.path("values");

            List<BitbucketPrDto> prs = new ArrayList<>();
            boolean hasMore = false;
            int nextStart = start;

            long sinceMs = since.toEpochMilli();
            if (values.isArray()) {
                for (JsonNode prNode : values) {
                    long updatedMs = prNode.path("updatedDate").asLong(0);

                    if (updatedMs < sinceMs) {
                        hasMore = false;
                        break;
                    }

                    prs.add(parsePrNode(prNode));
                }

                boolean isLastPage = root.path("isLastPage").asBoolean(true);
                if (!isLastPage && prs.size() == limit) {
                    hasMore = true;
                    nextStart = root.path("nextPageStart").asInt(start + limit);
                }
            }

            return new PrBatchResult(prs, hasMore, nextStart);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to fetch modified Bitbucket PRs for: " + projectKey + "/" + repoSlug, e);
        }
    }

    // Every branch's displayId (e.g. "main", "feature/x"), fully paginated up front - a repo
    // rarely has more than a few hundred branches, so loading the whole list in memory (unlike
    // commits, walked lazily/paginated below) is not a concern.
    public List<String> fetchAllBranchIds(String baseUrl, String projectKey, String repoSlug) {
        return fetchAllBranchIds(baseUrl, requireConfig(baseUrl), projectKey, repoSlug);
    }

    // Explicit-token overload for the tool path - see addPrComment's overload comment below.
    public List<String> fetchAllBranchIds(String baseUrl, String accessToken, String projectKey, String repoSlug) {
        String token = requireExplicitToken(accessToken);
        List<String> ids = new ArrayList<>();
        int start = 0;
        boolean hasMore = true;
        while (hasMore) {
            String url = joinUrl(baseUrl, String.format(
                    "rest/api/1.0/projects/%s/repos/%s/branches?start=%d&limit=100",
                    projectKey, repoSlug, start));
            try {
                String body = restClient.get()
                        .uri(url)
                        .headers(h -> addAuthHeader(h, token))
                        .retrieve()
                        .onStatus(status -> status.value() == 401,
                                (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                        .onStatus(status -> status.value() == 403,
                                (req, resp) -> { throw new IllegalStateException(noAccess(projectKey, repoSlug)); })
                        .onStatus(status -> status.value() == 404,
                                (req, resp) -> { throw new IllegalStateException(repoNotFound(baseUrl, projectKey, repoSlug)); })
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                (req, resp) -> { throw new IllegalStateException(
                                        "Bitbucket API error: " + resp.getStatusCode().value()
                                        + " " + resp.getStatusText()); })
                        .body(String.class);

                JsonNode root = objectMapper.readTree(body);
                JsonNode values = root.path("values");
                if (values.isArray()) {
                    for (JsonNode b : values) {
                        String displayId = b.path("displayId").asText();
                        if (!displayId.isBlank()) ids.add(displayId);
                    }
                }
                boolean isLastPage = root.path("isLastPage").asBoolean(true);
                hasMore = !isLastPage;
                start = root.path("nextPageStart").asInt(start + 100);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to fetch Bitbucket branches for: " + projectKey + "/" + repoSlug, e);
            }
        }
        return ids;
    }

    // One branch's commits, newest-first (Bitbucket's default commits ordering), optionally
    // stopping early once a commit older than `since` is reached (null since = full walk, used by
    // sync()'s non-incremental path). Same shape as fetchModifiedPrsBatch()'s early-stop logic.
    public CommitBatchResult fetchCommitsBatch(String baseUrl, String projectKey, String repoSlug,
                                               String branch, Instant since, int start, int limit) {
        return fetchCommitsBatch(baseUrl, requireConfig(baseUrl), projectKey, repoSlug, branch, since, start, limit);
    }

    // Explicit-token overload for the tool path - see addPrComment's overload comment below.
    // A blank/null branch omits the `until` parameter entirely, which makes Bitbucket walk the
    // repository's default branch - the sensible reading of "the latest commits" when the user
    // didn't name a branch. sync()'s callers always pass a real branch, so they are unaffected.
    public CommitBatchResult fetchCommitsBatch(String baseUrl, String accessToken, String projectKey, String repoSlug,
                                               String branch, Instant since, int start, int limit) {
        String token = requireExplicitToken(accessToken);
        String until = (branch == null || branch.isBlank())
                ? ""
                : "until=" + encodePathSegment(branch) + "&";
        String url = joinUrl(baseUrl, String.format(
                "rest/api/1.0/projects/%s/repos/%s/commits?%sstart=%d&limit=%d",
                projectKey, repoSlug, until, start, limit));

        boolean namedBranch = branch != null && !branch.isBlank();
        try {
            String body = restClient.get()
                    .uri(encodedUri(url))
                    .headers(h -> addAuthHeader(h, token))
                    .retrieve()
                    .onStatus(status -> status.value() == 401,
                            (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                    .onStatus(status -> status.value() == 403,
                            (req, resp) -> { throw new IllegalStateException(noAccess(projectKey, repoSlug)); })
                    // A 404 for a named branch is the branch, deleted since the walk listed it -
                    // routine during a walk that takes minutes. Its own type so the walk can step
                    // over it; it used to end the whole listing as an ERROR.
                    .onStatus(status -> status.value() == 404 && namedBranch,
                            (req, resp) -> { throw new BranchGoneException(branch); })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> { throw new IllegalStateException(
                                    "Bitbucket API error: " + resp.getStatusCode().value()
                                    + " " + resp.getStatusText()); })
                    .body(String.class);

            JsonNode root = objectMapper.readTree(body);
            JsonNode values = root.path("values");
            List<BitbucketCommitDto> commits = new ArrayList<>();
            boolean anyRecent = false;

            // AN INCREMENTAL WALK STOPS AFTER A WHOLE PAGE OF OLD COMMITS, by COMMITTER time. It
            // stopped at the first commit whose AUTHOR time was older than the cutoff - but commits
            // come in graph order and author dates are not monotonic: a merged feature branch, a
            // rebase or a cherry-pick puts newly reachable commits with old author dates behind
            // the merge, and the walk stopped before them.
            if (values.isArray()) {
                for (JsonNode c : values) {
                    BitbucketCommitDto dto = parseCommitNode(c, baseUrl, projectKey, repoSlug);
                    // Epoch milliseconds on both sides - see fetchModifiedPrsBatch.
                    long when = dto.committerTimestampMs > 0 ? dto.committerTimestampMs : dto.authorTimestampMs;
                    if (since != null && when < since.toEpochMilli()) continue;
                    anyRecent = true;
                    commits.add(dto);
                }
            }
            boolean stoppedEarly = since != null && !anyRecent;

            boolean isLastPage = root.path("isLastPage").asBoolean(true);
            boolean hasMore = !stoppedEarly && !isLastPage;
            int nextStart = root.path("nextPageStart").asInt(start + commits.size());

            return new CommitBatchResult(commits, hasMore, nextStart);

        } catch (IllegalStateException | BranchGoneException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Bitbucket commits for: "
                    + projectKey + "/" + repoSlug + " branch=" + branch, e);
        }
    }

    /** A branch that no longer exists - see fetchCommitsBatch. */
    static final class BranchGoneException extends RuntimeException {
        BranchGoneException(String branch) {
            super("The branch " + branch + " no longer exists");
        }
    }

    public BitbucketCommitDto fetchCommitDetails(String baseUrl, String projectKey, String repoSlug, String commitId) {
        return fetchCommitDetails(baseUrl, requireConfig(baseUrl), projectKey, repoSlug, commitId);
    }

    // Explicit-token overload for the tool path - see addPrComment's overload comment below.
    public BitbucketCommitDto fetchCommitDetails(String baseUrl, String accessToken, String projectKey,
                                                 String repoSlug, String commitId) {
        String token = requireExplicitToken(accessToken);
        String url = joinUrl(baseUrl, String.format(
                "rest/api/1.0/projects/%s/repos/%s/commits/%s", projectKey, repoSlug, commitId));

        try {
            String body;
            try {
                body = restClient.get()
                        .uri(url)
                        .headers(h -> addAuthHeader(h, token))
                        .retrieve()
                        // 404 FIRST. RestClient applies the FIRST matching handler, so while the
                        // catch-all 4xx below was registered ahead of it a missing commit became
                        // "Bitbucket API error: 404 Not Found" and the catch beneath was dead code -
                        // the caller never got the null it tests for. Fixed 2026-09-18.
                        .onStatus(status -> status.value() == 404,
                                (req, resp) -> { throw HttpClientErrorException.create(resp.getStatusCode(),
                                        resp.getStatusText(), resp.getHeaders(), new byte[0], StandardCharsets.UTF_8); })
                        .onStatus(status -> status.value() == 401,
                                (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                        .onStatus(status -> status.value() == 403,
                                (req, resp) -> { throw new IllegalStateException(noAccess(projectKey, repoSlug)); })
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                (req, resp) -> { throw new IllegalStateException(
                                        "Bitbucket API error: " + resp.getStatusCode().value()
                                        + " " + resp.getStatusText()); })
                        .body(String.class);
            } catch (HttpClientErrorException.NotFound e) {
                return null;
            }
            if (body == null) return null;
            return parseCommitNode(objectMapper.readTree(body), baseUrl, projectKey, repoSlug);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to fetch Bitbucket commit details: " + projectKey + "/" + repoSlug + " " + commitId, e);
        }
    }

    private BitbucketCommitDto parseCommitNode(JsonNode c, String baseUrl, String projectKey, String repoSlug) {
        BitbucketCommitDto dto = new BitbucketCommitDto();
        dto.id = c.path("id").asText();
        dto.displayId = c.path("displayId").asText();
        dto.message = c.path("message").asText();
        JsonNode author = c.path("author");
        dto.authorName = author.path("name").asText("Unknown");
        dto.authorEmail = author.path("emailAddress").asText("");
        dto.authorTimestampMs = c.path("authorTimestamp").asLong(0);
        dto.committerName = c.path("committer").path("name").asText("");
        dto.committerTimestampMs = c.path("committerTimestamp").asLong(0);
        for (JsonNode p : c.path("parents")) {
            String parent = p.path("displayId").asText(p.path("id").asText(""));
            if (!parent.isBlank()) dto.parents.add(parent);
        }
        dto.commitUrl = joinUrl(baseUrl, String.format(
                "projects/%s/repos/%s/commits/%s", projectKey, repoSlug, dto.id));
        return dto;
    }

    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // RestClient's .uri(String) runs the value through DefaultUriBuilderFactory in its default
    // TEMPLATE_AND_VALUES mode, which percent-encodes the '%' of an ALREADY-encoded segment:
    // "until=feature%2Fx" goes out as "until=feature%252Fx" and Bitbucket 404s on the unknown
    // ref. Handing RestClient a pre-built URI bypasses the factory entirely. Any request whose
    // URL embeds an encodePathSegment(...) result must go through this - see the equivalent
    // JQL/CQL double-encoding trap documented for the Jira/Confluence connectors.
    private static URI encodedUri(String alreadyEncodedUrl) {
        return URI.create(alreadyEncodedUrl);
    }

    public String commitToMarkdown(BitbucketCommitDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Commit ").append(dto.displayId != null ? dto.displayId : dto.id).append("\n\n");
        sb.append("## Commit Details\n");
        sb.append("| Field | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| Hash | ").append(dto.id).append(" |\n");
        sb.append("| Author | ").append(dto.authorName);
        if (dto.authorEmail != null && !dto.authorEmail.isBlank()) sb.append(" <").append(dto.authorEmail).append(">");
        sb.append(" |\n");
        if (dto.authorTimestampMs > 0) {
            sb.append("| Date | ").append(epochMsToLocalDateTime(dto.authorTimestampMs).format(DISPLAY_FORMATTER)).append(" |\n");
        }
        if (dto.committerName != null && !dto.committerName.isBlank() && !dto.committerName.equals(dto.authorName)) {
            sb.append("| Committer | ").append(dto.committerName).append(" |\n");
        }
        if (dto.parents != null && !dto.parents.isEmpty()) {
            sb.append("| ").append(dto.parents.size() > 1 ? "Parents (merge commit)" : "Parent").append(" | ")
                    .append(String.join(", ", dto.parents)).append(" |\n");
        }
        sb.append("| URL | ").append(dto.commitUrl != null ? dto.commitUrl : "").append(" |\n");
        sb.append("\n## Message\n");
        sb.append(dto.message != null && !dto.message.isBlank() ? dto.message : "(no message)").append('\n');
        if (dto.pullRequests != null && !dto.pullRequests.isEmpty()) {
            sb.append("\n## Pull Requests\n");
            for (String pr : dto.pullRequests) sb.append("- ").append(pr).append('\n');
        }
        appendChanges(sb, dto.changes, dto.changesTruncated);
        appendJiraKeys(sb, extractJiraIssueKeys(dto.message), "the commit message");
        return sb.toString().trim();
    }

    public BitbucketPrDto fetchPrDetails(String baseUrl, String projectKey, String repoSlug, int prId) {
        return fetchPrDetails(baseUrl, requireConfig(baseUrl), projectKey, repoSlug, prId);
    }

    // Explicit-token overload for the tool path - see addPrComment's overload comment below.
    public BitbucketPrDto fetchPrDetails(String baseUrl, String accessToken, String projectKey,
                                         String repoSlug, int prId) {
        String token = requireExplicitToken(accessToken);
        String prUrl = joinUrl(baseUrl, String.format(
                "rest/api/1.0/projects/%s/repos/%s/pull-requests/%d", projectKey, repoSlug, prId));

        try {
            String prBody;
            try {
                prBody = restClient.get()
                        .uri(prUrl)
                        .headers(h -> addAuthHeader(h, token))
                        .retrieve()
                        // 404 FIRST, for the reason spelled out in fetchCommitDetails: registered
                        // after the catch-all it never ran, and a PR that no longer exists came back
                        // as an API error instead of the null every caller here tests for.
                        .onStatus(status -> status.value() == 404,
                                (req, resp) -> { throw HttpClientErrorException.create(resp.getStatusCode(),
                                        resp.getStatusText(), resp.getHeaders(), new byte[0], StandardCharsets.UTF_8); })
                        .onStatus(status -> status.value() == 401,
                                (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                        .onStatus(status -> status.value() == 403,
                                (req, resp) -> { throw new IllegalStateException(noAccess(projectKey, repoSlug)); })
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                (req, resp) -> { throw new IllegalStateException(
                                        "Bitbucket API error: " + resp.getStatusCode().value()
                                        + " " + resp.getStatusText()); })
                        .body(String.class);
            } catch (HttpClientErrorException.NotFound e) {
                return null;
            }

            if (prBody == null) return null;

            JsonNode prNode = objectMapper.readTree(prBody);
            BitbucketPrDto dto = parsePrNode(prNode);
            fetchPrActivities(dto, baseUrl, token, projectKey, repoSlug);
            dto.content = prToMarkdown(dto);

            return dto;

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to fetch Bitbucket PR details: " + projectKey + "/" + repoSlug + " PR-" + prId, e);
        }
    }

    public void addPrComment(String baseUrl, String projectKey, String repoSlug, int prId, String text) {
        addPrComment(baseUrl, requireConfig(baseUrl), projectKey, repoSlug, prId, text);
    }

    // Explicit-token overload for the tool path - no shared connector auth state involved, so no
    // synchronized/auth() needed by callers (see ../proxyma-docs/LESSONS_LEARNED.md 2026-07-16 "Jira tool
    // calls serialize behind a shared-connector lock").
    public void addPrComment(String baseUrl, String accessToken, String projectKey, String repoSlug, int prId, String text) {
        String token = requireExplicitToken(accessToken);
        String url = joinUrl(baseUrl, String.format(
                "rest/api/1.0/projects/%s/repos/%s/pull-requests/%d/comments", projectKey, repoSlug, prId));

        try {
            restClient.post()
                    .uri(url)
                    .headers(h -> addAuthHeader(h, token))
                    .body(java.util.Map.of("text", text))
                    .retrieve()
                    .onStatus(status -> status.value() == 401,
                            (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                    .onStatus(status -> status.value() == 403,
                            (req, resp) -> { throw new IllegalStateException(noAccess(projectKey, repoSlug)); })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> { throw new IllegalStateException(
                                    "Bitbucket API error: " + resp.getStatusCode().value()
                                    + " " + resp.getStatusText()); })
                    .toBodilessEntity();
            log.debug("Added comment to Bitbucket PR: {}/{} PR-{}", projectKey, repoSlug, prId);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to add comment to Bitbucket PR: " + projectKey + "/" + repoSlug + " PR-" + prId, e);
        }
    }

    // - Pull request / tag write operations --------------------------------------
    //
    // Everything below is tool-path only, so these take an explicit access token exclusively -
    // there is no requireConfig()-based overload, because background sync never creates PRs,
    // reviews, merges, or tags. They share withStandardErrors()/readErrorMessage() rather than
    // repeating the inline onStatus chains the older sync-path methods above use: Bitbucket
    // returns a genuinely useful `errors[].message` (e.g. "You do not have permission", "The
    // pull request has conflicts") and surfacing it verbatim is what makes a failed tool call
    // actionable for the agent instead of a bare status code.

    // Bitbucket Server/DC has no /myself endpoint; it instead stamps the authenticated username
    // onto the X-AUSERNAME response header of every REST response. That header is the documented
    // way to answer "who am I", and reviewPr() needs it to build the participant path.
    public String fetchCurrentUsername(String baseUrl, String accessToken) {
        String token = requireExplicitToken(accessToken);
        String url = joinUrl(baseUrl, "rest/api/1.0/application-properties");
        try {
            HttpHeaders headers = withStandardErrors(
                    restClient.get()
                            .uri(url)
                            .headers(h -> addAuthHeader(h, token))
                            .retrieve(),
                    "current user")
                    .toBodilessEntity()
                    .getHeaders();
            String username = headers.getFirst("X-AUSERNAME");
            if (username == null || username.isBlank() || "anonymous".equalsIgnoreCase(username)) {
                throw new IllegalStateException(
                        "Bitbucket did not identify the current user (no X-AUSERNAME header). "
                        + "The access token may be invalid, or a proxy is stripping the header.");
            }
            return username;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to determine the current Bitbucket user", e);
        }
    }

    // reviewers are Bitbucket usernames (the `name` field), not display names.
    public BitbucketPrDto createPr(String baseUrl, String accessToken, String projectKey, String repoSlug,
                                   String title, String description, String fromBranch, String toBranch,
                                   List<String> reviewers) {
        String token = requireExplicitToken(accessToken);
        String url = joinUrl(baseUrl, String.format(
                "rest/api/1.0/projects/%s/repos/%s/pull-requests", projectKey, repoSlug));

        Map<String, Object> repoRef = Map.of("slug", repoSlug, "project", Map.of("key", projectKey));
        List<Map<String, Object>> reviewerBody = new ArrayList<>();
        if (reviewers != null) {
            for (String r : reviewers) {
                if (r != null && !r.isBlank()) {
                    reviewerBody.add(Map.of("user", Map.of("name", r.trim())));
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description != null ? description : "");
        body.put("state", "OPEN");
        body.put("open", true);
        body.put("closed", false);
        body.put("fromRef", Map.of("id", toBranchRef(fromBranch), "repository", repoRef));
        body.put("toRef", Map.of("id", toBranchRef(toBranch), "repository", repoRef));
        body.put("reviewers", reviewerBody);
        body.put("locked", false);

        try {
            String responseBody = withStandardErrors(
                    restClient.post()
                            .uri(url)
                            .headers(h -> addAuthHeader(h, token))
                            .body(body)
                            .retrieve(),
                    "create pull request")
                    .body(String.class);
            BitbucketPrDto dto = parsePrNode(objectMapper.readTree(responseBody));
            log.debug("Created Bitbucket PR: {}/{} PR-{}", projectKey, repoSlug, dto.id);
            return dto;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Bitbucket pull request in "
                    + projectKey + "/" + repoSlug, e);
        }
    }

    // status: APPROVED | NEEDS_WORK | UNAPPROVED. Registers the caller as a participant if they
    // aren't already a reviewer, which is what makes a drive-by review work.
    public String reviewPr(String baseUrl, String accessToken, String projectKey, String repoSlug,
                           int prId, String status) {
        String token = requireExplicitToken(accessToken);
        String username = fetchCurrentUsername(baseUrl, accessToken);
        String url = joinUrl(baseUrl, String.format(
                "rest/api/1.0/projects/%s/repos/%s/pull-requests/%d/participants/%s",
                projectKey, repoSlug, prId, encodePathSegment(username)));

        try {
            String responseBody = withStandardErrors(
                    restClient.put()
                            .uri(encodedUri(url))
                            .headers(h -> addAuthHeader(h, token))
                            .body(Map.of("status", status))
                            .retrieve(),
                    "review pull request")
                    .body(String.class);
            String applied = responseBody != null
                    ? objectMapper.readTree(responseBody).path("status").asText(status)
                    : status;
            log.debug("Set Bitbucket PR review status: {}/{} PR-{} -> {}", projectKey, repoSlug, prId, applied);
            return applied;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to set review status on Bitbucket PR: "
                    + projectKey + "/" + repoSlug + " PR-" + prId, e);
        }
    }

    // Merge is the one operation with real optimistic-locking semantics: Bitbucket rejects the
    // POST unless the caller passes the PR's CURRENT version, so it is read here rather than
    // asked of the caller (the agent has no way to know it). The dry-run GET .../merge first
    // turns "conflicted" and "blocked by an unapproved required reviewer" into a readable
    // message instead of a 409 the agent would have to guess at.
    public String mergePr(String baseUrl, String accessToken, String projectKey, String repoSlug,
                          int prId, String message, String strategyId) {
        String token = requireExplicitToken(accessToken);
        String prUrl = joinUrl(baseUrl, String.format(
                "rest/api/1.0/projects/%s/repos/%s/pull-requests/%d", projectKey, repoSlug, prId));

        try {
            String prBody = withStandardErrors(
                    restClient.get().uri(prUrl).headers(h -> addAuthHeader(h, token)).retrieve(),
                    "read pull request")
                    .body(String.class);
            if (prBody == null) {
                throw new IllegalStateException("Pull request not found: "
                        + projectKey + "/" + repoSlug + " PR-" + prId);
            }
            JsonNode prNode = objectMapper.readTree(prBody);
            String state = prNode.path("state").asText("");
            if (!"OPEN".equalsIgnoreCase(state)) {
                throw new IllegalStateException("Pull request PR-" + prId + " is " + state
                        + ", not OPEN - it cannot be merged.");
            }
            int version = prNode.path("version").asInt(-1);

            String canMergeBody = withStandardErrors(
                    restClient.get().uri(prUrl + "/merge").headers(h -> addAuthHeader(h, token)).retrieve(),
                    "check mergeability")
                    .body(String.class);
            if (canMergeBody != null) {
                JsonNode canMerge = objectMapper.readTree(canMergeBody);
                if (canMerge.path("conflicted").asBoolean(false)) {
                    throw new IllegalStateException("Pull request PR-" + prId
                            + " has merge conflicts and cannot be merged automatically.");
                }
                if (!canMerge.path("canMerge").asBoolean(true)) {
                    throw new IllegalStateException("Pull request PR-" + prId + " cannot be merged: "
                            + summarizeVetoes(canMerge.path("vetoes")));
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("version", version);
            if (message != null && !message.isBlank()) body.put("message", message);
            if (strategyId != null && !strategyId.isBlank()) body.put("strategyId", strategyId);

            String mergedBody = withStandardErrors(
                    restClient.post()
                            .uri(prUrl + "/merge?version=" + version)
                            .headers(h -> addAuthHeader(h, token))
                            .body(body)
                            .retrieve(),
                    "merge pull request")
                    .body(String.class);
            String mergedState = mergedBody != null
                    ? objectMapper.readTree(mergedBody).path("state").asText("MERGED")
                    : "MERGED";
            log.debug("Merged Bitbucket PR: {}/{} PR-{}", projectKey, repoSlug, prId);
            return mergedState;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to merge Bitbucket PR: "
                    + projectKey + "/" + repoSlug + " PR-" + prId, e);
        }
    }

    public TagBatchResult fetchTagsBatch(String baseUrl, String accessToken, String projectKey, String repoSlug,
                                         String filterText, int start, int limit) {
        String token = requireExplicitToken(accessToken);
        StringBuilder path = new StringBuilder(String.format(
                "rest/api/1.0/projects/%s/repos/%s/tags?start=%d&limit=%d", projectKey, repoSlug, start, limit));
        if (filterText != null && !filterText.isBlank()) {
            path.append("&filterText=").append(encodePathSegment(filterText));
        }

        try {
            String body = withStandardErrors(
                    restClient.get().uri(encodedUri(joinUrl(baseUrl, path.toString())))
                            .headers(h -> addAuthHeader(h, token)).retrieve(),
                    "list tags")
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            List<BitbucketTagDto> tags = new ArrayList<>();
            JsonNode values = root.path("values");
            if (values.isArray()) {
                for (JsonNode t : values) {
                    tags.add(parseTagNode(t, baseUrl, projectKey, repoSlug));
                }
            }
            boolean hasMore = !root.path("isLastPage").asBoolean(true);
            int nextStart = root.path("nextPageStart").asInt(start + tags.size());
            return new TagBatchResult(tags, hasMore, nextStart);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Bitbucket tags for: "
                    + projectKey + "/" + repoSlug, e);
        }
    }

    // startPoint is any commit-ish: a commit hash, a branch name, or another tag.
    // Bitbucket moved tag creation from /rest/git/1.0 to /rest/api/1.0 in later versions and both
    // paths exist in the wild, so a 404 on the modern path falls back to the legacy one rather
    // than surfacing a confusing "not found" for a repo that plainly exists.
    public BitbucketTagDto createTag(String baseUrl, String accessToken, String projectKey, String repoSlug,
                                     String name, String startPoint, String message) {
        String token = requireExplicitToken(accessToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("startPoint", startPoint);
        body.put("message", message != null ? message : "");

        String modernPath = String.format("rest/api/1.0/projects/%s/repos/%s/tags", projectKey, repoSlug);
        String legacyPath = String.format("rest/git/1.0/projects/%s/repos/%s/tags", projectKey, repoSlug);
        try {
            String responseBody;
            try {
                responseBody = postTag(baseUrl, modernPath, token, body);
            } catch (HttpClientErrorException.NotFound e) {
                log.debug("Bitbucket tag creation not available at {}, retrying legacy path", modernPath);
                responseBody = postTag(baseUrl, legacyPath, token, body);
            }
            BitbucketTagDto dto = parseTagNode(objectMapper.readTree(responseBody), baseUrl, projectKey, repoSlug);
            log.debug("Created Bitbucket tag {} in {}/{}", name, projectKey, repoSlug);
            return dto;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Bitbucket tag '" + name + "' in "
                    + projectKey + "/" + repoSlug, e);
        }
    }

    // - Branches -----------------------------------------------------------------------
    //
    // Created through the core API (POST .../branches, Bitbucket 7 and later), falling back on a
    // 404 to the branch-utils path older servers answer on - the same two-path shape as createTag.
    // Deleted through branch-utils only: Data Center's core API has no DELETE for a branch.

    public Map<String, Object> createBranch(String baseUrl, String accessToken, String projectKey, String repoSlug,
                                            String name, String startPoint) {
        String token = requireExplicitToken(accessToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("startPoint", startPoint);
        String modernPath = String.format("rest/api/1.0/projects/%s/repos/%s/branches", projectKey, repoSlug);
        String legacyPath = String.format("rest/branch-utils/1.0/projects/%s/repos/%s/branches", projectKey, repoSlug);
        try {
            String responseBody;
            try {
                responseBody = postJson(baseUrl, modernPath, token, body, "create branch");
            } catch (HttpClientErrorException.NotFound e) {
                log.debug("Bitbucket branch creation not available at {}, retrying branch-utils", modernPath);
                responseBody = postJson(baseUrl, legacyPath, token, body, "create branch");
            }
            JsonNode b = objectMapper.readTree(responseBody);
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("name", b.path("displayId").asText(name));
            branch.put("ref", b.path("id").asText("refs/heads/" + name));
            branch.put("commitId", b.path("latestCommit").asText(""));
            log.debug("Created Bitbucket branch {} in {}/{}", name, projectKey, repoSlug);
            return branch;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Bitbucket branch '" + name + "' in "
                    + projectKey + "/" + repoSlug, e);
        }
    }

    public void deleteBranch(String baseUrl, String accessToken, String projectKey, String repoSlug, String name) {
        String token = requireExplicitToken(accessToken);
        String ref = name.startsWith("refs/") ? name : "refs/heads/" + name;
        String path = String.format("rest/branch-utils/1.0/projects/%s/repos/%s/branches", projectKey, repoSlug);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", ref);
        body.put("dryRun", false);
        try {
            withStandardErrors(
                    restClient.method(org.springframework.http.HttpMethod.DELETE)
                            .uri(joinUrl(baseUrl, path))
                            .headers(h -> addAuthHeader(h, token))
                            .body(body)
                            .retrieve(),
                    "delete branch")
                    .toBodilessEntity();
            log.debug("Deleted Bitbucket branch {} in {}/{}", name, projectKey, repoSlug);
        } catch (IllegalStateException e) {
            throw e;
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Bitbucket has no branch '" + name + "' in "
                    + projectKey + "/" + repoSlug + ".");
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete Bitbucket branch '" + name + "' in "
                    + projectKey + "/" + repoSlug, e);
        }
    }

    /**
     * A pull-request comment that can sit on a line of the diff, or answer another comment.
     *
     * @param path     file path in the repository; null or blank for a general comment
     * @param line     line number in that file; null for a comment on the whole file
     * @param lineType ADDED, REMOVED or CONTEXT - which side of the diff the line is on (default ADDED)
     * @param parentId the comment this one replies to, or null
     * @return the new comment's id
     */
    public long addPrComment(String baseUrl, String accessToken, String projectKey, String repoSlug, int prId,
                             String text, String path, Integer line, String lineType, Long parentId) {
        String token = requireExplicitToken(accessToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        if (parentId != null) body.put("parent", Map.of("id", parentId));
        if (path != null && !path.isBlank()) {
            Map<String, Object> anchor = new LinkedHashMap<>();
            anchor.put("path", path.strip());
            anchor.put("diffType", "EFFECTIVE");
            String type = lineType == null || lineType.isBlank() ? "ADDED" : lineType.strip().toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("ADDED", "REMOVED", "CONTEXT").contains(type)) {
                throw new IllegalArgumentException("lineType must be ADDED, REMOVED or CONTEXT, not '" + lineType + "'");
            }
            // A removed line exists only in the old file, so its line number is counted there.
            anchor.put("fileType", "REMOVED".equals(type) ? "FROM" : "TO");
            if (line != null) {
                anchor.put("line", line);
                anchor.put("lineType", type);
            }
            body.put("anchor", anchor);
        }
        String commentsPath = String.format("rest/api/1.0/projects/%s/repos/%s/pull-requests/%d/comments",
                projectKey, repoSlug, prId);
        try {
            String responseBody = postJson(baseUrl, commentsPath, token, body, "comment on pull request");
            return objectMapper.readTree(responseBody).path("id").asLong(0);
        } catch (IllegalStateException e) {
            throw e;
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Bitbucket has no pull request " + prId + " in "
                    + projectKey + "/" + repoSlug + (parentId != null ? ", or no comment " + parentId + " on it" : "") + ".");
        } catch (Exception e) {
            throw new RuntimeException("Failed to add comment to Bitbucket PR: " + projectKey + "/" + repoSlug
                    + " PR-" + prId, e);
        }
    }

    private String postJson(String baseUrl, String path, String token, Map<String, Object> body, String context) {
        return withStandardErrors(
                restClient.post()
                        .uri(joinUrl(baseUrl, path))
                        .headers(h -> addAuthHeader(h, token))
                        .body(body)
                        .retrieve(),
                context)
                .body(String.class);
    }

    private String postTag(String baseUrl, String path, String token, Map<String, Object> body) {
        return withStandardErrors(
                restClient.post()
                        .uri(joinUrl(baseUrl, path))
                        .headers(h -> addAuthHeader(h, token))
                        .body(body)
                        .retrieve(),
                "create tag")
                .body(String.class);
    }

    private BitbucketTagDto parseTagNode(JsonNode t, String baseUrl, String projectKey, String repoSlug) {
        BitbucketTagDto dto = new BitbucketTagDto();
        dto.name = t.path("displayId").asText();
        dto.ref = t.path("id").asText();
        dto.commitId = t.path("latestCommit").asText(t.path("hash").asText(""));
        dto.url = joinUrl(baseUrl, String.format(
                "projects/%s/repos/%s/browse?at=%s", projectKey, repoSlug, encodePathSegment(dto.ref)));
        return dto;
    }

    private static String summarizeVetoes(JsonNode vetoes) {
        if (!vetoes.isArray() || vetoes.size() == 0) {
            return "the server did not allow the merge (no reason given).";
        }
        List<String> reasons = new ArrayList<>();
        for (JsonNode v : vetoes) {
            String summary = v.path("summaryMessage").asText("");
            String detail = v.path("detailedMessage").asText("");
            reasons.add(detail.isBlank() ? summary : summary + " - " + detail);
        }
        return String.join("; ", reasons);
    }

    // "main" and "refs/heads/main" are both natural things for a caller to say; Bitbucket only
    // accepts the fully-qualified form in a PR's fromRef/toRef.
    private static String toBranchRef(String branch) {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("Branch name must not be empty.");
        }
        String trimmed = branch.trim();
        return trimmed.startsWith("refs/") ? trimmed : "refs/heads/" + trimmed;
    }

    private RestClient.ResponseSpec withStandardErrors(RestClient.ResponseSpec spec, String context) {
        return spec
                // A TYPE, not just a sentence: 401 and 403 read the same from here, and the write
                // tools need to tell "this token cannot write" from a conflict or a bad id so they
                // can say what to do about it. See BitbucketAccessDeniedException.
                .onStatus(status -> status.value() == 401 || status.value() == 403,
                        (req, resp) -> {
                            String detail = readErrorMessage(resp);
                            throw new BitbucketAccessDeniedException(detail != null
                                    ? "Bitbucket rejected the request (" + context + "): " + detail
                                    : resp.getStatusCode().value() == 401 ? TOKEN_REJECTED : noAccess(null, null));
                        })
                .onStatus(status -> status.value() == 404,
                        (req, resp) -> { throw HttpClientErrorException.create(resp.getStatusCode(),
                                resp.getStatusText(), resp.getHeaders(), new byte[0], StandardCharsets.UTF_8); })
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, resp) -> {
                            String detail = readErrorMessage(resp);
                            throw new IllegalStateException("Bitbucket API error (" + context + "): "
                                    + (detail != null ? detail
                                       : resp.getStatusCode().value() + " " + resp.getStatusText()));
                        });
    }

    // Bitbucket error bodies are {"errors":[{"message":"..."}]} - pull the human-readable part
    // out when it's there, and stay silent (null) when it isn't rather than echoing raw JSON.
    private String readErrorMessage(ClientHttpResponse resp) {
        try {
            String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode errors = objectMapper.readTree(body).path("errors");
            if (errors.isArray() && errors.size() > 0) {
                List<String> messages = new ArrayList<>();
                for (JsonNode e : errors) {
                    String m = e.path("message").asText("");
                    if (!m.isBlank()) messages.add(m);
                }
                if (!messages.isEmpty()) return String.join("; ", messages);
            }
        } catch (Exception ignored) {
            // Non-JSON or unreadable body - fall back to the status line.
        }
        return null;
    }

    // - Diffs --------------------------------------------------------------------

    public DiffResult fetchPrDiff(String baseUrl, String accessToken, String projectKey, String repoSlug,
                                  int prId, String path, int contextLines) {
        return fetchDiff(baseUrl, accessToken, String.format(
                "rest/api/1.0/projects/%s/repos/%s/pull-requests/%d/diff", projectKey, repoSlug, prId),
                path, contextLines, projectKey + "/" + repoSlug + " PR-" + prId);
    }

    public DiffResult fetchCommitDiff(String baseUrl, String accessToken, String projectKey, String repoSlug,
                                      String commitId, String path, int contextLines) {
        return fetchDiff(baseUrl, accessToken, String.format(
                "rest/api/1.0/projects/%s/repos/%s/commits/%s/diff", projectKey, repoSlug,
                encodePathSegment(commitId)),
                path, contextLines, projectKey + "/" + repoSlug + " " + commitId);
    }

    // Bitbucket returns a structured JSON diff (per-file hunks, each a list of CONTEXT/ADDED/
    // REMOVED segments) rather than patch text. Rendering it back into standard unified diff here
    // is deliberate on two counts: the raw JSON is several times the token cost of the equivalent
    // patch, and every model already knows how to read a unified diff. The `.diff` URL suffix
    // would return patch text directly but is not available across all Bitbucket versions, so
    // this takes the endpoint that always exists.
    private DiffResult fetchDiff(String baseUrl, String accessToken, String path, String filePath,
                                 int contextLines, String context) {
        String token = requireExplicitToken(accessToken);
        StringBuilder p = new StringBuilder(path);
        if (filePath != null && !filePath.isBlank()) {
            p.append("/").append(encodePathSegment(filePath.trim()));
        }
        p.append("?contextLines=").append(contextLines);

        try {
            String body = withStandardErrors(
                    restClient.get().uri(encodedUri(joinUrl(baseUrl, p.toString())))
                            .headers(h -> addAuthHeader(h, token)).retrieve(),
                    "diff")
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            List<FileDiff> files = new ArrayList<>();
            for (JsonNode d : root.path("diffs")) {
                files.add(parseFileDiff(d));
            }
            // Bitbucket sets this when the diff exceeded ITS own server-side limits, which is a
            // different thing from our own line budget - surfaced separately so the agent is not
            // told "here is the whole diff" when the server already cut it short.
            boolean serverTruncated = root.path("truncated").asBoolean(false);
            return new DiffResult(files, serverTruncated);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Bitbucket diff for: " + context, e);
        }
    }

    private FileDiff parseFileDiff(JsonNode d) {
        FileDiff fd = new FileDiff();
        String source = d.path("source").path("toString").asText(null);
        String destination = d.path("destination").path("toString").asText(null);
        fd.path = destination != null ? destination : source;
        fd.oldPath = source;
        if (source == null) fd.changeType = "ADDED";
        else if (destination == null) fd.changeType = "DELETED";
        else if (!source.equals(destination)) fd.changeType = "RENAMED";
        else fd.changeType = "MODIFIED";

        JsonNode hunks = d.path("hunks");
        // No hunks with a real path means Bitbucket had nothing textual to show - binary content,
        // or a pure mode/rename change. Saying so beats emitting an empty patch that reads as
        // "nothing changed".
        if (!hunks.isArray() || hunks.size() == 0) {
            fd.binaryOrNoContent = true;
            fd.patch = "";
            return fd;
        }

        StringBuilder patch = new StringBuilder();
        for (JsonNode hunk : hunks) {
            // An explicit LF like every diff line below - %n is CRLF on Windows, which mixed the two.
            patch.append(String.format("@@ -%d,%d +%d,%d @@" + NEWLINE,
                    hunk.path("sourceLine").asInt(), hunk.path("sourceSpan").asInt(),
                    hunk.path("destinationLine").asInt(), hunk.path("destinationSpan").asInt()));
            for (JsonNode segment : hunk.path("segments")) {
                String type = segment.path("type").asText("CONTEXT");
                char marker = switch (type) {
                    case "ADDED" -> '+';
                    case "REMOVED" -> '-';
                    default -> ' ';
                };
                for (JsonNode line : segment.path("lines")) {
                    patch.append(marker).append(line.path("line").asText("")).append('\n');
                    if (marker == '+') fd.added++;
                    else if (marker == '-') fd.removed++;
                }
            }
        }
        fd.patch = patch.toString();
        return fd;
    }

    public static class FileDiff {
        public String path;
        public String oldPath;
        public String changeType;
        public int added;
        public int removed;
        public boolean binaryOrNoContent;
        public String patch = "";
    }

    public static class DiffResult {
        public final List<FileDiff> files;
        public final boolean serverTruncated;

        public DiffResult(List<FileDiff> files, boolean serverTruncated) {
            this.files = files;
            this.serverTruncated = serverTruncated;
        }
    }

    // - Jira issue key detection -------------------------------------------------

    // Branch/PR/commit naming convention only - Bitbucket's real Jira links live behind the Jira
    // integration plugin, which is not guaranteed to be installed, so this deliberately reads the
    // key straight out of the text. That means it is a HINT, not an authoritative link: the
    // denylist below removes the common uppercase-dash-number tokens that are definitely not Jira
    // keys, but a caller should still confirm a detected key resolves to a real issue.
    private static final java.util.regex.Pattern JIRA_KEY_PATTERN =
            java.util.regex.Pattern.compile("\\b([A-Z][A-Z0-9]{1,9})-(\\d+)\\b");
    // "PR"/"MR" earn their place here the hard way: "Merge PR-12 into main" is one of the most
    // common commit messages there is, and reading that as a Jira key sends the agent chasing an
    // issue that does not exist. A project literally keyed PR would be missed - an acceptable
    // trade against a false positive on nearly every merge commit.
    private static final Set<String> JIRA_KEY_DENYLIST = Set.of(
            "UTF", "ISO", "RFC", "SHA", "SHA1", "SHA256", "SHA512", "MD5", "AES", "RSA", "HTTP",
            "HTTPS", "ASCII", "CVE", "CWE", "IPV4", "IPV6", "X86", "ARM64", "UTC", "GMT",
            "PR", "MR");

    public static List<String> extractJiraIssueKeys(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = JIRA_KEY_PATTERN.matcher(text);
        while (m.find()) {
            if (!JIRA_KEY_DENYLIST.contains(m.group(1))) {
                keys.add(m.group(1) + "-" + m.group(2));
            }
        }
        return List.copyOf(keys);
    }

    // - Markdown rendering -------------------------------------------------------

    public String prToMarkdown(BitbucketPrDto dto) {
        StringBuilder sb = new StringBuilder();

        sb.append("# [PR-").append(dto.id).append("] ").append(dto.title).append("\n\n");

        sb.append("## PR Details\n");
        sb.append("| Field | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| ID | ").append(dto.id).append(" |\n");
        sb.append("| State | ").append(dto.state).append(" |\n");
        sb.append("| Author | ").append(dto.author).append(" |\n");

        String reviewersStr = (dto.reviewers == null || dto.reviewers.isEmpty())
                ? "None"
                : String.join(", ", dto.reviewers);
        sb.append("| Reviewers | ").append(reviewersStr).append(" |\n");
        sb.append("| From Branch | ").append(dto.fromRef).append(" |\n");
        sb.append("| To Branch | ").append(dto.toRef).append(" |\n");

        if (dto.createdDate != null) {
            sb.append("| Created | ").append(dto.createdDate.format(DISPLAY_FORMATTER)).append(" |\n");
        }
        if (dto.updatedDate != null) {
            sb.append("| Updated | ").append(dto.updatedDate.format(DISPLAY_FORMATTER)).append(" |\n");
        }
        sb.append("| URL | ").append(dto.prUrl != null ? dto.prUrl : "").append(" |\n");
        sb.append("\n");

        sb.append("## Description\n");
        sb.append((dto.description != null && !dto.description.isBlank())
                ? dto.description
                : "(no description)");
        sb.append("\n");

        if (dto.activities != null && !dto.activities.isEmpty()) {
            sb.append("\n## Activity\n");
            for (ActivityEntry a : dto.activities) {
                sb.append("- ");
                if (a.date != null) sb.append(a.date.format(DISPLAY_FORMATTER)).append(" - ");
                sb.append(a.user).append(' ').append(a.description).append('\n');
            }
        }

        if (dto.comments != null && !dto.comments.isEmpty()) {
            sb.append("\n## Comments\n");
            for (CommentEntry c : dto.comments) {
                sb.append("### ").append(c.author != null ? c.author : "Unknown");
                if (c.createdDate != null) {
                    sb.append(" (").append(c.createdDate.format(DISPLAY_FORMATTER)).append(")");
                }
                if (c.filePath != null && !c.filePath.isBlank()) {
                    sb.append(" on `").append(c.filePath).append('`');
                    if (c.line != null) sb.append(" line ").append(c.line);
                }
                sb.append("\n");
                sb.append(c.text != null ? c.text : "").append("\n");
                appendReplies(sb, c.replies, 1);
            }
        }
        if (dto.activitiesTruncated) {
            sb.append("\n(Activity cut at the most recent ").append(MAX_PR_ACTIVITIES).append(" entries.)\n");
        }

        if (dto.commits != null && !dto.commits.isEmpty()) {
            sb.append("\n## Commits\n");
            for (BitbucketCommitDto c : dto.commits) {
                sb.append("- ").append(c.displayId).append(' ').append(commitTitle(c))
                        .append(" (").append(c.authorName).append(")\n");
            }
            if (dto.commitsTruncated) sb.append("- (list cut at ").append(dto.commits.size()).append(" commits)\n");
        }
        appendChanges(sb, dto.changes, dto.changesTruncated);
        // Title and source branch only, for the reason BitbucketToolService.getPr gives: a description
        // quoting logs or errors produces far more false positives than real keys.
        appendJiraKeys(sb, extractJiraIssueKeys(dto.title + " " + dto.fromRef), "the title and source branch");

        return sb.toString().trim();
    }

    // Replies as nested quotes, one level of '>' per level of reply, so a thread stays a thread.
    private static void appendReplies(StringBuilder sb, List<CommentEntry> replies, int depth) {
        if (replies == null) return;
        String quote = "> ".repeat(depth);
        for (CommentEntry r : replies) {
            sb.append('\n').append(quote).append("**").append(r.author != null ? r.author : "Unknown").append("**");
            if (r.createdDate != null) sb.append(" (").append(r.createdDate.format(DISPLAY_FORMATTER)).append(')');
            sb.append(":\n");
            for (String line : (r.text != null ? r.text : "").split("\n", -1)) {
                sb.append(quote).append(line).append('\n');
            }
            appendReplies(sb, r.replies, depth + 1);
        }
    }

    // - Private helpers ----------------------------------------------------------

    private String requireConfig(String baseUrl) {
        if (authAccessToken != null && !authAccessToken.isBlank()) {
            return authAccessToken;
        }
        throw new IllegalStateException(
                "BitbucketConnector not authenticated. Call auth() before making API requests.");
    }

    // The explicit-token counterpart of requireConfig(): validates a caller-supplied token
    // instead of the connector's shared auth() state, so the tool path never touches that state.
    private static String requireExplicitToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException(
                    "Bitbucket access token is not configured. Please set your personal access token in Settings.");
        }
        return accessToken;
    }

    private void addAuthHeader(HttpHeaders headers, String token) {
        if (token != null && token.contains(":")) {
            headers.set("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        } else {
            headers.set("Authorization", "Bearer " + token);
        }
    }

    private List<BitbucketPrDto> parsePrValues(JsonNode root) throws Exception {
        List<BitbucketPrDto> prs = new ArrayList<>();
        JsonNode values = root.path("values");
        if (values.isArray()) {
            for (JsonNode prNode : values) {
                prs.add(parsePrNode(prNode));
            }
        }
        return prs;
    }

    private BitbucketPrDto parsePrNode(JsonNode prNode) {
        BitbucketPrDto dto = new BitbucketPrDto();
        dto.id = prNode.path("id").asInt();
        dto.title = prNode.path("title").asText();
        dto.state = prNode.path("state").asText();

        JsonNode authorNode = prNode.path("author").path("user");
        dto.author = authorNode.path("displayName").asText("Unknown");

        dto.fromRef = prNode.path("fromRef").path("displayId").asText();
        dto.toRef = prNode.path("toRef").path("displayId").asText();

        long createdMs = prNode.path("createdDate").asLong(0);
        long updatedMs = prNode.path("updatedDate").asLong(0);
        if (createdMs > 0) dto.createdDate = epochMsToLocalDateTime(createdMs);
        if (updatedMs > 0) dto.updatedDate = epochMsToLocalDateTime(updatedMs);
        dto.updatedTimestampMs = updatedMs;

        JsonNode selfLinks = prNode.path("links").path("self");
        if (selfLinks.isArray() && selfLinks.size() > 0) {
            dto.prUrl = selfLinks.get(0).path("href").asText();
        }

        JsonNode descNode = prNode.path("description");
        dto.description = descNode.isMissingNode() || descNode.isNull() ? "" : descNode.asText();

        List<String> reviewers = new ArrayList<>();
        JsonNode reviewerNodes = prNode.path("reviewers");
        if (reviewerNodes.isArray()) {
            for (JsonNode r : reviewerNodes) {
                String displayName = r.path("user").path("displayName").asText();
                if (displayName.isBlank()) continue;
                // Where each reviewer stands, which is what "who approved this" is asked of.
                String verdict = switch (r.path("status").asText("")) {
                    case "APPROVED" -> " (approved)";
                    case "NEEDS_WORK" -> " (needs work)";
                    case "UNAPPROVED" -> " (not approved yet)";
                    default -> "";
                };
                reviewers.add(displayName + verdict);
            }
        }
        dto.reviewers = reviewers;
        dto.comments = new ArrayList<>();

        return dto;
    }

    // The PR's whole history, not its first page. This used to read ONE page of 100 activities and keep
    // only the COMMENTED ones, so on a busy PR the oldest discussion was never indexed, and approvals,
    // merges, declines and reply threads never were at all.
    //
    // Comments are taken from each thread's ROOT activity (commentAction ADDED), whose comment object
    // carries every reply nested inside it as it stands now. A REPLIED or EDITED activity repeats part
    // of a thread already there, so it is skipped rather than rendered twice.
    //
    // A failure keeps whatever was read: the PR itself is known, and losing its description over an
    // activities error would index less than the old code did.
    private static final int MAX_PR_ACTIVITIES = 2000;

    private void fetchPrActivities(BitbucketPrDto dto, String baseUrl, String token, String projectKey, String repoSlug) {
        List<ActivityEntry> events = new ArrayList<>();
        List<CommentEntry> threads = new ArrayList<>();
        try {
            boolean[] truncated = new boolean[1];
            List<JsonNode> activities = pagedValues(baseUrl, token, projectKey, repoSlug,
                    "pull-requests/" + dto.id + "/activities", MAX_PR_ACTIVITIES, "PR activities", truncated);
            dto.activitiesTruncated = truncated[0];
            // Bitbucket lists newest first; a timeline reads oldest first.
            for (int i = activities.size() - 1; i >= 0; i--) {
                JsonNode activity = activities.get(i);
                String action = activity.path("action").asText("");
                if ("COMMENTED".equals(action)) {
                    if ("ADDED".equals(activity.path("commentAction").asText("ADDED"))) {
                        CommentEntry root = parseComment(activity.path("comment"));
                        JsonNode anchor = activity.path("commentAnchor");
                        if (!anchor.isObject()) anchor = activity.path("comment").path("anchor");
                        if (anchor.isObject()) {
                            root.filePath = anchor.path("path").asText(null);
                            if (anchor.path("line").asInt(0) > 0) root.line = anchor.path("line").asInt();
                        }
                        threads.add(root);
                    }
                    continue;
                }
                String described = describeActivity(action, activity);
                if (described == null) continue;
                ActivityEntry entry = new ActivityEntry();
                long ms = activity.path("createdDate").asLong(0);
                if (ms > 0) entry.date = epochMsToLocalDateTime(ms);
                entry.user = displayName(activity.path("user"));
                entry.description = described;
                events.add(entry);
            }
        } catch (Exception e) {
            log.warn("[Bitbucket] Failed to read activities of {}/{} PR-{}: {}", projectKey, repoSlug, dto.id, e.getMessage());
        }
        dto.activities = events;
        dto.comments = threads;
    }

    private CommentEntry parseComment(JsonNode node) {
        CommentEntry entry = new CommentEntry();
        entry.author = displayName(node.path("author"));
        long createdMs = node.path("createdDate").asLong(0);
        if (createdMs > 0) entry.createdDate = epochMsToLocalDateTime(createdMs);
        entry.text = node.path("text").asText();
        for (JsonNode reply : node.path("comments")) {
            entry.replies.add(parseComment(reply));
        }
        return entry;
    }

    // A past-tense phrase per activity type, or null for one that says nothing a reader wants.
    private static String describeActivity(String action, JsonNode activity) {
        return switch (action) {
            case "OPENED" -> "opened the pull request";
            case "APPROVED" -> "approved";
            case "UNAPPROVED" -> "removed their approval";
            case "REVIEWED" -> "marked it as needing work";
            case "MERGED" -> {
                String hash = activity.path("commit").path("displayId").asText("");
                yield hash.isBlank() ? "merged the pull request" : "merged the pull request as " + hash;
            }
            case "DECLINED" -> "declined the pull request";
            case "REOPENED" -> "reopened the pull request";
            case "DELETED" -> "deleted the pull request";
            case "RESCOPED" -> {
                int added = activity.path("added").path("commits").size();
                int removed = activity.path("removed").path("commits").size();
                if (added == 0 && removed == 0) yield "updated the source branch";
                StringBuilder sb = new StringBuilder("updated the source branch (");
                if (added > 0) sb.append(added).append(added == 1 ? " commit added" : " commits added");
                if (added > 0 && removed > 0) sb.append(", ");
                if (removed > 0) sb.append(removed).append(removed == 1 ? " commit removed" : " commits removed");
                yield sb.append(')').toString();
            }
            case "UPDATED" -> {
                String prevTitle = activity.path("previousTitle").asText("");
                String prevTarget = activity.path("previousToRef").path("displayId").asText("");
                if (!prevTarget.isBlank()) yield "changed the target branch from " + prevTarget;
                if (!prevTitle.isBlank()) yield "changed the title from \"" + prevTitle + "\"";
                yield "edited the pull request";
            }
            case "REVIEWERS_UPDATED" -> {
                List<String> added = new ArrayList<>();
                for (JsonNode u : activity.path("addedReviewers")) added.add(displayName(u));
                List<String> removed = new ArrayList<>();
                for (JsonNode u : activity.path("removedReviewers")) removed.add(displayName(u));
                if (added.isEmpty() && removed.isEmpty()) yield null;
                StringBuilder sb = new StringBuilder();
                if (!added.isEmpty()) sb.append("added reviewers ").append(String.join(", ", added));
                if (!added.isEmpty() && !removed.isEmpty()) sb.append("; ");
                if (!removed.isEmpty()) sb.append("removed reviewers ").append(String.join(", ", removed));
                yield sb.toString();
            }
            default -> null;
        };
    }

    // A user node is either the user itself or, on some payloads, a wrapper holding one under "user".
    private static String displayName(JsonNode node) {
        String name = node.path("displayName").asText("");
        if (name.isBlank()) name = node.path("user").path("displayName").asText("");
        if (name.isBlank()) name = node.path("name").asText("");
        return name.isBlank() ? "Unknown" : name;
    }

    // What a PR changed and the commits it carries - the list of PATHS, never their content. Read on
    // the index path only (getPrContent): the getPr tool answers faster without them and has diff
    // tools for the files themselves.
    private static final int MAX_LISTED_CHANGES = 1000;
    private static final int MAX_LISTED_COMMITS = 500;

    private void addPrScope(BitbucketPrDto dto, String baseUrl, String projectKey, String repoSlug) {
        String token = requireConfig(baseUrl);
        try {
            boolean[] truncated = new boolean[1];
            dto.changes = parseChanges(pagedValues(baseUrl, token, projectKey, repoSlug,
                    "pull-requests/" + dto.id + "/changes", MAX_LISTED_CHANGES, "PR changes", truncated));
            dto.changesTruncated = truncated[0];
        } catch (Exception e) {
            log.warn("[Bitbucket] Failed to read changed files of {}/{} PR-{}: {}", projectKey, repoSlug, dto.id, e.getMessage());
        }
        try {
            boolean[] truncated = new boolean[1];
            List<BitbucketCommitDto> commits = new ArrayList<>();
            for (JsonNode c : pagedValues(baseUrl, token, projectKey, repoSlug,
                    "pull-requests/" + dto.id + "/commits", MAX_LISTED_COMMITS, "PR commits", truncated)) {
                commits.add(parseCommitNode(c, baseUrl, projectKey, repoSlug));
            }
            dto.commits = commits;
            dto.commitsTruncated = truncated[0];
        } catch (Exception e) {
            log.warn("[Bitbucket] Failed to read commits of {}/{} PR-{}: {}", projectKey, repoSlug, dto.id, e.getMessage());
        }
    }

    private void addCommitScope(BitbucketCommitDto dto, String baseUrl, String projectKey, String repoSlug) {
        String token = requireConfig(baseUrl);
        try {
            boolean[] truncated = new boolean[1];
            dto.changes = parseChanges(pagedValues(baseUrl, token, projectKey, repoSlug,
                    "commits/" + encodePathSegment(dto.id) + "/changes", MAX_LISTED_CHANGES, "commit changes", truncated));
            dto.changesTruncated = truncated[0];
        } catch (Exception e) {
            log.warn("[Bitbucket] Failed to read changed files of commit {}: {}", dto.id, e.getMessage());
        }
        // Which pull requests brought this commit in. Not on every server version, so its absence is
        // routine - logged at debug, never an error.
        try {
            boolean[] truncated = new boolean[1];
            List<String> prs = new ArrayList<>();
            for (JsonNode pr : pagedValues(baseUrl, token, projectKey, repoSlug,
                    "commits/" + encodePathSegment(dto.id) + "/pull-requests", 50, "commit pull requests", truncated)) {
                prs.add("PR-" + pr.path("id").asInt() + " " + pr.path("title").asText("")
                        + " (" + pr.path("state").asText("") + ")");
            }
            dto.pullRequests = prs;
        } catch (Exception e) {
            log.debug("[Bitbucket] No pull requests listed for commit {}: {}", dto.id, e.getMessage());
        }
    }

    private static List<ChangedFile> parseChanges(List<JsonNode> values) {
        List<ChangedFile> changes = new ArrayList<>();
        for (JsonNode v : values) {
            ChangedFile f = new ChangedFile();
            f.path = v.path("path").path("toString").asText("");
            f.type = v.path("type").asText("MODIFY");
            String src = v.path("srcPath").path("toString").asText("");
            f.srcPath = src.isBlank() || src.equals(f.path) ? null : src;
            changes.add(f);
        }
        return changes;
    }

    private static void appendChanges(StringBuilder sb, List<ChangedFile> changes, boolean truncated) {
        if (changes == null || changes.isEmpty()) return;
        sb.append("\n## Changed Files\n");
        for (ChangedFile f : changes) {
            sb.append("- ").append(f.type).append(' ');
            if (f.srcPath != null) sb.append('`').append(f.srcPath).append("` -> ");
            sb.append('`').append(f.path).append("`\n");
        }
        if (truncated) sb.append("- (list cut at ").append(changes.size()).append(" files)\n");
    }

    private static LocalDateTime epochMsToLocalDateTime(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private String joinUrl(String base, String path) {
        return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    // - Inner value types --------------------------------------------------------

    public static class BitbucketRepoInfo {
        public final String slug;
        public final String name;

        public BitbucketRepoInfo(String slug, String name) {
            this.slug = slug;
            this.name = name;
        }
    }

    public static class BitbucketPrDto {
        public int id;
        public String title;
        public String state;
        public String author;
        public List<String> reviewers;
        public String fromRef;
        public String toRef;
        public LocalDateTime createdDate;
        public LocalDateTime updatedDate;
        // The same instant as updatedDate, unrounded and unzoned - what an item's lastModified is
        // built from. updatedDate stays because it is what the Markdown table renders.
        public long updatedTimestampMs;
        public String prUrl;
        public String description;
        public List<CommentEntry> comments;
        public List<ActivityEntry> activities;
        public boolean activitiesTruncated;
        // Null unless read for indexing - see addPrScope.
        public List<ChangedFile> changes;
        public boolean changesTruncated;
        public List<BitbucketCommitDto> commits;
        public boolean commitsTruncated;
        public String content;
    }

    public static class CommentEntry {
        public String author;
        public LocalDateTime createdDate;
        public String text;
        // Set for an inline comment: the file and line it is attached to. A path, never the code.
        public String filePath;
        public Integer line;
        public List<CommentEntry> replies = new ArrayList<>();
    }

    public static class ActivityEntry {
        public LocalDateTime date;
        public String user;
        public String description;
    }

    public static class ChangedFile {
        public String path;
        public String type;
        public String srcPath;
    }

    public static class PrBatchResult {
        public final List<BitbucketPrDto> prs;
        public final boolean hasMore;
        public final int nextStart;

        public PrBatchResult(List<BitbucketPrDto> prs, boolean hasMore, int nextStart) {
            this.prs = prs;
            this.hasMore = hasMore;
            this.nextStart = nextStart;
        }
    }

    public static class BitbucketCommitDto {
        public String id;
        public String displayId;
        public String message;
        public String authorName;
        public String authorEmail;
        public long authorTimestampMs;
        public String committerName;
        public long committerTimestampMs;
        public List<String> parents = new ArrayList<>();
        public String commitUrl;
        // Null unless read for indexing - see addCommitScope.
        public List<ChangedFile> changes;
        public boolean changesTruncated;
        public List<String> pullRequests;
    }

    public static class BitbucketTagDto {
        public String name;
        public String ref;
        public String commitId;
        public String url;
    }

    public static class TagBatchResult {
        public final List<BitbucketTagDto> tags;
        public final boolean hasMore;
        public final int nextStart;

        public TagBatchResult(List<BitbucketTagDto> tags, boolean hasMore, int nextStart) {
            this.tags = tags;
            this.hasMore = hasMore;
            this.nextStart = nextStart;
        }
    }

    public static class CommitBatchResult {
        public final List<BitbucketCommitDto> commits;
        public final boolean hasMore;
        public final int nextStart;

        public CommitBatchResult(List<BitbucketCommitDto> commits, boolean hasMore, int nextStart) {
            this.commits = commits;
            this.hasMore = hasMore;
            this.nextStart = nextStart;
        }
    }

    /**
     * Escape hatch: any Bitbucket REST endpoint the configured credential is allowed to reach.
     *
     * Exists because the hand-written methods above will never be a complete API - see
     * {@link ai.proxyma.core.connector.RestPassthrough} for why that is the design rather than a
     * gap. Everything about the request except the verb, path and body is fixed here: the host and
     * the credential are this instance's, and the path is validated so they stay that way.
     *
     * @param path path relative to the server root, e.g. /rest/api/1.0/projects
     * @param body JSON request text, or null for verbs that take none
     */
    public Map<String, Object> callApi(String baseUrl, String accessToken, String method,
                                       String path, String body) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("No Bitbucket base URL is configured");
        }
        return RestPassthrough.call(restClient, h -> addAuthHeader(h, accessToken),
                baseUrl, method, path, body);
    }

    // =========================================================================
    //  FILE TREE - reading a repository WITHOUT cloning it
    // =========================================================================

    /**
     * Every file path under {@code path}, recursively, at a ref.
     *
     * GET /rest/api/1.0/projects/{key}/repos/{slug}/files/{path}
     *
     * The point of this and {@link #browsePath} is that neither downloads a repository. A clone of
     * a large monorepo is gigabytes and minutes, while answering "where does retry live" needs a
     * list of paths, which is one paginated call. Cloning stays a separate, explicit operation for
     * the cases that genuinely need a working tree.
     *
     * <p>Bounded by {@code maxEntries} rather than paged to exhaustion: this feeds an agent's
     * context, and a repository with 200k files would fill it with a directory listing and leave
     * no room for the answer. The result says when it was cut rather than looking complete.
     */
    public Map<String, Object> listFiles(String baseUrl, String accessToken, String projectKey,
                                         String repoSlug, String path, String at, int maxEntries) {
        String token = requireExplicitToken(accessToken);
        List<String> files = new ArrayList<>();
        int start = 0;
        boolean truncated = false;
        boolean hasMore = true;
        String suffix = encodedPathSuffix(path);
        while (hasMore) {
            String url = repoApi(baseUrl, projectKey, repoSlug,
                    "files" + suffix + "?start=" + start + "&limit=1000" + atParam(at));
            JsonNode root = getJsonTree(url, token, "listFiles");
            for (JsonNode v : root.path("values")) {
                if (files.size() >= maxEntries) {
                    truncated = true;
                    break;
                }
                files.add(v.asText());
            }
            if (truncated) break;
            hasMore = !root.path("isLastPage").asBoolean(true);
            start = root.path("nextPageStart").asInt(start + 1000);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        result.put("count", files.size());
        if (truncated) result.put("truncated", true);
        return result;
    }

    /**
     * One directory level, or one file's content.
     *
     * GET /rest/api/1.0/projects/{key}/repos/{slug}/browse/{path}
     *
     * Bitbucket answers this single endpoint two different ways depending on what the path names.
     * Both shapes are normalized here so a caller never branches on undocumented nesting: a
     * directory yields type DIRECTORY with entries, a file yields type FILE with its text rejoined
     * from the per-line array the API actually returns.
     */
    public Map<String, Object> browsePath(String baseUrl, String accessToken, String projectKey,
                                          String repoSlug, String path, String at, int maxLines) {
        String token = requireExplicitToken(accessToken);
        String url = repoApi(baseUrl, projectKey, repoSlug,
                "browse" + encodedPathSuffix(path) + "?limit=1000" + atParam(at));
        JsonNode root = getJsonTree(url, token, "browsePath");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path == null ? "" : path);

        JsonNode children = root.path("children").path("values");
        if (children.isArray()) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (JsonNode c : children) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", c.path("path").path("name").asText());
                entry.put("path", c.path("path").path("toString").asText());
                entry.put("type", c.path("type").asText("FILE"));
                if (c.has("size")) entry.put("size", c.path("size").asLong());
                entries.add(entry);
            }
            result.put("type", "DIRECTORY");
            result.put("entries", entries);
            result.put("count", entries.size());
            if (!root.path("children").path("isLastPage").asBoolean(true)) {
                result.put("truncated", true);
            }
            return result;
        }

        JsonNode lines = root.path("lines");
        if (lines.isArray()) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (JsonNode line : lines) {
                if (n >= maxLines) break;
                sb.append(line.path("text").asText()).append(NEWLINE);
                n++;
            }
            result.put("type", "FILE");
            result.put("content", sb.toString());
            result.put("lines", n);
            if (n < lines.size() || !root.path("isLastPage").asBoolean(true)) {
                result.put("truncated", true);
            }
            return result;
        }

        // Neither shape. Said out loud rather than returned as an empty object, because "no
        // children and no lines" is a real answer - a binary file, or a path absent at this ref.
        result.put("type", "UNKNOWN");
        result.put("note", "No directory children and no text lines - the path may name a binary "
                + "file, or may not exist at this ref.");
        return result;
    }

    // =========================================================================
    //  SNAPSHOT - the whole repository on disk, without git
    // =========================================================================

    /**
     * Downloads the repository at a ref and unpacks it into {@code destination}.
     *
     * <p>GET /rest/api/1.0/projects/{key}/repos/{slug}/archive?format=zip
     *
     * <p><b>This is a snapshot, not a clone.</b> It is the working tree at one ref with no .git
     * directory, no history and no ability to commit - and that is deliberate. A real clone needs
     * either a {@code git} binary on PATH, which is the fragility the SVN connector already suffers
     * from, or JGit, which is a heavyweight dependency to add for the benefit of an assistant that
     * reads code rather than committing it. The archive endpoint needs neither and is one HTTP GET.
     * If history is ever genuinely needed, that is a different feature, not a flag on this one.
     *
     * <p>Three bounds, because an archive is attacker-shaped even when the server is trusted: a
     * total byte cap, an entry-count cap, and the traversal check on every entry. The traversal one
     * matters most - a zip entry named {@code ../../etc/x} escapes the destination, which is the
     * same class of bug as SEC-23, and this repo has paid for it once already.
     */
    public Map<String, Object> downloadSnapshot(String baseUrl, String accessToken, String projectKey,
                                                String repoSlug, String at, String subPath,
                                                Path destination, long maxBytes, int maxEntries)
            throws IOException {
        String token = requireExplicitToken(accessToken);
        StringBuilder path = new StringBuilder(String.format(
                "rest/api/1.0/projects/%s/repos/%s/archive?format=zip", projectKey, repoSlug));
        path.append(atParam(at));
        if (subPath != null && !subPath.isBlank()) {
            path.append("&path=").append(
                    java.net.URLEncoder.encode(trimSlashes(subPath), StandardCharsets.UTF_8));
        }
        String url = joinUrl(baseUrl, path.toString());

        // Unpacked beside the destination and swapped in only once complete: the old snapshot used to
        // be deleted before the request was even authenticated, and a refusal, a cap overrun or a
        // dropped connection then left nothing, or half a tree, where a good snapshot had been.
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, destination.getFileName() + ".part-");
        log.info("[Bitbucket] Downloading snapshot of {}/{} at {} into {}",
                projectKey, repoSlug, at == null ? "(default branch)" : at, destination);

        long[] totals = new long[2];
        boolean done = false;
        try (InputStream in = restClient.get()
                .uri(URI.create(url))
                .headers(h -> addAuthHeader(h, token))
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .onStatus(status -> status.value() == 401,
                        (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                .onStatus(status -> status.value() == 403,
                        (req, resp) -> { throw new IllegalStateException(noAccess(projectKey, repoSlug)); })
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, resp) -> {
                            throw new IllegalStateException(
                                    "Bitbucket archive request failed: " + resp.getStatusCode().value()
                                            + " " + resp.getStatusText());
                        })
                .body(InputStream.class)) {
            if (in == null) throw new IOException("Bitbucket returned an empty archive response");
            totals = extractZipBounded(in, staging, maxBytes, maxEntries);
            deleteTree(destination);
            Files.move(staging, destination);
            done = true;
        } finally {
            if (!done) deleteTree(staging);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("directory", destination.toString());
        result.put("files", totals[1]);
        result.put("bytes", totals[0]);
        return result;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Streams a zip into {@code destDir}, refusing to write outside it and stopping at the caps.
     *
     * @return {@code [bytesWritten, entriesWritten]}
     */
    static long[] extractZipBounded(InputStream in, Path destDir, long maxBytes, int maxEntries)
            throws IOException {
        Path root = destDir.toAbsolutePath().normalize();
        long bytes = 0;
        int files = 0;
        byte[] buffer = new byte[8192];
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                // The guard. An entry may name anything at all, including ../ segments and absolute
                // paths; only ones that stay under root are written, and the rest are skipped
                // rather than sanitized, because a name this cannot place is one to distrust.
                if (!target.startsWith(root)) {
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    zis.closeEntry();
                    continue;
                }
                if (files >= maxEntries) {
                    throw new IOException("Repository archive holds more than " + maxEntries
                            + " files - narrow it with the path argument.");
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    int read;
                    while ((read = zis.read(buffer)) > 0) {
                        bytes += read;
                        // Checked mid-stream, not from the entry's declared size: a zip header can
                        // claim any size it likes, so the only honest bound is what has actually
                        // been written.
                        if (bytes > maxBytes) {
                            throw new IOException("Repository archive exceeds " + (maxBytes / 1_048_576)
                                    + " MB - narrow it with the path argument, or raise maxMegabytes.");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                files++;
                zis.closeEntry();
            }
        }
        return new long[] { bytes, files };
    }

    private static final String NEWLINE = String.valueOf((char) 10);

    private static String trimSlashes(String p) {
        String t = p.trim();
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String atParam(String at) {
        if (at == null || at.isBlank()) return "";
        return "&at=" + java.net.URLEncoder.encode(at.trim(), StandardCharsets.UTF_8);
    }

    // A repository path as URL path segments, each encoded on its own: "/" stays a separator, while
    // a space, '#', '?', '{' or '%' in a name is data. Concatenated raw, '#' cut the request at a
    // fragment (a C# folder listed its parent, its query dropped) and braces were read as template
    // variables ("Not enough variable values available to expand").
    private static String encodedPathSuffix(String path) {
        if (path == null || path.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String segment : trimSlashes(path).split("/")) {
            // "." names nothing; ".." is refused by DotSegmentGuard when the request is sent.
            if (segment.isEmpty() || segment.equals(".")) continue;
            out.append('/').append(encodePathSegment(segment));
        }
        return out.toString();
    }

    // A repository-scoped REST path. `rest` may carry a query string whose values are ALREADY encoded;
    // blank means the repository resource itself.
    private String repoApi(String baseUrl, String projectKey, String repoSlug, String rest) {
        String repo = String.format("rest/api/1.0/projects/%s/repos/%s", encodePathSegment(projectKey), encodePathSegment(repoSlug));
        return joinUrl(baseUrl, rest == null || rest.isBlank() ? repo : repo + "/" + rest);
    }

    // The sync-side JSON GET: this source's own token, a pre-encoded URL (see encodedUri - a branch
    // name with a slash is the ordinary case here, not an edge), and optionally null for a 404, which
    // for a ref or a repository means "gone" rather than "broken". The 404 handler is registered first
    // because RestClient applies the first matching one.
    private JsonNode getJsonAt(String alreadyEncodedUrl, String what, boolean nullOn404) {
        return getJsonAt(alreadyEncodedUrl, requireConfig(alreadyEncodedUrl), what, nullOn404);
    }

    // Explicit-token form, for what the tool path shares with sync - PR activities.
    private JsonNode getJsonAt(String alreadyEncodedUrl, String token, String what, boolean nullOn404) {
        try {
            String body = restClient.get()
                    .uri(encodedUri(alreadyEncodedUrl))
                    .headers(h -> addAuthHeader(h, token))
                    .retrieve()
                    .onStatus(status -> status.value() == 404,
                            (req, resp) -> { throw HttpClientErrorException.create(resp.getStatusCode(),
                                    resp.getStatusText(), resp.getHeaders(), new byte[0], StandardCharsets.UTF_8); })
                    .onStatus(status -> status.value() == 401,
                            (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                    .onStatus(status -> status.value() == 403,
                            (req, resp) -> { throw new IllegalStateException(noAccess(null, null)); })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> { throw new IllegalStateException("Bitbucket API error (" + what + "): "
                                    + resp.getStatusCode().value() + " " + resp.getStatusText()); })
                    .body(String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (HttpClientErrorException.NotFound e) {
            if (nullOn404) return null;
            throw new IllegalStateException("Bitbucket API error (" + what + "): 404 Not Found", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket " + what + " failed: " + SyncErrorAware.summarize(e), e);
        }
    }

    // Every value of a paginated list, stopping at `max` - these feed one indexed document, and a PR
    // touching 40,000 files must not become a 40,000-line document. truncated[0] says it was cut.
    private List<JsonNode> pagedValues(String baseUrl, String token, String projectKey, String repoSlug,
                                       String listPath, int max, String what, boolean[] truncated) {
        List<JsonNode> values = new ArrayList<>();
        String sep = listPath.contains("?") ? "&" : "?";
        int start = 0;
        while (true) {
            JsonNode root = getJsonAt(repoApi(baseUrl, projectKey, repoSlug,
                    listPath + sep + "start=" + start + "&limit=" + Math.min(max, 500)), token, what, false);
            for (JsonNode v : root.path("values")) {
                if (values.size() >= max) {
                    truncated[0] = true;
                    return values;
                }
                values.add(v);
            }
            if (root.path("isLastPage").asBoolean(true)) return values;
            int next = root.path("nextPageStart").asInt(-1);
            if (next <= start) return values;
            start = next;
        }
    }

    // Same status handling as fetchAllBranchIds above: 401/403 named explicitly, because "check
    // your token" is actionable where "API error 401" is not.
    //
    // The URL arrives ENCODED (repoApi, encodedPathSuffix, atParam) and goes out as a URI. Handed to
    // RestClient as a String it was encoded a second time - "at=feature%2Fx" sent as
    // "at=feature%252Fx" - so every branch with a slash in its name was unknown to Bitbucket.
    private JsonNode getJsonTree(String alreadyEncodedUrl, String token, String what) {
        try {
            String body = restClient.get()
                    .uri(encodedUri(alreadyEncodedUrl))
                    .headers(h -> addAuthHeader(h, token))
                    .retrieve()
                    .onStatus(status -> status.value() == 401,
                            (req, resp) -> { throw new IllegalStateException(TOKEN_REJECTED); })
                    .onStatus(status -> status.value() == 403,
                            (req, resp) -> { throw new IllegalStateException(noAccess(null, null)); })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> {
                                throw new IllegalStateException(
                                        "Bitbucket API error: " + resp.getStatusCode().value()
                                                + " " + resp.getStatusText());
                            })
                    .body(String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Bitbucket " + what + " failed: " + e.getMessage(), e);
        }
    }
}
