package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.common.net.HttpVerb;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import ai.proxyma.core.tools.ToolIds;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.bitbucket.BitbucketAccessDeniedException;
import ai.proxyma.core.connector.bitbucket.BitbucketConnector;
import ai.proxyma.core.connector.spi.AtlassianServer;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.system.util.AppPaths;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
class BitbucketToolService {

    private static final String LABEL = "Bitbucket";
    private static final String PREFIX = "bitbucket";

    // The connection's Read and Write switches are enforced where the connection is resolved, with
    // the access each call is about to use; see JiraToolService.
    private final ToolConnectorResolver resolver;
    private final BitbucketConnector bitbucketConnector;
    private final McpGuardService mcpGuard;
    private final AppPaths appPaths;

    BitbucketToolService(ToolConnectorResolver resolver, BitbucketConnector bitbucketConnector,
                         McpGuardService mcpGuard, AppPaths appPaths) {
        this.mcpGuard = mcpGuard;
        this.resolver = resolver;
        this.bitbucketConnector = bitbucketConnector;
        this.appPaths = appPaths;
    }

    // Base URL / access token resolved as plain local values into the connector's explicit-token
    // overload - no synchronized(bitbucketConnector), no auth() mutation, no registered-repo
    // requirement (see ToolConnectorResolver's class comment). The comment is posted under the
    // calling user's own Bitbucket identity - their own token only, never the connector owner's.
    long addPrComment(String projectKey, String repoSlug, int prId, String text, String path, Integer line,
                      String lineType, Long parentId) {
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.WRITE);
        String url = target.baseUrl();
        String accessToken = target.credential();
        return bitbucketConnector.addPrComment(url, accessToken, projectKey, repoSlug, prId, text, path, line,
                lineType, parentId);
    }

    Map<String, Object> createBranch(String projectKey, String repoSlug, String name, String startPoint) {
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.WRITE);
        String url = target.baseUrl();
        String accessToken = target.credential();
        try {
            return bitbucketConnector.createBranch(url, accessToken, projectKey, repoSlug, name, startPoint);
        } catch (BitbucketAccessDeniedException e) {
            throw writeDenied("Creating a branch", e,
                    "Listing branches needs only read, so that working is no sign the token can create one. "
                    + "Branch permissions apply too: a restriction matching this name refuses it even for a "
                    + "token that carries write permission.");
        }
    }

    void deleteBranch(String projectKey, String repoSlug, String name) {
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.WRITE);
        String url = target.baseUrl();
        String accessToken = target.credential();
        try {
            bitbucketConnector.deleteBranch(url, accessToken, projectKey, repoSlug, name);
        } catch (BitbucketAccessDeniedException e) {
            throw writeDenied("Deleting a branch", e,
                    "A branch permission can forbid deleting a branch - the default branch and release "
                    + "branches usually carry one - even for a token that can write.");
        }
    }

    // One entry per branch: its name (Bitbucket's displayId, e.g. "main", "feature/x") plus any
    // Jira issue key detected in that name. `filter` is applied here rather than server-side
    // because the connector's branch fetch is already fully paginated - a repo's whole branch
    // list is cheap to walk, but flooding the model's context with several hundred names is not,
    // hence the caller-supplied narrowing (which doubles as "find the branch for MYPROJ-123").
    List<Map<String, Object>> listBranches(String projectKey, String repoSlug, String filter) {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.READ);
        String url = target.baseUrl();
        String accessToken = target.credential();
        List<String> branches = bitbucketConnector.fetchAllBranchIds(url, accessToken, projectKey, repoSlug);
        if (filter != null && !filter.isBlank()) {
            String needle = filter.toLowerCase(Locale.ROOT);
            branches = branches.stream()
                    .filter(b -> b.toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        }
        return branches.stream().map(b -> withJiraKeys("name", b, b)).toList();
    }

    // Newest-first commit history for one branch (blank branch = the repo's default branch).
    // Single batch only: `limit` is exactly what the caller asked for, so "the last 10 commits"
    // is one API call, not a walk.
    List<Map<String, Object>> listCommits(String projectKey, String repoSlug, String branch,
                                          int limit) {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.READ);
        String url = target.baseUrl();
        String accessToken = target.credential();
        var batch = bitbucketConnector.fetchCommitsBatch(
                url, accessToken, projectKey, repoSlug, branch, null, 0, limit);

        List<Map<String, Object>> commits = new ArrayList<>();
        for (BitbucketConnector.BitbucketCommitDto dto : batch.commits) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("hash", dto.displayId != null && !dto.displayId.isBlank() ? dto.displayId : dto.id);
            entry.put("fullHash", dto.id);
            entry.put("author", dto.authorName);
            if (dto.authorEmail != null && !dto.authorEmail.isBlank()) {
                entry.put("authorEmail", dto.authorEmail);
            }
            if (dto.authorTimestampMs > 0) {
                entry.put("date", Instant.ofEpochMilli(dto.authorTimestampMs).toString());
            }
            entry.put("message", dto.message != null ? dto.message : "");
            entry.put("url", dto.commitUrl != null ? dto.commitUrl : "");
            putJiraKeys(entry, dto.message);
            commits.add(entry);
        }
        return commits;
    }

    // Full PR detail rendered as markdown (description + comments), matching the shape
    // JiraToolService.getJiraIssue() returns.
    Map<String, Object> getPr(String projectKey, String repoSlug, int prId) {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.READ);
        String url = target.baseUrl();
        String accessToken = target.credential();
        BitbucketConnector.BitbucketPrDto dto =
                bitbucketConnector.fetchPrDetails(url, accessToken, projectKey, repoSlug, prId);
        if (dto == null) {
            throw new IllegalArgumentException(
                    "Pull request not found: " + projectKey + "/" + repoSlug + " PR-" + prId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", dto.prUrl != null ? dto.prUrl : url);
        result.put("fromBranch", dto.fromRef);
        result.put("toBranch", dto.toRef);
        result.put("state", dto.state);
        result.put("content", dto.content != null ? dto.content : bitbucketConnector.prToMarkdown(dto));
        // Title and source branch both carry the key by convention; the description is skipped
        // because a PR body quoting logs or error text produces far more false positives.
        putJiraKeys(result, dto.title + " " + dto.fromRef);
        return result;
    }

    Map<String, Object> createPr(String projectKey, String repoSlug, String title, String description,
                                 String fromBranch, String toBranch, List<String> reviewers) {
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.WRITE);
        String url = target.baseUrl();
        String accessToken = target.credential();
        var dto = bitbucketConnector.createPr(url, accessToken, projectKey, repoSlug,
                title, description, fromBranch, toBranch, reviewers);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prId", dto.id);
        result.put("title", dto.title);
        result.put("state", dto.state);
        result.put("fromBranch", dto.fromRef);
        result.put("toBranch", dto.toRef);
        result.put("url", dto.prUrl != null ? dto.prUrl : url);
        return result;
    }

    // Bitbucket's own vocabulary, kept verbatim so the tool description and the API agree:
    // APPROVED, NEEDS_WORK ("request changes"), UNAPPROVED (withdraw a previous review).
    private static final Set<String> REVIEW_STATUSES = Set.of("APPROVED", "NEEDS_WORK", "UNAPPROVED");

    String reviewPr(String projectKey, String repoSlug, int prId, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!REVIEW_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be one of APPROVED, NEEDS_WORK or UNAPPROVED "
                    + "(got: " + status + ")");
        }
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.WRITE);
        String url = target.baseUrl();
        String accessToken = target.credential();
        return bitbucketConnector.reviewPr(url, accessToken, projectKey, repoSlug, prId, normalized);
    }

    String mergePr(String projectKey, String repoSlug, int prId, String message, String strategy) {
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.WRITE);
        String url = target.baseUrl();
        String accessToken = target.credential();
        try {
            return bitbucketConnector.mergePr(url, accessToken, projectKey, repoSlug, prId, message, strategy);
        } catch (BitbucketAccessDeniedException e) {
            throw writeDenied("Merging a pull request", e,
                    "Approving that same pull request needs only read, so an approval going through is "
                    + "no sign the token can merge.");
        }
    }

    List<Map<String, Object>> listTags(String projectKey, String repoSlug, String filter, int limit) {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.READ);
        String url = target.baseUrl();
        String accessToken = target.credential();
        var batch = bitbucketConnector.fetchTagsBatch(url, accessToken, projectKey, repoSlug, filter, 0, limit);
        return batch.tags.stream().map(BitbucketToolService::tagToMap).toList();
    }

    Map<String, Object> createTag(String projectKey, String repoSlug, String name, String startPoint,
                                  String message) {
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.WRITE);
        String url = target.baseUrl();
        String accessToken = target.credential();
        try {
            return tagToMap(bitbucketConnector.createTag(url, accessToken, projectKey, repoSlug,
                    name, startPoint, message));
        } catch (BitbucketAccessDeniedException e) {
            throw writeDenied("Creating a tag", e,
                    "Listing tags and reading commits in the same repository need only read, so those "
                    + "working is no sign the token can create one. A tag is also a ref, so the "
                    + "repository's branch permissions apply to it: a restriction matching this tag's "
                    + "name refuses it even for a token that does carry write permission.");
        }
    }

    // What a 401/403 on a write has to say, and why it is not "check your access token". Bitbucket
    // answers "unknown token" and "this token may not do that" with the same sentence, so a merge
    // refused for want of WRITE permission read exactly like a bad credential - and the user, whose
    // token had just read the PR successfully, had nothing to act on.
    //
    // The cause is deliberately NOT chained: its message is already quoted at the front of this one,
    // and Failures.describe() would otherwise print it twice. It is logged instead, at debug, by the
    // connector that threw it.
    //
    // Bitbucket's own: the other services' writes have no equivalent refusal to explain.
    private static IllegalStateException writeDenied(String operation, BitbucketAccessDeniedException cause,
                                                     String note) {
        String settingsLink = "[Settings > Connections > " + LABEL + "](app://settings/connectors/" + PREFIX + ")";
        return new IllegalStateException(cause.getMessage() + " " + operation + " writes to the server, "
                + "so it needs an access token that carries WRITE permission - reading and searching need "
                + "only read, so those having worked says nothing about this one. " + note
                + " Tell the user to create a new " + LABEL + " personal access token WITH write permission "
                + "and save it in " + settingsLink + ", reproducing that markdown link exactly as written "
                + "as a clickable link. If their token already has write permission, then the account "
                + "itself lacks write access to this repository, project or branch and only an "
                + "administrator there can grant it - say so rather than suggesting another token. Either "
                + "way, do not retry until the user confirms something has changed.");
    }

    private static Map<String, Object> tagToMap(BitbucketConnector.BitbucketTagDto dto) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", dto.name);
        entry.put("ref", dto.ref);
        entry.put("commitId", dto.commitId);
        entry.put("url", dto.url);
        return entry;
    }

    // Total patch lines returned across all files in one call. The chat agent would survive a
    // bigger number - ChatService.offloadIfLarge() spills anything over ~8000 tokens to a file it
    // can grep - but that offload is deliberately chat-only: a direct MCP caller (an IDE hitting
    // /mcp) gets the tool's raw result, so an unbounded diff would land in its context whole.
    // Hence the budget lives here, in the tool, rather than being left to the caller.
    private static final int MAX_PATCH_LINES = 1500;
    // One file the caller named with `path` - see renderDiff.
    private static final int MAX_SCOPED_PATCH_LINES = 6000;

    Map<String, Object> prDiff(String projectKey, String repoSlug, int prId, String path,
                               int contextLines) {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.READ);
        String url = target.baseUrl();
        String accessToken = target.credential();
        return renderDiff(bitbucketConnector.fetchPrDiff(
                url, accessToken, projectKey, repoSlug, prId, path, contextLines), path);
    }

    Map<String, Object> commitDiff(String projectKey, String repoSlug, String commitId, String path,
                                   int contextLines) {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.READ);
        String url = target.baseUrl();
        String accessToken = target.credential();
        return renderDiff(bitbucketConnector.fetchCommitDiff(
                url, accessToken, projectKey, repoSlug, commitId, path, contextLines), path);
    }

    // The per-file summary is ALWAYS complete even when the patches are not: "which files did this
    // touch" is both the cheapest and the most commonly needed answer, and a review that silently
    // saw only some of the changed files would be worse than useless. Patches then fill the line
    // budget in order, and anything left over is named in omittedFiles so the agent knows exactly
    // what it has not seen and can re-request those by path.
    private static Map<String, Object> renderDiff(BitbucketConnector.DiffResult diff, String requestedPath) {
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        StringBuilder patch = new StringBuilder();
        // A FILE ASKED FOR BY PATH GETS A LARGER BUDGET, since 2026-09-29. The hint below sends the
        // agent back with `path` for an omitted file - and a file bigger than MAX_PATCH_LINES was then
        // omitted again, on its own, so the one way to see it led nowhere. Asked for by name, it is
        // what the caller wants in context; past even this, it is cut and the cut is said.
        boolean scoped = requestedPath != null && !requestedPath.isBlank();
        int budget = scoped ? MAX_SCOPED_PATCH_LINES : MAX_PATCH_LINES;
        String cutFile = null;
        int cutTotal = 0;
        int totalAdded = 0;
        int totalRemoved = 0;

        for (BitbucketConnector.FileDiff f : diff.files) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", f.path);
            if (f.oldPath != null && !f.oldPath.equals(f.path)) entry.put("oldPath", f.oldPath);
            entry.put("changeType", f.changeType);
            entry.put("linesAdded", f.added);
            entry.put("linesRemoved", f.removed);
            if (f.binaryOrNoContent) entry.put("binaryOrNoTextContent", true);
            files.add(entry);
            totalAdded += f.added;
            totalRemoved += f.removed;

            if (f.binaryOrNoContent || f.patch.isEmpty()) continue;
            int lines = (int) f.patch.lines().count();
            if (lines > budget && scoped && cutFile == null && budget > 0) {
                patch.append("diff --git a/").append(f.oldPath != null ? f.oldPath : f.path)
                     .append(" b/").append(f.path).append('\n')
                     .append(String.join("\n", f.patch.lines().limit(budget).toList())).append('\n');
                cutFile = f.path;
                cutTotal = lines;
                budget = 0;
                continue;
            }
            if (lines > budget) {
                omitted.add(f.path);
                continue;
            }
            patch.append("diff --git a/").append(f.oldPath != null ? f.oldPath : f.path)
                 .append(" b/").append(f.path).append('\n')
                 .append(f.patch);
            budget -= lines;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filesChanged", files.size());
        result.put("linesAdded", totalAdded);
        result.put("linesRemoved", totalRemoved);
        result.put("files", files);
        result.put("diff", patch.toString());
        if (!omitted.isEmpty()) {
            result.put("omittedFiles", omitted);
            result.put("hint", "The diff above is missing " + omitted.size() + " file(s) that did not fit "
                    + "the size budget - they are listed in omittedFiles. Re-call this tool with the path "
                    + "parameter set to one of them to see that file's changes on its own.");
        }
        if (cutFile != null) {
            result.put("patchTruncated", cutFile);
            result.put("patchTruncatedNote", "The patch for " + cutFile + " is " + cutTotal + " lines; the first "
                    + MAX_SCOPED_PATCH_LINES + " are shown. Lower contextLines to fit more of the change.");
        }
        if (diff.serverTruncated) {
            result.put("serverTruncated", true);
            result.put("serverTruncatedNote", "Bitbucket itself truncated this diff because it exceeded the "
                    + "server's own limit - even the per-file summary may be incomplete. Request individual "
                    + "files by path to inspect them reliably.");
        }
        if (requestedPath != null && !requestedPath.isBlank()) {
            result.put("scopedToPath", requestedPath);
        }
        return result;
    }

    // Jira issue keys are detected from naming convention only (see
    // BitbucketConnector.extractJiraIssueKeys) - omitted entirely when nothing matched, so the
    // model never sees an empty array it might read as "confirmed: no linked issue".
    private static Map<String, Object> withJiraKeys(String key, String value, String textToScan) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(key, value);
        putJiraKeys(entry, textToScan);
        return entry;
    }

    private static void putJiraKeys(Map<String, Object> entry, String textToScan) {
        List<String> keys = BitbucketConnector.extractJiraIssueKeys(textToScan);
        if (!keys.isEmpty()) {
            entry.put("jiraIssueKeys", keys);
        }
    }

    // A Bitbucket SOURCE is one repository, and registration stores its name as "PROJECT/repo"
    // (see ConnectorService.registerBitbucketRepo) - so that pair decides which connection a call
    // uses. Every tool here carries both, which is why Bitbucket resolves per source more
    // completely than Confluence does.
    //
    // ONE RESOLUTION PER CALL (see JiraToolService.on), refused for a write unless the connection's
    // Write switch is on - and the key and slug checked for their shape first (ToolIds), since both
    // go into REST paths. The server is its root: an address pasted from a repository's page is cut
    // before that page, keeping the path the server is installed under.
    private ToolConnectorResolver.Target target(String projectKey, String repoSlug, Access access) {
        if (projectKey != null) ToolIds.key("project key", projectKey);
        if (repoSlug != null) ToolIds.slug(repoSlug);
        ToolConnectorResolver.Target target =
                resolver.target(ConnectorType.BITBUCKET, LABEL, sourceKey(projectKey, repoSlug), access);
        return new ToolConnectorResolver.Target(AtlassianServer.bitbucketRoot(target.baseUrl()), target.credential(),
                target.authKind());
    }

    // Must match what registration wrote, exactly - a mismatch here does not fail, it silently
    // resolves to the caller's default and the override looks as though it was never saved.
    private static String sourceKey(String projectKey, String repoSlug) {
        if (projectKey == null || repoSlug == null) return null;
        return projectKey + "/" + repoSlug;
    }

    Map<String, Object> callBitbucketApi(String method, String path, String body) {
        // The one call here with no repository in hand - a raw API call names a path, not a repo -
        // so it resolves to the caller's own server.
        // Only GET is a read - the rule CallBitbucketApiTool and WriteToolRegistry.isWriteCall apply.
        ToolConnectorResolver.Target target = target(null, null, HttpVerb.isRead(method) ? Access.READ : Access.WRITE);
        return bitbucketConnector.callApi(target.baseUrl(), target.credential(), method, path, body);
    }

    // Both of these read the repository over the API rather than cloning it - see
    // BitbucketConnector.listFiles for why that is the default and cloning is the exception.
    Map<String, Object> listFiles(String projectKey, String repoSlug, String path, String at,
                                  int maxEntries) {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.READ);
        return bitbucketConnector.listFiles(target.baseUrl(), target.credential(), projectKey, repoSlug, path, at, maxEntries);
    }

    Map<String, Object> browsePath(String projectKey, String repoSlug, String path, String at,
                                   int maxLines) {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.READ);
        return bitbucketConnector.browsePath(target.baseUrl(), target.credential(), projectKey, repoSlug, path, at, maxLines);
    }

    /**
     * Downloads the repository at a ref into its own directory under the data root.
     *
     * <p>One directory per project/repo/ref, so asking for main and a release branch gives two
     * snapshots rather than one overwriting the other, and asking for the same one twice replaces
     * it in place instead of accumulating. The ref segment is the reason: two snapshots of the
     * same repo that differ only by branch are the common case when comparing them, which is most
     * of why anyone wants the files locally at all.
     *
     * <p>A READ operation, guarded as one. It writes to local disk, but nothing on the Bitbucket
     * side changes - the write permission gates acting on the server, not using the answer.
     */
    Map<String, Object> downloadSnapshot(String projectKey, String repoSlug, String at,
                                         String subPath, int maxMegabytes) throws Exception {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        // The connection FIRST: a caller with no Bitbucket connection, or with Read off, is refused
        // before anything on disk is touched. It deleted the snapshot and only then resolved.
        ToolConnectorResolver.Target target = target(projectKey, repoSlug, Access.READ);
        Path destination = snapshotDir(projectKey, repoSlug, at);
        // Replaced rather than merged. Unpacking a new archive over an old tree would leave files
        // deleted since the last snapshot sitting there looking current, which is worse than a
        // slower download - somebody would grep the directory and find code that no longer exists.
        // The connector swaps the new tree in only once it is complete; deleting it here first left
        // nothing behind a refused or failed download, before the caller had been authenticated.
        Map<String, Object> result = new LinkedHashMap<>(bitbucketConnector.downloadSnapshot(
                target.baseUrl(), target.credential(), projectKey, repoSlug, at,
                subPath, destination, (long) maxMegabytes * 1_048_576L, MAX_SNAPSHOT_FILES));
        result.put("ref", at == null || at.isBlank() ? "(default branch)" : at);
        return result;
    }

    /** Frees a snapshot. Paired with the download deliberately - see DeleteBitbucketRepoTool. */
    Map<String, Object> deleteSnapshot(String projectKey, String repoSlug, String at)
            throws Exception {
        mcpGuard.checkBitbucketRead(SearchContext.getUserId());
        // Only somebody who could have downloaded it may remove it.
        target(projectKey, repoSlug, Access.READ);
        Path destination = snapshotDir(projectKey, repoSlug, at);
        boolean existed = Files.isDirectory(destination);
        deleteRecursively(destination);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("directory", destination.toString());
        result.put("deleted", existed);
        return result;
    }

    private static final int MAX_SNAPSHOT_FILES = 50_000;

    // Every segment is sanitized to [A-Za-z0-9._-] before it reaches resolve(). A ref is
    // caller-supplied and "../.." is a legal-looking branch name to a string concatenator, so the
    // directory layout must not be assembled from raw input even though the archive extractor
    // jails its own writes underneath it.
    //
    // One directory PER USER, under the user's id (the single local user's is "local"): on a server
    // every user shared one, so anyone could replace or delete another user's snapshot of a
    // repository their own token could not even read. And a short hash of the three raw values, so
    // names stay apart that sanitize alike: "feature/x" and "feature-x" were one directory.
    Path snapshotDir(String projectKey, String repoSlug, String at) {
        String ref = (at == null || at.isBlank()) ? "default" : at;
        String user = SearchContext.getUserId();
        return appPaths.repoSnapshotsDir()
                .resolve(user == null || user.isBlank() ? "local" : safeSegment(user))
                .resolve(safeSegment(projectKey) + "_" + safeSegment(repoSlug) + "_" + safeSegment(ref)
                        + "_" + shortHash(projectKey + "\n" + repoSlug + "\n" + ref));
    }

    private static String shortHash(String raw) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d).substring(0, 8);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static String safeSegment(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "-");
        // Collapse runs of dots BEFORE stripping leading ones. Replacing separators already means
        // the result is a single segment that resolve() cannot walk out of, so ".." surviving here
        // is not itself exploitable - but a sanitizer whose safety depends on what a DIFFERENT line
        // does is one edit away from not being safe, and the edit would look innocent. A single dot
        // is left alone so an ordinary ref like release-1.2.0 stays readable.
        cleaned = cleaned.replaceAll("[.]{2,}", ".");
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        if (cleaned.isBlank()) cleaned = "x";
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
