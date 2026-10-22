package ai.proxyma.core.chat.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

// Seeds the starter Skills the agent uses to discover sources and unlock their tools. Every skill
// below gates a tool group (see ToolSkillGating) - the LLM-facing tool catalog is kept small, and
// each skill's name IS a gating key, so each skill MUST exist for its tools to ever be advertised.
// That is why seeding is per-skill-by-name rather than the old "only if the whole list is empty"
// gate: a missing gating skill means permanently-invisible tools, so a deleted one is re-created.
// Runs on every startup; a skill already present under its name is left untouched, so it never
// fights an admin's edits.
//
// Name is the whole identity - there is no old-name/alias migration. A skill an admin renames has
// become their own (deletable, no "Restore default"; see DefaultSkillRegistry) and the built-in is
// simply re-seeded here under its own name on the next startup, which is also what keeps its tool
// gating working. Renaming a default is therefore a fork, not a rename.
@Component
class DefaultChatSkillSeeder {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatSkillSeeder.class);

    private final ChatSkillService chatSkillService;
    private final ToolSkillGating toolSkillGating;
    private final DefaultSkillRegistry defaultSkills;
    // Microsoft 365 sources exist on Enterprise Server only (ConnectorType.isMicrosoft), and
    // McpToolConfig registers their tools only there - so the skill text must not offer them either.
    private final boolean multiUser;
    // Home Server: single user like Desktop, but it takes uploads instead of pointing at a folder
    // (FileSourceMode), so a `files` source means something different there.
    private final boolean fileUpload;

    DefaultChatSkillSeeder(ChatSkillService chatSkillService, ToolSkillGating toolSkillGating,
                           DefaultSkillRegistry defaultSkills,
                           @org.springframework.beans.factory.annotation.Value("${proxyma.multi-user:false}") boolean multiUser,
                           @org.springframework.beans.factory.annotation.Value("${proxyma.file-upload.enabled:false}") boolean fileUpload) {
        this.chatSkillService = chatSkillService;
        this.toolSkillGating = toolSkillGating;
        this.defaultSkills = defaultSkills;
        this.multiUser = multiUser;
        this.fileUpload = fileUpload;
    }

    @PostConstruct
    void seedIfNeeded() {
        for (SeededSkill seeded : SKILLS) {
            SeededSkill skill = forEdition(seeded);
            try {
                // Registered unconditionally, before (and independently of) seeding: the registry
                // is what "Restore default" reads, so it has to know this skill's original text
                // even on the overwhelmingly common startup where seedIfAbsent does nothing
                // because the skill already exists. It is also what marks these as PRODUCT skills -
                // neither editable nor deletable (see ChatSkillService.canEdit and canDelete).
                defaultSkills.register(skill.name(), skill.shortDescription(), skill.detail(),
                        DefaultSkillRegistry.Origin.PRODUCT);
                seedIfAbsent(skill);
            } catch (Exception e) {
                // Never block startup over one skill's seed data - an admin can always add it manually.
                log.warn("[ChatSkill] Failed to seed '{}': {}", skill.name(), e.getMessage());
            }
        }
    }

    // Idempotent: absent -> create; present -> bring its text up to this build's (see
    // refreshText) and backfill its tools.
    private void seedIfAbsent(SeededSkill skill) {
        // Tool names come from ToolSkillGating's map rather than being restated here, so the skill
        // file and the gating map can never disagree about what a skill unlocks.
        List<String> tools = List.copyOf(toolSkillGating.toolNamesForGroup(skill.name()));

        if (chatSkillService.readDetailByName(skill.name()).isPresent()) {
            refreshText(skill);
            backfillTools(skill.name(), tools);
            return;
        }
        chatSkillService.createOfficial(skill.name(), skill.shortDescription(), skill.detail(), tools);
        log.info("[ChatSkill] Seeded {} ({} tool(s))", skill.name(), tools.size());
    }

    // An installed product skill takes this build's text. It used to be left alone once seeded, so an
    // improved body reached fresh installs only - found 2026-09-26, when the skills were cut to half
    // their size and no existing installation would ever have sent the shorter text. Safe because
    // nobody can have edited one: product skills refuse edits (ChatSkillService.canEdit) and the
    // Skills UI offers none. `official` keeps a user's own skill that merely shares a built-in's name
    // out of it, the same guard canDelete uses.
    private void refreshText(SeededSkill skill) {
        chatSkillService.list(null, true).stream()
                .filter(s -> s.official() && s.name().equalsIgnoreCase(skill.name()))
                .findFirst()
                .filter(s -> !skill.shortDescription().equals(s.shortDescription())
                        || !chatSkillService.readDetailByName(s.name()).map(skill.detail()::equals).orElse(false))
                .ifPresent(s -> {
                    chatSkillService.updateOfficial(s.id(), s.name(), skill.shortDescription(), skill.detail());
                    log.info("[ChatSkill] Updated '{}' to this build's text", s.name());
                });
    }

    // Fills `tools` on a default skill seeded before default skills carried their own list, so the
    // Skills UI stops showing every one of them with nothing selected. Only ever fills an EMPTY
    // list on an official skill (see backfillOfficialToolsIfEmpty), so an admin's own selection is
    // never overwritten, and it is a no-op on every startup after the first.
    private void backfillTools(String skillName, List<String> tools) {
        if (tools.isEmpty()) return;
        chatSkillService.list(null, true).stream()
                .filter(s -> s.name().equalsIgnoreCase(skillName))
                .findFirst()
                .ifPresent(s -> {
                    if (chatSkillService.backfillOfficialToolsIfEmpty(s.id(), tools)) {
                        log.info("[ChatSkill] Backfilled {} tool(s) onto '{}'", tools.size(), skillName);
                    }
                });
    }

    private record SeededSkill(String name, String shortDescription, String detail) {}

    // THE EDITION'S TEXT, since 2026-09-29. The assistant on a desktop install offered to register
    // a SharePoint site and a Teams channel - read straight off this skill - where neither tool is
    // registered and the service refuses both. Seeded per edition, like the tools themselves.
    //
    // Three variants since the same date: Home Server is single-user like Desktop (no Microsoft
    // 365) but takes uploads like Enterprise Server (FileSourceMode), so keying on multiUser alone
    // told it a `files` source indexes "a local folder" - a path inside its container.
    private SeededSkill forEdition(SeededSkill skill) {
        if (multiUser || !skill.name().equals(CONNECTOR_MANAGEMENT_DESKTOP.name())) return skill;
        return fileUpload ? CONNECTOR_MANAGEMENT_HOME_SERVER : CONNECTOR_MANAGEMENT_DESKTOP;
    }

    private static final SeededSkill CONNECTOR_MANAGEMENT_DESKTOP = singleUserConnectorManagement(
            "Add or remove sources of any type - folders, websites, shares, repositories; save notes to a custom source; browse SVN.",
            "Type `files` is a folder on the user's computer (`path`), its top level unless `recursive`.");

    private static final SeededSkill CONNECTOR_MANAGEMENT_HOME_SERVER = singleUserConnectorManagement(
            "Add or remove sources of any type - uploads, websites, shares, repositories; save notes to a custom source; browse SVN.",
            "Type `files` is a folder of files the user uploaded on the Sources page; removing it deletes them.");

    private static SeededSkill singleUserConnectorManagement(String shortDescription, String filesEntry) {
        return new SeededSkill(
            "Connector Management",
            shortDescription,
            """
            # Connector Management

            Adding a source makes it searchable by `searchIndexedSources`; removing it stops \
            indexing and deletes its search data. Confirm the exact path, URL or key first.

            - `listSourceTypes` lists what can be added here and the fields each type takes; \
              `addSource` adds one (its type and fields); `removeSource` removes one of the user's \
              own sources by its id, which `getConnectorStatus` lists. %s
            - `readSourceItem` reads one item of a source live - the current issue, page or file.
            - `saveToSource` saves a note the user asks to keep - a summary, a decision, meeting \
              notes - as a document of one of their own custom sources (type `custom`), where \
              search finds it. The same `noteId` replaces a note saved before.
            - A source read through a connection uses this conversation's server (see `useServer`), \
              else the user's default connection for its service. A network share \
              (`\\\\server\\share\\folder`) needs the connection for its own file server. Without \
              one, say so and point to Settings > Connections. On failure relay the reason - never \
              retry with a different server name.
            - `listSvnFiles` / `readSvnFile` browse SVN live, without a checkout and without \
              registering anything - registering is for search. They reach the SVN server set in \
              Settings: pass a path below it or a full URL on it (`recursive=true` is slow on a \
              large path). Both need the user's own saved SVN credential.
            - `addBitbucketPrComment` needs no registration.
            - SharePoint, Teams and other Microsoft 365 sources are Proxyma Enterprise Server \
              only; say so if asked.
            """.formatted(filesEntry));
    }

    // Bodies carry only what the system prompt (SystemPromptService.DEFAULT and DELEGATION) and the
    // tools' own descriptions do not: cross-tool workflow, ordering, pitfalls and product facts.
    // Citation, write-confirmation, missing-token, app:// link and delegation rules live in the
    // prompt - a body is re-sent every turn after readSkill, so repeating them is paid for twice.
    private static final List<SeededSkill> SKILLS = List.of(
        new SeededSkill(
            "Search",
            "How to search the indexed sources well and judge what the hits actually say.",
            """
            # Search

            - `searchIndexedSources` returns passages ranked by similarity, not answers. Read \
              enough of each hit to know what it actually says.
            - Try more than one wording. When sources disagree, prefer the more specific and more \
              recent one, and say that they disagree.
            - Found nothing, or only weak evidence: say so. "The indexed sources don't cover this" \
              beats a confident answer built on a thin match.
            - Cite only what you actually read - never a source you only saw named in another \
              document. Keep a sub-agent's sources attached to the claims they support.
            """
        ),
        new SeededSkill(
            "Jira",
            "Live Jira issues: find, read, create, update, transition, link, log work, comment.",
            """
            # Jira

            Live Jira, not the indexed copy: current data, and it can write.

            - An item on a server other than the default (e.g. a link to another host): call \
              `useServer` first; this conversation's tools then use that server.
            ## Finding issues
            - `listJiraProjects` rather than asking the user for a project key.
            - `searchJiraIssues` takes JQL only: scope it with `project = KEY` in the JQL.
            - "My issues": `assignee = currentUser()` (or `reporter = currentUser()`) in the JQL. \
              Ask who the user is only if that comes back empty or wrong.
            - Before concluding, check the issue's links, subtasks and recent comments, and try a \
              broader or reworded JQL.
            - Indexing a project into search is `addSource` (type `jira`), under Connector \
              Management - not for live reads.

            ## Writing
            - `updateJiraIssue`: `labels` REPLACES the whole list. Use `addLabels` / \
              `removeLabels` unless the user said "set the labels to".
            - Any issue type the project has can be created - Epic, Story, Task, Bug, Sub-task or a \
              custom one. `listJiraIssueTypes` lists them; with `issueType` it gives that type's \
              create fields and which are required.
            - Under an Epic: `parentKey` when the Story's fields include `parent`; otherwise set the \
              `Epic Link` custom field in `fields` to the Epic's key. An Epic may require `Epic Name`.
            - Custom fields: `listJiraFields` first - ids differ per server.
            - Fix versions: `listJiraVersions`, and `createJiraVersion` before setting a new one - \
              Jira refuses a version name it does not know.
            - "This will take 2h" is `updateJiraEstimate` (an omitted estimate is left untouched). \
              "I spent 2h" is `logJiraWork`; Jira lowers the remaining estimate itself.
            - `transitionJiraIssue` with no targetStatus lists the available transitions.
            - `linkJiraIssues` with no linkType lists the link types. The INWARD issue is the \
              subject: "PROJ-1 blocks PROJ-2" is inward PROJ-1, outward PROJ-2. Backwards states \
              the opposite on both issues.
            - `deleteJiraIssueLink` takes the id `getJiraIssue` prints as `[link <id>]`.
            - `attachJiraFile` sends a file from this conversation - the user's upload in \
              `attachments/` or something you made in `outputs/`.
            - `callJiraApi` (`/rest/api/2/...`) only for what no named tool covers: watchers, \
              filters, boards, sprints. On a non-2xx, read the error body and fix \
              the request - never resend it unchanged.
            """
        ),
        new SeededSkill(
            "Confluence",
            "Live Confluence pages: find, read, create, update, delete, comment.",
            """
            # Confluence

            Live Confluence, not the indexed copy.

            - An item on a server other than the default (e.g. a link to another host): call \
              `useServer` first; this conversation's tools then use that server.
            - `listConfluenceSpaces` rather than asking the user for a space key. \
              `searchConfluencePages` takes CQL; `getConfluencePage` reads one page by id.
            - Before concluding, check the page's child and linked pages and the other hits.
            - Page and comment bodies are HTML, not Markdown. `createConfluencePage` takes an \
              optional parent page id to nest the new page.
            - `updateConfluenceLabels` adds and removes labels; `attachConfluenceFile` sends a file \
              from this conversation's `attachments/` or `outputs/` and replaces one of the same \
              name; `moveConfluencePage` changes a page's parent; `updateConfluenceComment` and \
              `deleteConfluenceComment` take the comment's id.
            - `callConfluenceApi` (`/rest/api/...`) only for what no named tool covers: \
              restrictions, templates, watchers.
            - Indexing a space into search is `addSource` (type `confluence`), under Connector \
              Management - not for live reads.
            """
        ),
        new SeededSkill(
            "Bitbucket",
            "Live Bitbucket: branches, commits, tags, files, diffs; review, open and merge PRs.",
            """
            # Bitbucket

            Live Bitbucket. Use it for ordering-, branch- and recency-scoped questions ("the last \
            10 commits on main", "what landed since Friday") - `searchIndexedSources` cannot \
            answer those.

            - An item on a server other than the default (e.g. a link to another host): call \
              `useServer` first; this conversation's tools then use that server.
            ## Identifying the repository
            Every tool takes a projectKey and a repoSlug: `.../projects/MYPROJ/repos/my-repo/...` \
            is `MYPROJ` and `my-repo`. Read both from a pasted URL. A repo you cannot map to a \
            project key: ask - never guess a key.

            ## Reading
            - Unsure a branch exists: `listBitbucketBranches` first (with `filter` on big repos).
            - Release and version questions: `listBitbucketTags`.
            - Code: `listBitbucketFiles` to locate a file, `browseBitbucketPath` to read it.
            - A diff with a non-empty `omittedFiles` is not the whole change: fetch those by \
              `path`, or say which files you did not read.
            - `jiraIssueKeys` is parsed from branch names, commit messages and PR titles, not a \
              real Jira link - a hint, so confirm with `getJiraIssue` before saying what the \
              issue is. It is absent, not empty, when nothing matched. For the reverse, pass an \
              issue key as `filter` to `listBitbucketBranches`.
            - Indexing a repository into search is `addSource` (type `bitbucket`), under Connector \
              Management - not for live reads.

            ## Reviewing a pull request
            1. `getBitbucketPr` for intent, then `getBitbucketPrDiff`. Judge the diff, not the \
               description.
            2. Where correctness depends on code the diff does not show, read it; raise concerns \
               only about code you read.
            3. Report to the USER: what you reviewed, what you found, what you could not verify. \
               Cite `path:line` and quote the lines. Separate defects from style, say when code \
               looks correct, and never invent problems.

            "Review this PR" NEVER means posting or approving. `addBitbucketPrComment` and \
            `reviewBitbucketPr` only when explicitly asked - an approval counts towards the \
            repository's merge checks - and never approve code you could not read.

            ## Writing
            - `createBitbucketPr`: confirm both branches exist; `reviewers` are usernames, not \
              display names.
            - `reviewBitbucketPr`: `APPROVED`, `NEEDS_WORK` (request changes) or `UNAPPROVED` \
              (withdraw).
            - `mergeBitbucketPr` publishes code. On a conflict or unmet merge check, relay the \
              real reason; never retry blindly.
            - `createBitbucketTag`: confirm the exact name and target - tags mark releases.
            - `createBitbucketBranch` / `deleteBitbucketBranch`: confirm the name; never delete the \
              default branch or one with an open pull request unless the user says so.
            - `addBitbucketPrComment` comments on a line with `path` and `line` (take both from \
              the diff), and replies with `parentCommentId`.
            - `callBitbucketApi` (`/rest/api/1.0/...`) only where no named tool exists: \
              repository settings, hooks, permissions, build statuses.
            """
        ),
        new SeededSkill(
            "Web",
            "Public web search, and fetching any URL (intranet too), incl. PDFs and page media.",
            """
            # Web

            For sources that are neither indexed nor a connector with its own skill.

            - `webSearch` works with nothing configured; an admin may add a search API key to \
              use that service instead. Do not name the search engine behind it. On \
              `success:false`, relay the error as given - never answer from training data \
              instead.
            - `webFetch` any URL the user gives, intranet and internal wikis included - never \
              refuse or hedge because a URL looks internal. A PDF URL returns its text. Follow \
              the relevant `links` for sub-pages.
            - `webFetch` runs no JavaScript. An empty app shell, a near-blank page, or content a \
              browser would build client-side: retry the SAME url with `renderWebFetch`. It is \
              slower - only after `webFetch`.
            - `truncated:true` is a prefix, not a failure - say so if the answer may lie past \
              the cutoff. On failure, relay the real `error` / `statusCode`.
            - Check several search results and corroborate; never stop at the top one.
            - Say when information comes from the live web rather than indexed sources.

            ## Showing media
            Embed as `![description](mediaUrl)` with the exact `mediaUrl` string a tool result \
            returned - never the original third-party URL, and never one typed by hand or \
            recalled rather than read from a result, however well it fits the pattern. No \
            `media` entry for it: describe it in words, or re-fetch the page.
            """
        ),
        new SeededSkill(
            "Memory & Knowledge",
            "Memory across conversations: recall what you learned, remember facts, search earlier conversations.",
            """
            # Memory & Knowledge

            Memory is not in your prompt; it is looked up on demand. `recall` and \
            `searchConversations` are always available; this skill adds `remember`.

            - `recall` FIRST, before any source is searched, whenever a request needs information - \
              it may rely on an earlier answer, a project detail, or this user's preferences or \
              corrections. Not on trivial messages. Empty means not learned yet. Treat what comes \
              back as prior context: search the sources only for what it leaves out, and verify \
              with `searchIndexedSources` when the answer must be authoritative.
            - `recall` returns entries with an `id`. A cut-off or relevant entry: `recall` with \
              that `id` reads it whole. With no query, `recall` lists the newest entries - use it \
              when the user asks what you remember, or when a search finds nothing you expected.
            - `remember` as soon as you learn something, one self-contained statement per call. \
              `category`: `knowledge` (default - durable facts, resolved answers, project \
              details) or `user` (this user's preferences, role, and corrections - store the \
              correction, not the mistake).
            - To correct an entry, `remember` with its `id` from `recall` and the corrected \
              statement - it replaces that entry, never one saved beside it. To delete one that \
              is wrong or no longer true, `remember` with its `id` and `forget` true. Without an \
              `id`, only a near-identical restatement replaces an old entry. Briefly say what you \
              noted, changed or deleted.
            - Skip details that matter only to the current task, or are obvious from the message.
            - The user can read, edit and delete every entry (Settings > Memory). An entry that \
              is gone was deleted on purpose: do not save it again unless they say it again.

            ## Earlier conversations
            - Memory holds what was saved; `searchConversations` finds what was SAID. Use it when \
              the user refers to an earlier chat ("what did we decide about X", "the query you \
              wrote last week"), or when `recall` finds nothing and it was likely discussed.
            - Search with a few distinctive words; a "quoted phrase" stays together. Then read the \
              best hit with its `conversationId`, and page on with `nextFromMessage`.
            - Link a conversation you used with its `link`, as [its title](link).
            """
        ),
        new SeededSkill(
            "Documents & Files",
            "Read an attached file (PDF, Office, scan) by converting it to Markdown.",
            """
            # Documents & Files

            For attached files not already indexed (indexed content: `searchIndexedSources`; \
            an http(s) URL: `webFetch`). `convertDocument` reads only this conversation's \
            files; on Desktop, bring a file from elsewhere on the computer in first with \
            `openLocalFile` ("Office Files" skill).

            - `convertDocument` before summarizing or quoting - never guess a binary file's \
              contents.
            - A real document comes back offloaded. Call `convertDocument` YOURSELF - it confirms \
              the conversion - then, if `delegate` is available, delegate the reading with the \
              `filePath`, what the document is and every part to cover; if you do not know the \
              parts yet (sheets, sections), have the sub-agent enumerate and cover each. "It is \
              one file" says nothing about how much reading it is.
            - "Summarize this file" means every sheet or section, not the two that looked \
              promising. Read it yourself only for a targeted lookup you can name a search term \
              for.
            """
        ),
        // Distilled from Anthropic's docx/xlsx/pptx skills for this product's one tool: what goes wrong
        // in practice with the three libraries, and how a result reaches the user.
        new SeededSkill(
            "Office Files",
            "Create or edit Word, Excel and PowerPoint files with Python, then hand them over.",
            """
            # Office Files

            `runPython` runs your program in this conversation's workspace: `attachments/` holds \
            the user's uploads (read-only), `outputs/` everything you make. Libraries: \
            python-docx, openpyxl, python-pptx, pandas, matplotlib.

            - Editing an upload: open `attachments/<name>`, save the result as `outputs/<name>` \
              (or a new name). Change what was asked and nothing else.
            - Word: use built-in styles (`Heading 1`, `List Bullet`, `Table Grid`) rather than \
              direct formatting. To keep formatting when editing text, change the runs of a \
              paragraph, not `paragraph.text`.
            - Excel: derived cells are FORMULAS (`=SUM(B2:B9)`), not values you computed. openpyxl \
              saves no computed results, so the in-app preview shows those cells empty until the \
              file is opened in Excel - say so. Set number formats; never load a workbook with \
              `data_only=True` and save it, which deletes every formula.
            - PowerPoint: build on the deck's own layouts and placeholders; keep text short enough \
              to fit. Charts: python-pptx's chart API, or a matplotlib PNG.
            - Charts in Word or Excel: a matplotlib PNG saved to `outputs/`, then inserted.
            - Check before you answer: `convertDocument` on the file's `path` and read it back.
            - Fix a failed run from its stderr; do not resend it unchanged.
            - Hand over every file you made as a link, `[name](url)`, with the url exactly as \
              `runPython` returned it.
            - Desktop only - `openLocalFile` brings in a file the user names by path; \
              `saveLocalFile` writes an output to a path the user asked for. Both ask the user \
              first. Never overwrite without their say-so.
            """
        ),
        new SeededSkill(
            "Connector Management",
            "Add or remove sources of any type - uploads, websites, shares, repositories, Microsoft 365; save notes to a custom source; browse SVN.",
            """
            # Connector Management

            Adding a source makes it searchable by `searchIndexedSources`; removing it stops \
            indexing and deletes its search data. Confirm the exact path, URL or key first.

            - `listSourceTypes` lists what can be added here and the fields each type takes; \
              `addSource` adds one (its type and fields); `removeSource` removes one of the user's \
              own sources by its id, which `getConnectorStatus` lists. Type `files` is a folder of \
              files the user uploaded on the Sources page; removing it deletes them.
            - `readSourceItem` reads one item of a source live - the current issue, page or file.
            - `saveToSource` saves a note the user asks to keep - a summary, a decision, meeting \
              notes - as a document of one of their own custom sources (type `custom`), where \
              search finds it, and so does anyone the source is shared with. The same `noteId` \
              replaces a note saved before.
            - A source read through a connection uses this conversation's server (see `useServer`), \
              else the user's default connection for its service. A network share \
              (`\\\\server\\share\\folder`) needs the connection for its own file server. Without \
              one, say so and point to Settings > Connections. On failure relay the reason - never \
              retry with a different server name.
            - SharePoint and Teams are read as the user's own Microsoft 365 account, connected \
              once in Settings; neither can be shared.
            - `listSvnFiles` / `readSvnFile` browse SVN live, without a checkout and without \
              registering anything - registering is for search. They reach the SVN server set in \
              Settings: pass a path below it or a full URL on it (`recursive=true` is slow on a \
              large path). Both need the user's own saved SVN credential and never fall back to \
              anyone else's.
            - `addBitbucketPrComment` needs no registration.
            """
        ),
        new SeededSkill(
            "Platform Info & Documentation",
            "This platform: what it is, how to use it, FAQ, troubleshooting, version, indexing status.",
            """
            # Platform Info & Documentation

            ## Live facts - never guess these
            - `getPlatformInfo`: product name, version, mode ("desktop", "home-server" or \
              "server"), host OS, \
              build date. Always from the tool, never from memory - they differ per deployment \
              and release. (Today's date is already in your instructions.)
            - `getConnectorStatus`: what is indexed, document counts, and whether indexing is \
              running or failed - what `searchIndexedSources` can search, and why something is \
              not found yet.

            ## What this platform does
            Semantic search and AI chat over the user's own sources: local files, network \
            shares, web pages, Jira, Confluence, Bitbucket and SVN. Documents are chunked, embedded and searched \
            with hybrid (keyword + semantic) search; this agent answers with citations; the same \
            search is exposed as a REST API and an MCP endpoint.

            ## Modes differ - check `mode` first
            - **desktop**: one local user, no sign-in, no sharing, no API tokens.
            - **home-server**: one user, reached in a browser; files are uploaded on the Sources \
              page rather than picked from a folder.
            - **server**: multi-user with sign-in. Connectors are private to their owner unless \
              marked "shared" (an all-or-nothing toggle on the Sources page, not a list of \
              people). MCP/API calls use a personal access token from \
              [Settings > API Tokens](app://settings/api-tokens), with the same visibility as the \
              web UI - never a bypass.

            ## Getting started
            1. Ask in Chat - available connectors are searchable at once.
            2. (Server: sign in first.) Add sources on the Sources page, or ask this agent to \
               register one.
            3. Indexing is automatic; watch it on the Sources page or via `getConnectorStatus`.
            4. Integration: MCP at `<origin>/mcp`, search API `POST <origin>/api/v1/search`, \
               where origin is what the user actually reaches this app at - never invent a \
               host or port.

            ## Troubleshooting
            - **MCP client won't connect**: the URL must end in `/mcp` and be reachable from where \
              the client runs, not just the browser; check firewall rules for the port.
            - **401/403 on the API or MCP**: the token may be expired, revoked or from a deleted \
              account - generate one at [Settings > API Tokens](app://settings/api-tokens). The \
              header must be exactly `Authorization: Bearer pxm_<token>`; a missing "Bearer " or \
              stray whitespace is the usual mistake.
            - **`searchIndexedSources` returns nothing**: still indexing (`getConnectorStatus`), \
              private to another user and not shared, or a re-index pending after the embedding \
              model changed.
            - **"Does everything work?"**: `readSkill` `Self Test` and follow it.
            - **A source is in error**: `getConnectorStatus` gives its `error` and its \
              `connection`. A null connection means it cannot sync: registering it again gives it \
              the default connection, or the user picks one by editing the source. Registering an \
              existing source only re-syncs it (`alreadyRegistered: true`, same id) - to index it \
              from scratch, remove it by its `id` and register it again from its `target`. Report \
              what the tools returned, never what you meant them to do.

            ## Contact / support
            Give `contact@proxyma.ai` / `proxyma.ai` only if `getPlatformInfo` reports the product \
            name as exactly "Proxyma"; otherwise point the user to this app's own Help page.
            """
        ),
        new SeededSkill(
            "Self Test",
            "Test every feature of this installation step by step, and report what works and what does not.",
            """
            # Self Test

            For "test everything", "does it all work", "self test". You check each area with real \
            tool calls; the user checks what only a person can see. Never report a result you did \
            not get from a tool in THIS conversation.

            ## Ground rules
            - Start with `askUser`: "Reads only" (default) or "Reads and writes". Writes need a \
              scratch place the user NAMES - a test Jira project, Confluence space, Bitbucket \
              repository. Never write anywhere else, and delete what you create at the end.
            - A named place that refuses a write is a FAIL with its error. Do not try another \
              project, space or repository instead - ask the user for one.
            - What you write lands in systems colleagues read: call it "Self test", label it \
              "self-test", and never put this product's name in a title, label, body, comment, \
              branch or file name there.
            - Before each area, `readSkill` that area's skill (Jira, Confluence, Bitbucket, Web, \
              Memory & Knowledge, Documents & Files, Office Files, Connector Management) - its \
              tools appear only then.
            - A tool that is not offered after `readSkill` means the edition or license does not \
              include it: mark SKIPPED with that reason, not FAIL. A connector with no connection \
              in Settings is SKIPPED too, with the link to set it up.
            - One failure never stops the run. Record the tool's own error text and go on.
            - Keep each check small: 1-3 results, short queries.

            ## 1. Installation
            - `getPlatformInfo`: version, mode, OS. `getConnectorStatus`: every source, its \
              document count and state. Each source in error is a FAIL with its reason.
            - This conversation answering at all means the chat model works: PASS.

            ## 2. Search
            - `searchIndexedSources` with a word from the name of each source that has documents. \
              PASS if hits come back with their source; FAIL if a source with documents returns \
              nothing.
            - `searchKnowledgeGraph` for one entity from those hits, if the tool is offered.

            ## 3. Web
            - `webSearch` "Proxyma". `webFetch` https://proxyma.ai. `renderWebFetch` the same URL. \
              `webFetch` a public PDF. A network or proxy error is a FAIL with the error.

            ## 4. Memory
            - `recall` "self test". With writes allowed: `remember` \
              "Self test ran on <today's date>", then `recall` it back.
            - `searchConversations` for a word from an earlier conversation's title, then read \
              the first hit by its `conversationId`.

            ## 5. Files
            - `runPython`: write `outputs/self-test.xlsx` (3 rows, one chart) and \
              `outputs/self-test.docx` (a title and a table). PASS if both come back as files.
            - `convertDocument` the xlsx back: PASS if the 3 rows are in the text.
            - Ask the user to attach any PDF or Word file; `convertDocument` it. Mark NEEDS YOU \
              if they skip.
            - Desktop only, and only if the user wants: `saveLocalFile` the docx (they approve it).

            ## 6. Jira (reads)
            - `getJiraCurrentUser`, `listJiraProjects`, `searchJiraIssues` \
              `assignee = currentUser() ORDER BY updated DESC` (3), `getJiraIssue` on the first, \
              `listJiraIssueTypes` for its project, `listJiraFields` "sprint", \
              `callJiraApi` GET `/rest/api/2/serverInfo`.
            ### Jira (writes, scratch project only)
            - `createJiraIssue` a Task "Self test" assigned to the user from \
              `getJiraCurrentUser`, `addJiraComment`, `updateJiraIssue` with `addLabels` \
              ["self-test"], `transitionJiraIssue` with no target (lists transitions), \
              `attachJiraFile` self-test.xlsx, `createJiraIssue` a Sub-task under it (assigned \
              the same way), then `deleteJiraIssue` the sub-task and the task. If Jira refuses \
              the delete, close both instead and report the delete as FAIL with Jira's error.

            ## 7. Confluence (reads)
            - `listConfluenceSpaces`, `searchConfluencePages` `type=page order by lastmodified \
              desc` (3), `getConfluencePage` on the first.
            ### Confluence (writes, scratch space only)
            - `createConfluencePage` "Self test", `updateConfluencePage`, \
              `updateConfluenceLabels` add "self-test", `addConfluenceComment` then \
              `updateConfluenceComment`, `attachConfluenceFile` self-test.docx, a second page and \
              `moveConfluencePage` it under the first; then `deleteConfluenceComment` and \
              `deleteConfluencePage` both pages.

            ## 8. Bitbucket (reads)
            - `listBitbucketBranches`, `getBitbucketCommits` (3), `listBitbucketFiles` at the root, \
              `browseBitbucketPath` the README if there is one, and `getBitbucketPr` with \
              `getBitbucketPrDiff` on a recent pull request if there is one.
            ### Bitbucket (writes, scratch repository only)
            - `createBitbucketBranch` "self-test" from the default branch, then \
              `deleteBitbucketBranch` it. Never create a pull request, merge or tag in a self test.

            ## 9. SVN
            - `listSvnFiles` at the root, `readSvnFile` one small text file from the listing.

            ## 10. Sources
            - With writes allowed: `addSource` type `webpage` with seedUrl https://example.com, wait \
              until `getConnectorStatus` shows it indexed (check a few times), `searchIndexedSources` \
              "Example Domain", then `removeSource` it.

            ## 11. Agents
            - `delegate`, if offered: a sub-agent reports this installation's version. PASS if \
              it matches step 1.

            ## Report
            One table: Area | Check | Result (PASS / FAIL / SKIPPED / NEEDS YOU) | Detail. Then the \
            counts, and for each FAIL the fix - with the Settings link where there is one \
            ([Connectors](app://settings/connectors), [AI providers](app://settings/providers), \
            [License](app://settings/license)). Say what you created and deleted. Offer to put the \
            report in `outputs/self-test-report.md`, which the user can attach to Send feedback.

            ## What the user checks (give this list at the end)
            - Settings: change a setting, save, reopen - it stayed.
            - Sources: add a folder (Desktop) or upload a file (servers); Sync now; open a \
              source and a document's chunks.
            - Chat: drop a file on the chat; stop an answer mid-way; send a message while it \
              works; run two conversations at once.
            - Send feedback with a screenshot dropped on the form.
            - Enterprise Server: sign in and out; an admin sees Administration; a user cannot.
            - Integrations: connect an MCP client (Claude Code, VS Code) to `<origin>/mcp` with an \
              [API token](app://settings/api-tokens), and ask it to search.
            - Desktop: the tray menu opens the app; Settings > Updates checks for updates.
            """
        )
    );
}
