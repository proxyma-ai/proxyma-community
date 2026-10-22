// Fake third-party services for the connectors: each service's API, answered instantly and
// identically every time, under a path prefix of its own - /github/... - on one server.
//
// WHY THIS EXISTS. A connector is tested twice. Its own module tests it against LoopbackApi, a
// fake built in Java for the cases that are hard to arrange (a 429 with Retry-After, a redirect to
// another host, a truncated tree). This is the other half: a real HTTP server the PACKAGED app
// reaches through a connection a person adds in Settings > Connections, so the whole path - the
// connection dialog, the source dialog, the sync, the index, a search - can be driven by hand or by
// the functional suite without an account on the real service. It is fake-llm.mjs's sibling, and for
// the same reason: the same bytes on every run, so a red test means the product changed.
//
//   node tools/testing/fake-services.mjs --port 8798
//
// Then add a GitHub connection whose API URL (under More options) is http://127.0.0.1:8798/github/api/v3
// with any token (a host that is not github.com is a GitHub Enterprise Server, spoken to under
// /api/v3), and a GitHub source for the repository octo-org/handbook. For Slack, a connection
// whose API URL is http://127.0.0.1:8798/slack/api with any bot token, and a Slack source with no
// channel named (every public channel) or #leadership for the private one. Likewise a GitLab
// connection whose GitLab URL is http://127.0.0.1:8798/gitlab, with any token, and a source for the
// group fake-group (or the project fake-group/runbooks); and a Linear connection whose API URL is
// http://127.0.0.1:8798/linear, with any key, and a source for the team keys ENG, OPS (or none).
// For Notion, a connection whose API URL is http://127.0.0.1:8798/notion/v1 with any token, and a
// Notion source with nothing chosen. For Airtable, a connection whose API URL is
// http://127.0.0.1:8798/airtable/v0 with any token, and an Airtable source for the base
// appFakeCorpOps001. The Atlassian services are added the same way - see each one below for its
// address, its source and its codewords.
// A HubSpot connection whose API URL is http://127.0.0.1:8798/hubspot, with any token, and a
// HubSpot source with any object types. A Salesforce connection signed in with the JWT bearer flow
// whose My Domain URL is http://127.0.0.1:8798/salesforce, any consumer key and user name, and any
// unencrypted RSA private key (it is not checked), and a Salesforce source with the default objects.
// A Zoom connection whose API URL is http://127.0.0.1:8798/zoom/v2, with any token, and a Zoom source
// for your own meetings (or, through a Server-to-Server app, for * - see the Zoom section below).
//
// FLAGS
//   --port <n>        default 8798. 0 picks a free port and prints it as `listening <port>`.
//   --page-size <n>   the most items any list returns per page, default 2 - small on purpose, so
//                     a listing has to follow Link headers to see everything.
//   --log             print every request line to stderr.
//
// SERVICES
//   /github/...       GitHub's REST API, the parts the GitHub connector reads: /user, a
//                     repository, its issues and pull requests (one list, Link-paginated, with
//                     `since`), their comments and reviews, the git tree and raw file contents.
//                     Every request needs an `Authorization: Bearer <anything>` header; one
//                     without it is a 401, as GitHub's own is. A repository it does not hold is a 404.
//   /slack/api/...    Slack's Web API, the methods the Slack connector calls: auth.test,
//                     users.conversations, conversations.history (oldest, latest, inclusive),
//                     conversations.replies and users.info, cursor-paged. As Slack's own, every
//                     answer is a 200 with `ok`, and a refusal is `ok: false` with an `error` -
//                     `not_authed` without a bearer, `channel_not_found`, `thread_not_found`.
//   /gitlab/api/v4/... GitLab's REST API, the parts the GitLab connector reads: /user, a group
//                     (fake-group) with a project and a subgroup's project, their issues and merge
//                     requests (Link-paginated, with `updated_after`), notes, branches, the repository
//                     tree, and files (HEAD for the size, /raw for the bytes). Needs a PRIVATE-TOKEN
//                     header or a bearer; without either it is a 401.
//   /linear/graphql   Linear's GraphQL API, answered by operationName (Viewer, Teams, Issues, Issue,
//                     IssueComments) - the queries the Linear connector sends - with cursor pages, the
//                     team and updatedAt filters, and archived issues only with includeArchived.
//                     Needs an Authorization header; without one it is a 400 AUTHENTICATION_ERROR.
//   /google/...       Google's Drive, Gmail and Calendar APIs, the parts the Google connectors read:
//                     Drive files (My Drive, shared with me, a shared drive, a folder), exports and
//                     downloads, the change feed; Gmail labels, threads and history; Calendar events
//                     with a sync token; and an OAuth sign-in that agrees at once (/google/oauth/...).
//                     A change token, history id or sync token other than the current one has expired.
//   /jira/...         Jira, both product lines over one project, HELP (three issues; codewords
//                     BERGAMOT, SUMAC, GALANGAL): REST v2 as Data Center answers it (GET /search by
//                     offset, wiki-text bodies) and REST v3 as Cloud does (POST /search/jql by
//                     nextPageToken, the old /search gone with a 410, bodies as Atlassian Document
//                     Format). Server URL http://127.0.0.1:8798/jira; a personal access token makes
//                     the connection Data Center, an email and any API token makes it Cloud.
//   /confluence/...   Confluence, both product lines over one space, TEAM (three pages and a comment
//                     thread; codewords OLEANDER, WISTERIA, HIBISCUS): /rest/api as Data Center, and
//                     /wiki/api/v2 with v1's /wiki/rest/api/search as Cloud. Server URL
//                     http://127.0.0.1:8798/confluence for either.
//   /atlassian/...    One Atlassian Cloud SITE serving both: Jira at /atlassian/rest/api/3/...,
//                     Confluence at /atlassian/wiki/... - what one Cloud connection (an email and any
//                     API token, server URL http://127.0.0.1:8798/atlassian) reads Jira and Confluence
//                     sources through. The agent's Confluence tools speak v1 there too
//                     (/wiki/rest/api/space, /wiki/rest/api/content/search).
//   /bitbucket/2.0/... Bitbucket Cloud's API for one repository, acme-team/field-guide: pull requests
//                     in every state (codewords SAMPHIRE, QUINCE, LOVAGE) with comments, the branch
//                     head and its source tree (CHERVIL, SORREL). API URL (More options)
//                     http://127.0.0.1:8798/bitbucket/2.0.
//                     The three take a bearer or a Basic credential of any value, and refuse a
//                     request with none.
//   /notion/v1/...    Notion's API at Notion-Version 2026-03-11, the parts the Notion connector
//                     reads: users/me and users/{id}, POST search (newest edit first), pages and
//                     their markdown, block children, a database, its data source and POST query,
//                     cursor-paged by start_cursor. A request without a bearer is a 401, one without
//                     a Notion-Version header a 400 missing_version, as Notion's own are.
//   /airtable/v0/...  Airtable's Web API, the parts the Airtable connector reads: meta/whoami,
//                     meta/bases, a base's tables, and a table's records - offset-paged, with a
//                     view, cellFormat=string, and the two filterByFormula shapes the connector
//                     writes (one record by RECORD_ID(), created or changed since a time).
//                     Attachment links are signed afresh on every answer, as Airtable's are.
//   /zendesk/api/v2/... Zendesk's REST API, the parts the Zendesk connector reads: /users/me, the
//                     cursor-based ticket export (start_time or cursor, with a deleted ticket in it),
//                     tickets with their comments (cursor-paged, an internal note among them), and the
//                     Help Center - locales, sections, articles by language and section, and the
//                     incremental article export. Needs a Basic or bearer Authorization header;
//                     without one it is a 401. An OAuth sign-in that agrees at once (/zendesk/oauth/...).
//   /freshdesk/api/v2/... Freshdesk's REST API v2, the parts the Freshdesk connector reads: /agents/me,
//                     agents and contacts, tickets (updated_since, order, Link-paginated, trashed and
//                     spam ones only under their filter), conversations, and the knowledge base's
//                     categories, folders, subfolders and articles. Needs a Basic header; without one
//                     it is a 401.
//   /hubspot/...      HubSpot's CRM API, the parts the HubSpot connector reads, under any dated
//                     version or v3: account-info details, owners, pipelines, each object type's
//                     list (cursor pages, archived=true for the archived company), its CRM search
//                     (a last-modified GTE filter, oldest first), batch reads and one record with its
//                     associations. Needs a bearer; without one it is a 401. A record it does not
//                     hold is a 404 OBJECT_NOT_FOUND, as HubSpot's own.
//   /salesforce/...   A Salesforce org: the JWT bearer token exchange (any assertion, answering an
//                     instance_url on this server), userinfo, sObject describes, getDeleted, and SOQL
//                     through a small interpreter of the queries the connector sends, paged by
//                     nextRecordsUrl. Needs a bearer; without one it is a 401 INVALID_SESSION_ID.
//   /graph/...        Microsoft Graph v1.0 under /graph/v1.0, the parts the Microsoft 365 connectors
//                     read: me, the OneDrive (/me/drive, root:/path, items, children, content through a
//                     pre-authenticated download, the drive's delta), a SharePoint site with a page
//                     and a library, a Teams team, channel, messages, replies and their delta, a Teams
//                     chat, an Outlook mail folder with its message delta and a calendar - @odata.nextLink
//                     paged. Needs a bearer; without one it is a 401. A delta token other than the
//                     current one has expired (410). A sign-in that agrees at once (/graph/login/...).
//   /zoom/...         Zoom's API under /zoom/v2, the parts the Zoom connector reads: users/me, users
//                     (paged), a person's recordings and meeting summaries by date window (a month
//                     at most, as Zoom's own; paged by next_page_token), a meeting's recordings and
//                     summary by its UUID (encoded once or twice), past participants, transcript
//                     downloads (VTT) under /zoom/rec/download, and the token endpoint for a
//                     Server-to-Server app or a sign-in (/zoom/oauth/...). Needs a bearer; without
//                     one it is a 401 with code 124, as Zoom's own.
//   /asana/api/1.0/... Asana's REST API for one workspace (Fake Workspace) and its projects Launch
//                     plan and Hiring: tasks by project with modified_since and offset pages, a task's
//                     stories and subtasks, and the Events feed with its sync token (codewords
//                     CHALCEDONY, LABRADORITE, TOURMALINE). API URL (More options)
//                     http://127.0.0.1:8798/asana/api/1.0, any token; workspace Fake Workspace.
//   /monday/v2        monday.com's GraphQL API, answered by operationName (Me, Boards, BoardsById,
//                     Items, MoreItems, Item, ItemUpdates): board 3101, Marketing, its items_page
//                     cursor pages and an archived item it never lists (codewords MALACHITE,
//                     CARNELIAN, GARNET; JASPER never found). API URL http://127.0.0.1:8798/monday,
//                     any token; boards empty or 3101.
//   /trello/1/...     Trello's REST API for one member: an open board (short link FkRoad01) and a
//                     closed one, the board's cards by filter and before, a card with its list,
//                     board, members, checklists and attachments, its commentCard actions
//                     (codewords MARZIPAN, NOUGAT, PRALINE; BRITTLE archived, never found). API URL
//                     http://127.0.0.1:8798/trello/1, any API key and token; boards empty or FkRoad01.
//                     Wants the OAuth header (or key and token parameters); a 401 without.
//   /clickup/api/v2/... ClickUp's API v2 for one workspace (Fake Workspace, 9010): the team's task
//                     list with its space, folder, list and date_updated_gt filters and numbered
//                     pages, a task with its custom fields, checklists and subtasks, and its comments
//                     (codewords CERULEAN, CHARTREUSE, OCHRE; SEPIA archived, never found). API URL
//                     http://127.0.0.1:8798/clickup/api/v2, any token; workspace Fake Workspace.
//                     Asana, monday.com and ClickUp refuse a request without an Authorization header.
//   /dropbox/2/...    Dropbox's API, the parts the Dropbox connector reads: users/get_current_account,
//                     files/list_folder and /continue (cursor-paged, recursive or not; a cursor not
//                     its own is `reset`), files/get_metadata, and files/download and files/export
//                     (GET or POST, the argument in Dropbox-API-Arg). The account is in a team with a
//                     team space: with Dropbox-API-Path-Root {".tag":"root","root":"3000"} it sees
//                     /Fake User and /Marketing, without it only its own folder, as Dropbox roots it.
//                     A Paper doc exports as markdown. Codewords NUTMEG, BASALT, CHAMOMILE (the Paper
//                     doc) and LODESTAR (team space only). API URL (More options)
//                     http://127.0.0.1:8798/dropbox, any access token; or Sign in with Dropbox with
//                     proxyma.connectors.dropbox.authorize-url and .token-url set to /dropbox/oauth2/...
//   /box/2.0/...      Box's API, the parts the Box connector reads: users/me, folders and their items
//                     (marker-paged), files and their content (a 302 to /box/dl/<id>, which wants no
//                     token), and the events stream, which has nothing after position 9000 and
//                     refuses any other. All Files holds Handbook (welcome.md: ARGONAUT; a Box Note:
//                     FIRETHORN), Handbook/Policies (leave-policy.txt: SPINDRIFT) and expenses.csv
//                     (ZEPHYR). API URL http://127.0.0.1:8798/box/2.0, any developer token; or Sign in
//                     with Box through /box/oauth2/..., whose refresh token is good once, as Box's is.
//
// Adding a service: a handler under SERVICES below, keyed by its prefix, and its fixtures beside it.
//
// IMAP is not HTTP, so it is not served here. tools/testing/imap-mailbox holds a mailbox for
// GreenMail (Apache-2.0, the server the IMAP connector's own tests use), run on its own:
//   curl -LO https://repo1.maven.org/maven2/com/icegreen/greenmail-standalone/2.1.14/greenmail-standalone-2.1.14.jar
//   java -Dgreenmail.setup.test.smtp -Dgreenmail.setup.test.imap -Dgreenmail.hostname=127.0.0.1
//        -Dgreenmail.users=fake.user:app-password@localhost -Dgreenmail.users.login=email
//        -Dgreenmail.preload.dir=tools/testing/imap-mailbox -jar greenmail-standalone-2.1.14.jar
// (one line; from the repository root; it also opens its own API on port 8080). Then add an IMAP
// mail connection: Mail server 127.0.0.1, Security "None (this computer only)", Port 3143 under More
// options, user fake.user@localhost, password app-password - and an IMAP source for INBOX and
// Projects. Codewords: SALSIFY (a message), WOODRUFF (its text attachment), GOLDCREST (an HTML-only
// newsletter), BURDOCK (Projects). Each <user>/<folder>/*.eml file is one message.
//
// CONTROL - no product code ever calls these
//   GET    /_control/requests   every request received, newest last, as JSON
//   DELETE /_control/requests   forget them
//   GET    /_control/health     { ok: true } once listening

import { createServer } from 'node:http'

const args = process.argv.slice(2)
const flag = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] !== undefined ? args[i + 1] : fallback
}
const PORT = Number(flag('port', '8798'))
const PAGE_SIZE = Math.max(1, Number(flag('page-size', '2')))
const LOG = args.includes('--log')

const requests = []

// -- GitHub ------------------------------------------------------------------------------------

// One repository, written so a search can find each kind of item by a word only it holds.
const GH_USER = { login: 'fake-user', id: 1, name: 'Fake User' }
const GH_OWNER = 'octo-org'
const GH_REPO = 'handbook'
const GH_WEB = `https://github.example/${GH_OWNER}/${GH_REPO}`
const T0 = Date.parse('2026-09-01T09:00:00Z')
const at = minutes => new Date(T0 + minutes * 60_000).toISOString()

const ghRepo = {
  id: 4242,
  name: GH_REPO,
  full_name: `${GH_OWNER}/${GH_REPO}`,
  html_url: GH_WEB,
  default_branch: 'main',
  pushed_at: at(500),
  private: false,
}

const person = login => ({ login, id: login.length, type: 'User' })

// Issues and pull requests share one numbering and one list, as on GitHub.
const ghIssues = [
  {
    number: 1, title: 'Rollout checklist for the quarterly release', state: 'open',
    body: 'Before each release we walk the checklist. The codeword for the checklist is PERIWINKLE.',
    user: person('alice'), assignees: [person('bob')], labels: [{ name: 'process' }], milestone: { title: 'Q4' },
    created_at: at(0), updated_at: at(100), closed_at: null,
  },
  {
    number: 2, title: 'Search returns stale results after a reindex', state: 'closed',
    body: 'Stale results showed up for an hour after the reindex. Tracked under the codeword MARIGOLD.',
    user: person('bob'), assignees: [], labels: [{ name: 'bug' }], milestone: null,
    created_at: at(10), updated_at: at(200), closed_at: at(200),
  },
  {
    number: 3, title: 'Add the onboarding guide', state: 'open', draft: false,
    body: 'This pull request adds docs/onboarding.md. Reviewers: look for the codeword SAFFRON.',
    user: person('carol'), assignees: [person('alice')], labels: [{ name: 'docs' }], milestone: null,
    created_at: at(20), updated_at: at(300), closed_at: null,
    pull_request: { url: `${GH_WEB}/pull/3` },
    head: { ref: 'onboarding' }, base: { ref: 'main' }, merged_at: null,
  },
]

const ghComments = {
  1: [{ id: 11, user: person('bob'), created_at: at(50), body: 'Added the database step to the checklist.' }],
  2: [{ id: 21, user: person('alice'), created_at: at(150), body: 'Fixed by flushing the cache after the swap.' }],
  3: [{ id: 31, user: person('alice'), created_at: at(250), body: 'Looks good once the links are fixed.' }],
}
const ghReviewComments = {
  3: [{ id: 32, user: person('bob'), created_at: at(260), path: 'docs/onboarding.md', body: 'Link the security page here.' }],
}
const ghReviews = {
  3: [{ id: 33, user: person('bob'), state: 'CHANGES_REQUESTED', submitted_at: at(270), body: 'One link is missing.' }],
}

const ghFiles = {
  'README.md': '# Handbook\n\nHow the team works. The codeword for the handbook is LAVENDER.\n',
  'docs/onboarding.md': '# Onboarding\n\nYour first week, day by day. The codeword for onboarding is JUNIPER.\n',
  'docs/security.rst': 'Security\n========\n\nReport anything odd to the security channel.\n',
  'src/app.js': 'console.log("not documentation")\n',
}
const blobSha = path => `sha-${Buffer.from(path).toString('hex').slice(0, 16)}`

const issueView = (issue, base) => ({
  ...issue,
  html_url: `${GH_WEB}/${issue.pull_request ? 'pull' : 'issues'}/${issue.number}`,
  url: `${base}/repos/${GH_OWNER}/${GH_REPO}/issues/${issue.number}`,
})

// GitHub's Link header: rel="next" while there is a next page, rel="last" always.
function paged(res, url, items) {
  const page = Math.max(1, Number(url.searchParams.get('page') ?? '1'))
  const perPage = Math.min(PAGE_SIZE, Math.max(1, Number(url.searchParams.get('per_page') ?? '30')))
  const pages = Math.max(1, Math.ceil(items.length / perPage))
  const link = n => {
    const u = new URL(url)
    u.searchParams.set('page', String(n))
    return `<${u.toString()}>`
  }
  const links = []
  if (page < pages) links.push(`${link(page + 1)}; rel="next"`)
  links.push(`${link(pages)}; rel="last"`)
  json(res, 200, items.slice((page - 1) * perPage, page * perPage), { Link: links.join(', ') })
}

function github(req, res, url, rest) {
  // The connector speaks to any host but github.com as a GitHub Enterprise Server, under /api/v3.
  if (rest === 'api/v3' || rest.startsWith('api/v3/')) rest = rest.slice('api/v3/'.length)
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) {
    return json(res, 401, { message: 'Requires authentication', documentation_url: 'https://docs.github.com/rest' })
  }
  const base = `${url.origin}/github`
  if (rest === 'user') return json(res, 200, GH_USER)

  const m = rest.match(/^repos\/([^/]+)\/([^/]+)(?:\/(.*))?$/)
  if (!m) return notFound(res)
  const [, owner, repo, tail = ''] = m
  if (owner.toLowerCase() !== GH_OWNER || repo.toLowerCase() !== GH_REPO) return notFound(res)
  if (tail === '') return json(res, 200, ghRepo)

  if (tail === 'issues') {
    const since = url.searchParams.get('since')
    const state = url.searchParams.get('state') ?? 'open'
    const items = ghIssues
      .filter(i => state === 'all' || i.state === state)
      .filter(i => !since || Date.parse(i.updated_at) >= Date.parse(since))
      .sort((a, b) => (url.searchParams.get('direction') === 'asc' ? 1 : -1) * (Date.parse(a.updated_at) - Date.parse(b.updated_at)))
      .map(i => issueView(i, base))
    return paged(res, url, items)
  }
  let n = tail.match(/^(issues|pulls)\/(\d+)$/)
  if (n) {
    const issue = ghIssues.find(i => i.number === Number(n[2]))
    if (!issue || (n[1] === 'pulls' && !issue.pull_request)) return notFound(res)
    return json(res, 200, issueView(issue, base))
  }
  n = tail.match(/^(issues|pulls)\/(\d+)\/(comments|reviews)$/)
  if (n) {
    const number = Number(n[2])
    const table = n[1] === 'issues' ? ghComments : n[3] === 'reviews' ? ghReviews : ghReviewComments
    if (!ghIssues.some(i => i.number === number)) return notFound(res)
    return paged(res, url, table[number] ?? [])
  }
  n = tail.match(/^git\/trees\/(.+)$/)
  if (n) {
    if (decodeURIComponent(n[1]) !== 'main') return notFound(res)
    const tree = Object.entries(ghFiles).map(([path, body]) => ({
      path, mode: '100644', type: 'blob', sha: blobSha(path), size: Buffer.byteLength(body),
    }))
    tree.push({ path: 'docs', mode: '040000', type: 'tree', sha: 'sha-docs' })
    return json(res, 200, { sha: 'tree-sha-main-1', truncated: false, tree })
  }
  n = tail.match(/^contents\/(.+)$/)
  if (n) {
    const path = n[1].split('/').map(decodeURIComponent).join('/')
    const body = ghFiles[path]
    if (body === undefined) return notFound(res)
    if ((req.headers.accept ?? '').includes('raw')) {
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
      return res.end(body)
    }
    return json(res, 200, {
      type: 'file', path, sha: blobSha(path), size: Buffer.byteLength(body), encoding: 'base64',
      content: Buffer.from(body).toString('base64'),
    })
  }
  return notFound(res)
}

// -- Slack -------------------------------------------------------------------------------------

// One workspace, three channels the app is in - two public, one private - and a word per thread
// and per day that a search can find. Times are Slack's ts: seconds and microseconds.
const SL_WORKSPACE = { team: 'Fake Corp', team_id: 'T0FAKE', user: 'proxyma', user_id: 'U0BOT', url: 'https://fake-corp.slack.example/' }
const slTs = minutes => `${Math.floor((T0 + minutes * 60_000) / 1000)}.000100`
const slPeople = {
  U1: { id: 'U1', name: 'alice', real_name: 'Alice Nguyen' },
  U2: { id: 'U2', name: 'bao', real_name: 'Bao Tran' },
  U3: { id: 'U3', name: 'carol', real_name: 'Carol Smith' },
}
const slChannels = [
  { id: 'C100GENERAL', name: 'general', is_channel: true, is_private: false, is_member: true },
  { id: 'C200ENGINEERING', name: 'engineering', is_channel: true, is_private: false, is_member: true },
  { id: 'G300LEADERSHIP', name: 'leadership', is_channel: false, is_group: true, is_private: true, is_member: true },
]
// Each channel's messages, the replies of a thread under its first message.
const slMessages = {
  C100GENERAL: [
    { user: 'U1', at: 0, text: 'Office move next month. The codeword for the move is TANGERINE.', replies: [
      { user: 'U2', at: 30, text: 'Will the parking passes carry over?' },
      { user: 'U1', at: 45, text: 'Yes, <@U2> - facilities confirmed it.' },
    ] },
    { user: 'U3', at: 1500, text: 'The coffee machine is fixed. Codeword for the kitchen: CARDAMOM.' },
    { user: 'U2', at: 1510, text: 'Thanks <@U3> &amp; whoever ordered the part.' },
    { user: 'U2', at: 1520, subtype: 'channel_join', text: '<@U2> has joined the channel' },
  ],
  C200ENGINEERING: [
    { user: 'U2', at: 100, text: 'Postmortem for the cache outage. The codeword is HELIOTROPE.', replies: [
      { user: 'U3', at: 160, text: 'Root cause was the eviction storm, see <https://wiki.example/cache|the write-up>.' },
    ] },
    { user: 'U3', at: 3000, text: 'Release 4.2 is tagged. The release codeword is OBSIDIAN.' },
  ],
  G300LEADERSHIP: [
    { user: 'U1', at: 200, text: 'Budget review moves to Thursday. The budget codeword is QUILLWORT.' },
  ],
}

// A message as Slack's history shows it; a thread's first message carries its replies' count.
function slMessage(m) {
  const ts = slTs(m.at)
  const out = { type: 'message', user: m.user, ts, text: m.text, team: SL_WORKSPACE.team_id }
  if (m.subtype) out.subtype = m.subtype
  if (m.replies?.length) {
    out.thread_ts = ts
    out.reply_count = m.replies.length
    out.latest_reply = slTs(Math.max(...m.replies.map(r => r.at)))
    out.reply_users = [...new Set(m.replies.map(r => r.user))]
  }
  return out
}

// Slack's cursor paging: `limit` capped at --page-size, and next_cursor empty on the last page.
function slPaged(res, url, field, items) {
  const offset = Number((url.searchParams.get('cursor') ?? '').replace(/^o/, '') || '0')
  const limit = Math.min(PAGE_SIZE, Math.max(1, Number(url.searchParams.get('limit') ?? '100')))
  const more = offset + limit < items.length
  json(res, 200, {
    ok: true, [field]: items.slice(offset, offset + limit), has_more: more,
    response_metadata: { next_cursor: more ? `o${offset + limit}` : '' },
  })
}

function slack(req, res, url, rest) {
  if (!rest.startsWith('api/')) return json(res, 404, { ok: false, error: 'unknown_method' })
  const method = rest.slice('api/'.length)
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) return json(res, 200, { ok: false, error: 'not_authed' })
  const q = name => url.searchParams.get(name)

  if (method === 'auth.test') return json(res, 200, { ok: true, ...SL_WORKSPACE })
  if (method === 'users.info') {
    const user = slPeople[q('user')]
    return json(res, 200, user ? { ok: true, user } : { ok: false, error: 'user_not_found' })
  }
  if (method === 'users.conversations') {
    const types = (q('types') ?? 'public_channel').split(',')
    return slPaged(res, url, 'channels', slChannels.filter(c => types.includes(c.is_private ? 'private_channel' : 'public_channel')))
  }
  const channel = slMessages[q('channel')]
  if (method === 'conversations.history') {
    if (!channel) return json(res, 200, { ok: false, error: 'channel_not_found' })
    const inclusive = q('inclusive') === 'true'
    const oldest = Number(q('oldest') ?? '0')
    const latest = q('latest') === null ? Infinity : Number(q('latest'))
    const items = channel.map(slMessage)
      .filter(m => (inclusive ? Number(m.ts) >= oldest : Number(m.ts) > oldest))
      .filter(m => (inclusive ? Number(m.ts) <= latest : Number(m.ts) < latest))
      .sort((a, b) => Number(b.ts) - Number(a.ts))
    return slPaged(res, url, 'messages', items)
  }
  if (method === 'conversations.replies') {
    if (!channel) return json(res, 200, { ok: false, error: 'channel_not_found' })
    const root = channel.find(m => slTs(m.at) === q('ts'))
    if (!root) return json(res, 200, { ok: false, error: 'thread_not_found' })
    const replies = (root.replies ?? []).map(r => ({
      type: 'message', user: r.user, ts: slTs(r.at), thread_ts: slTs(root.at), text: r.text,
    }))
    return slPaged(res, url, 'messages', [slMessage(root), ...replies])
  }
  return json(res, 200, { ok: false, error: 'unknown_method' })
}

// -- GitLab ------------------------------------------------------------------------------------

// One group with a project of its own and one in a subgroup, written so a search can find each
// kind of item by a word only it holds.
const GL_USER = { id: 1, username: 'fake-user', name: 'Fake User' }
const GL_WEB = 'https://gitlab.example'
const glGroup = { id: 50, name: 'Fake Group', path: 'fake-group', full_path: 'fake-group', web_url: `${GL_WEB}/groups/fake-group` }
const glProjects = [
  { id: 101, path_with_namespace: 'fake-group/runbooks', default_branch: 'main', web_url: `${GL_WEB}/fake-group/runbooks`, empty_repo: false },
  { id: 102, path_with_namespace: 'fake-group/platform/api', default_branch: 'main', web_url: `${GL_WEB}/fake-group/platform/api`, empty_repo: false },
]
const glPerson = username => ({ id: username.length, username, name: username })

const glIssues = [
  {
    project_id: 101, iid: 1, title: 'Database failover drill', state: 'opened', issue_type: 'issue',
    description: 'Run the failover drill every quarter. The codeword for the drill is TANGERINE.',
    author: glPerson('alice'), assignees: [glPerson('bob')], labels: ['ops'], milestone: { title: 'Q4' },
    created_at: at(0), updated_at: at(110), closed_at: null, confidential: false,
  },
  {
    project_id: 102, iid: 1, title: 'Rate limiter drops bursts', state: 'closed', issue_type: 'incident',
    description: 'Bursts above the limit were dropped silently. Tracked under the codeword OBSIDIAN.',
    author: glPerson('bob'), assignees: [], labels: ['bug'], milestone: null,
    created_at: at(10), updated_at: at(210), closed_at: at(210), confidential: false,
  },
]
const glMergeRequests = [
  {
    project_id: 102, iid: 4, title: 'Token bucket for the rate limiter', state: 'merged', draft: false,
    description: 'Replaces the fixed window with a token bucket. Reviewers: look for the codeword CINNABAR.',
    author: glPerson('carol'), assignees: [glPerson('carol')], reviewers: [glPerson('alice')], labels: ['perf'],
    milestone: null, source_branch: 'token-bucket', target_branch: 'main',
    created_at: at(20), updated_at: at(310), merged_at: at(305), closed_at: null,
  },
]
const glNotes = {
  'issues/101/1': [
    { id: 1, author: glPerson('bob'), created_at: at(60), body: 'The last drill took eleven minutes.', system: false },
    { id: 2, author: glPerson('bob'), created_at: at(61), body: 'added ~ops label', system: true },
  ],
  'issues/102/1': [{ id: 3, author: glPerson('alice'), created_at: at(160), body: 'Fixed by !4.', system: false }],
  'merge_requests/102/4': [
    {
      id: 4, type: 'DiffNote', author: glPerson('alice'), created_at: at(260), body: 'Name the refill rate.', system: false,
      position: { new_path: 'src/limiter.go' },
    },
  ],
}
const glFiles = {
  101: {
    'README.md': '# Runbooks\n\nHow the team handles incidents. The codeword for the runbooks is VERMILION.\n',
    'docs/deploy.md': '# Deploying\n\nShip on Tuesdays only. The codeword for deploys is COBALT.\n',
    'scripts/failover.sh': 'echo "not documentation"\n',
  },
  102: {
    'README.md': '# API\n\nThe public API. The codeword for the API is MAGENTA.\n',
    'src/limiter.go': 'package limiter\n',
  },
}
const glBlobId = (project, path) => `blob-${project}-${Buffer.from(path).toString('hex').slice(0, 12)}`

const glRef = (item, sigil) => {
  const project = glProjects.find(p => p.id === item.project_id)
  return { short: `${sigil}${item.iid}`, full: `${project.path_with_namespace}${sigil}${item.iid}` }
}
const glIssueView = i => ({
  ...i, references: glRef(i, '#'),
  web_url: `${glProjects.find(p => p.id === i.project_id).web_url}/-/issues/${i.iid}`,
})
const glMergeRequestView = m => ({
  ...m, references: glRef(m, '!'),
  web_url: `${glProjects.find(p => p.id === m.project_id).web_url}/-/merge_requests/${m.iid}`,
})

// A project by its numeric id or its full path, as GitLab's :id takes either.
const glProject = idOrPath => {
  const key = decodeURIComponent(idOrPath).toLowerCase()
  return glProjects.find(p => String(p.id) === key || p.path_with_namespace === key)
}

// Issues or merge requests as GitLab lists them: `updated_after`, ordered by updated_at.
function glList(res, url, items) {
  const after = url.searchParams.get('updated_after')
  const sorted = items
    .filter(i => !after || Date.parse(i.updated_at) >= Date.parse(after))
    .sort((a, b) => (url.searchParams.get('sort') === 'asc' ? 1 : -1) * (Date.parse(a.updated_at) - Date.parse(b.updated_at)))
  return paged(res, url, sorted)
}

function gitlab(req, res, url, rest) {
  if (!req.headers['private-token'] && !/^Bearer \S+/.test(req.headers.authorization ?? '')) {
    return json(res, 401, { message: '401 Unauthorized' })
  }
  if (!rest.startsWith('api/v4/')) return glNotFound(res)
  const path = rest.slice('api/v4/'.length)
  if (path === 'user') return json(res, 200, GL_USER)

  let m = path.match(/^groups\/([^/]+)(?:\/(issues|merge_requests|projects))?$/)
  if (m) {
    const key = decodeURIComponent(m[1]).toLowerCase()
    if (key !== glGroup.full_path && key !== String(glGroup.id)) return glNotFound(res)
    if (!m[2]) return json(res, 200, glGroup)
    if (m[2] === 'issues') return glList(res, url, glIssues.map(glIssueView))
    if (m[2] === 'merge_requests') return glList(res, url, glMergeRequests.map(glMergeRequestView))
    return paged(res, url, glProjects)
  }

  m = path.match(/^projects\/([^/]+)(?:\/(.*))?$/)
  if (!m) return glNotFound(res)
  const project = glProject(m[1])
  if (!project) return glNotFound(res)
  const tail = m[2] ?? ''
  if (tail === '') return json(res, 200, project)
  if (tail === 'issues') return glList(res, url, glIssues.filter(i => i.project_id === project.id).map(glIssueView))
  if (tail === 'merge_requests') {
    return glList(res, url, glMergeRequests.filter(i => i.project_id === project.id).map(glMergeRequestView))
  }
  let n = tail.match(/^(issues|merge_requests)\/(\d+)(\/notes)?$/)
  if (n) {
    const table = n[1] === 'issues' ? glIssues : glMergeRequests
    const item = table.find(i => i.project_id === project.id && i.iid === Number(n[2]))
    if (!item) return glNotFound(res)
    if (n[3]) return paged(res, url, glNotes[`${n[1]}/${project.id}/${item.iid}`] ?? [])
    return json(res, 200, n[1] === 'issues' ? glIssueView(item) : glMergeRequestView(item))
  }
  n = tail.match(/^repository\/branches\/(.+)$/)
  if (n) {
    if (decodeURIComponent(n[1]) !== project.default_branch) return glNotFound(res)
    return json(res, 200, { name: project.default_branch, commit: { id: `head-${project.id}-1`, committed_date: at(400) } })
  }
  if (tail === 'repository/tree') {
    if (url.searchParams.get('ref') !== project.default_branch) return glNotFound(res)
    const files = glFiles[project.id] ?? {}
    const tree = Object.keys(files).map(path => ({
      id: glBlobId(project.id, path), name: path.split('/').pop(), type: 'blob', path, mode: '100644',
    }))
    const folders = [...new Set(Object.keys(files).filter(p => p.includes('/')).map(p => p.split('/')[0]))]
    folders.forEach(f => tree.push({ id: `tree-${f}`, name: f, type: 'tree', path: f, mode: '040000' }))
    return paged(res, url, tree)
  }
  n = tail.match(/^repository\/files\/([^/]+)(\/raw)?$/)
  if (n) {
    const file = decodeURIComponent(n[1])
    const body = (glFiles[project.id] ?? {})[file]
    if (body === undefined || url.searchParams.get('ref') !== project.default_branch) return glNotFound(res)
    if (n[2]) {
      res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
      return res.end(body)
    }
    // HEAD answers the size alone, as GitLab's own does in X-Gitlab-Size.
    res.writeHead(200, { 'Content-Type': 'application/json', 'X-Gitlab-Size': String(Buffer.byteLength(body)) })
    return res.end(req.method === 'HEAD' ? undefined : JSON.stringify({ file_path: file, size: Buffer.byteLength(body) }))
  }
  return glNotFound(res)
}

function glNotFound(res) {
  json(res, 404, { message: '404 Not Found' })
}

// -- Linear ------------------------------------------------------------------------------------

// Two teams, written so a search can find each issue by a word only it holds. ENG-3 is archived:
// listed only with includeArchived, so a full sync never indexes it.
const LN_VIEWER = { id: 'user-1', name: 'Fake User', email: 'fake-user@example.com' }
const lnTeams = [{ id: 'team-eng', key: 'ENG', name: 'Engineering' }, { id: 'team-ops', key: 'OPS', name: 'Operations' }]
const lnPerson = name => ({ id: `user-${name.toLowerCase()}`, name })
const lnIssues = [
  {
    id: 'issue-0001', identifier: 'ENG-1', title: 'Search ranking ignores freshness', team: 'ENG',
    description: 'Old pages outrank new ones. The codeword for ranking is AMETHYST.', priorityLabel: 'High', estimate: 3,
    state: { name: 'In Progress', type: 'started' }, assignee: lnPerson('Bob'), creator: lnPerson('Alice'),
    project: { name: 'Search quality' }, cycle: { number: 7, name: null }, labels: ['bug', 'search'],
    createdAt: at(0), updatedAt: at(120), archivedAt: null, trashed: null,
    comments: [
      { body: 'Freshness should decay over thirty days.', createdAt: at(60), user: lnPerson('Bob') },
      { body: 'Agreed, with a floor.', createdAt: at(70), user: lnPerson('Alice') },
      { body: 'Shipped behind a flag.', createdAt: at(110), user: lnPerson('Bob') },
    ],
  },
  {
    id: 'issue-0002', identifier: 'OPS-1', title: 'Rotate the on-call pager', team: 'OPS',
    description: 'The pager rotates weekly on Mondays. The codeword for on-call is HELIOTROPE.', priorityLabel: 'Medium',
    estimate: null, state: { name: 'Todo', type: 'unstarted' }, assignee: null, creator: lnPerson('Carol'),
    project: null, cycle: null, labels: [], createdAt: at(10), updatedAt: at(220), archivedAt: null, trashed: null,
    comments: [],
  },
  {
    id: 'issue-0003', identifier: 'ENG-3', title: 'Retire the old importer', team: 'ENG',
    description: 'Archived. The codeword PUMICE must never be found.', priorityLabel: 'Low', estimate: null,
    state: { name: 'Canceled', type: 'canceled' }, assignee: null, creator: lnPerson('Alice'),
    project: null, cycle: null, labels: [], createdAt: at(20), updatedAt: at(320), archivedAt: at(320), trashed: null,
    comments: [],
  },
]

const lnUrl = i => `https://linear.app/fake/issue/${i.identifier}`
// A GraphQL connection's page: `first` capped by --page-size, `after` an offset.
const lnPage = (items, first, after) => {
  const start = after ? Number(after) : 0
  const size = Math.min(PAGE_SIZE, Math.max(1, Number(first ?? 50)))
  const end = Math.min(items.length, start + size)
  return { slice: items.slice(start, end), pageInfo: { hasNextPage: end < items.length, endCursor: String(end) } }
}
const lnComments = (issue, after) => {
  const { slice, pageInfo } = lnPage(issue.comments, 50, after)
  return { nodes: slice, pageInfo }
}

// The data for one operation; null for an issue Linear does not have, undefined for an operation
// this fake does not know.
function linearQuery(op, v) {
  if (op === 'Viewer') return { viewer: LN_VIEWER }
  if (op === 'Teams') {
    const { slice, pageInfo } = lnPage(lnTeams, 100, v.after)
    return { teams: { nodes: slice.map(({ key, name }) => ({ key, name })), pageInfo } }
  }
  if (op === 'Issues') {
    const keys = v.filter?.team?.key?.in
    const gt = v.filter?.updatedAt?.gt
    const items = lnIssues
      .filter(i => !keys || keys.includes(i.team))
      .filter(i => !gt || Date.parse(i.updatedAt) > Date.parse(gt))
      .filter(i => v.includeArchived || !i.archivedAt)
      .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
    const { slice, pageInfo } = lnPage(items, v.first, v.after)
    return {
      issues: {
        nodes: slice.map(i => ({
          id: i.id, identifier: i.identifier, title: i.title, url: lnUrl(i), updatedAt: i.updatedAt,
          archivedAt: i.archivedAt, trashed: i.trashed, creator: i.creator,
        })),
        pageInfo,
      },
    }
  }
  if (op !== 'Issue' && op !== 'IssueComments') return undefined
  const issue = lnIssues.find(i => i.id === v.id || i.identifier === v.id)
  if (!issue) return null
  if (op === 'IssueComments') return { issue: { comments: lnComments(issue, v.after) } }
  const team = lnTeams.find(t => t.key === issue.team)
  return {
    issue: {
      ...issue, url: lnUrl(issue), team: { key: team.key, name: team.name }, parent: null, dueDate: null,
      startedAt: null, completedAt: null, canceledAt: null,
      labels: { nodes: issue.labels.map(name => ({ name })) },
      comments: lnComments(issue, null),
    },
  }
}

function linear(req, res, url, rest) {
  if (rest !== 'graphql') return json(res, 404, { message: 'Not Found' })
  if (!(req.headers.authorization ?? '').trim()) {
    return json(res, 400, {
      errors: [{ message: 'Authentication required, not authenticated', extensions: { code: 'AUTHENTICATION_ERROR' } }],
    })
  }
  const chunks = []
  req.on('data', c => chunks.push(c))
  req.on('end', () => {
    let body
    try {
      body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
    } catch {
      return json(res, 400, { errors: [{ message: 'Body is not JSON', extensions: { code: 'BAD_USER_INPUT' } }] })
    }
    const data = linearQuery(body.operationName, body.variables ?? {})
    if (data === undefined) {
      return json(res, 400, {
        errors: [{ message: `Unknown operation ${body.operationName}`, extensions: { code: 'GRAPHQL_VALIDATION_FAILED' } }],
      })
    }
    if (data === null) {
      return json(res, 200, { errors: [{ message: 'Entity not found: Issue', extensions: { code: 'INPUT_ERROR' } }], data: null })
    }
    json(res, 200, { data })
  })
}

// -- Google ------------------------------------------------------------------------------------

// One Google account, its Drive, its mailbox and its calendar, each item holding a word only it has.
// Point a Google connection's API URL (More options) at http://127.0.0.1:<port>/google. To sign in
// through this server rather than Google's, start the app with
//   -Dproxyma.connectors.google.authorize-url=http://127.0.0.1:<port>/google/oauth/authorize
//   -Dproxyma.connectors.google.token-url=http://127.0.0.1:<port>/google/oauth/token
// and give the connection any client ID: the authorize page agrees at once and sends the browser
// back with a code, and the token endpoint answers any code, refresh token or service-account
// assertion with a token.
const G_USER = { displayName: 'Fake User', emailAddress: 'fake.user@example.com' }
// Mail and events are dated from the day the server starts, so they stay inside the sources'
// windows (a year of mail, a year back and half a year ahead of events) whenever it is run.
const G_DAY = 86_400_000
const G_TODAY = Math.floor(Date.now() / G_DAY) * G_DAY
const gAgo = days => new Date(G_TODAY - days * G_DAY).toISOString()
const G_DOC = 'application/vnd.google-apps.document'
const G_SHEET = 'application/vnd.google-apps.spreadsheet'
const G_SLIDES = 'application/vnd.google-apps.presentation'
const G_FOLDER = 'application/vnd.google-apps.folder'
const G_APPS = 'application/vnd.google-apps.'
const G_TEAM_DRIVE = { id: '0ADfakeTeamDrive', name: 'Engineering' }

// Drive. `body` is what an export or a download answers.
const gFiles = [
  { id: 'gdoc-travel', name: 'Travel policy', mimeType: G_DOC, owned: true, parents: ['root'],
    body: '# Travel policy\n\nBook economy for flights under six hours. The codeword for travel is CINNAMON.\n' },
  { id: 'gsheet-budget', name: 'Office budget', mimeType: G_SHEET, owned: true, parents: ['root'],
    body: 'item,cost\nChairs,1200\nCodeword,TANGERINE\n' },
  { id: 'gslides-kickoff', name: 'Kickoff deck', mimeType: G_SLIDES, owned: true, parents: ['root'],
    body: 'Kickoff\n\nGoals for the quarter. The codeword for the kickoff is OBSIDIAN.\n' },
  { id: 'gtxt-notes', name: 'meeting-notes.txt', mimeType: 'text/plain', owned: true, parents: ['root'],
    body: 'Notes from Monday. The codeword for the notes is HAZELNUT.\n' },
  { id: 'gfolder-team', name: 'Team', mimeType: G_FOLDER, owned: true, parents: ['root'] },
  { id: 'gdoc-onboarding', name: 'Onboarding checklist', mimeType: G_DOC, owned: true, parents: ['gfolder-team'],
    body: '# Onboarding checklist\n\nDay one: laptop, badge, accounts. The codeword for onboarding is JASMINE.\n' },
  { id: 'gdoc-partner', name: 'Partner agreement', mimeType: G_DOC, owned: false, shared: true, parents: [],
    body: '# Partner agreement\n\nShared by a partner. The codeword for the agreement is QUARTZ.\n' },
  { id: 'gdoc-runbook', name: 'Runbook', mimeType: G_DOC, owned: false, driveId: G_TEAM_DRIVE.id,
    parents: [G_TEAM_DRIVE.id], body: '# Runbook\n\nRestart the queue first. The codeword for the runbook is GRANITE.\n' },
]
// The change feed has nothing new after this token; any other token has expired (404).
const G_CHANGE_TOKEN = '20'

const gFileView = f => ({
  id: f.id, name: f.name, mimeType: f.mimeType, modifiedTime: gAgo(5), trashed: false,
  size: f.body === undefined ? undefined : String(Buffer.byteLength(f.body)),
  webViewLink: `https://drive.google.example/file/d/${f.id}/view`,
  ownedByMe: !!f.owned, sharedWithMeTime: f.shared ? gAgo(30) : undefined, driveId: f.driveId,
  parents: f.parents, lastModifyingUser: f.owned ? G_USER : { displayName: 'Partner Person' },
})

// Gmail. Bodies go out base64url-encoded, as Gmail's do.
const b64url = text => Buffer.from(text).toString('base64url')
const gLabels = [
  { id: 'INBOX', name: 'INBOX', type: 'system' },
  { id: 'SENT', name: 'SENT', type: 'system' },
  { id: 'DRAFT', name: 'DRAFT', type: 'system' },
  { id: 'Label_1', name: 'Clients', type: 'user' },
]
const gThreads = [
  { id: '18a0aurora', messages: [
    { id: 'm-aurora-1', daysAgo: 6, labels: ['INBOX'], from: 'Bo Tran <bo@example.com>', to: 'fake.user@example.com',
      subject: 'Q3 planning', text: 'Can we fix the Q3 plan by Friday? The codeword for planning is AURORA.' },
    { id: 'm-aurora-2', daysAgo: 5, labels: ['SENT'], from: 'Fake User <fake.user@example.com>', to: 'bo@example.com',
      subject: 'Re: Q3 planning', html: '<p>Friday works.</p><p>I will bring the numbers.</p>' },
    { id: 'm-aurora-3', daysAgo: 1, labels: ['DRAFT'], from: 'Fake User <fake.user@example.com>', to: 'bo@example.com',
      subject: 'Re: Q3 planning', text: 'An unsent draft that is never indexed: NEVERSENT.' },
  ] },
  { id: '18a0bramble', messages: [
    { id: 'm-bramble-1', daysAgo: 12, labels: ['INBOX', 'Label_1'], from: 'Carol Ng <carol@client.example>',
      to: 'fake.user@example.com', subject: 'Contract renewal', attachment: 'renewal-terms.pdf',
      text: 'The renewal terms are attached. The codeword for the renewal is BRAMBLE.' },
  ] },
  { id: '18a0clover', messages: [
    { id: 'm-clover-1', daysAgo: 40, labels: ['SENT'], from: 'Fake User <fake.user@example.com>',
      to: 'dan@example.com', subject: 'Invoice 1042', text: 'Invoice 1042 is paid. The codeword for the invoice is CLOVER.' },
  ] },
]
// The mailbox's history id: history from any other has expired (404), from this one is empty.
const G_HISTORY_ID = '7000'

const gHeaders = m => [
  { name: 'From', value: m.from }, { name: 'To', value: m.to }, { name: 'Subject', value: m.subject },
  { name: 'Date', value: new Date(G_TODAY - m.daysAgo * G_DAY).toUTCString() },
]
function gMessage(m, threadId, full) {
  const base = { id: m.id, threadId, labelIds: m.labels, internalDate: String(G_TODAY - m.daysAgo * G_DAY), historyId: G_HISTORY_ID }
  if (!full) return { ...base, payload: { headers: gHeaders(m) } }
  const text = m.html ?? m.text
  const textPart = { partId: '0', mimeType: m.html ? 'text/html' : 'text/plain', filename: '',
    body: { size: Buffer.byteLength(text), data: b64url(text) } }
  const parts = [textPart]
  if (m.attachment) {
    parts.push({ partId: '1', mimeType: 'application/pdf', filename: m.attachment, body: { attachmentId: `att-${m.id}`, size: 2048 } })
  }
  return { ...base, snippet: (m.text ?? '').slice(0, 80), payload: { mimeType: 'multipart/mixed', headers: gHeaders(m), parts } }
}

// Calendar. Times are whole hours on days counted from today.
const gAt = (days, hour) => new Date(G_TODAY + days * G_DAY + hour * 3_600_000).toISOString()
const gEvents = [
  { id: 'evdesignreview', summary: 'Design review', start: { dateTime: gAt(3, 9) }, end: { dateTime: gAt(3, 10) },
    location: 'Room 4', hangoutLink: 'https://meet.google.example/abc-defg-hij',
    attendees: [{ email: 'bo@example.com', displayName: 'Bo Tran', responseStatus: 'accepted' }],
    description: 'Walk through the new sources page. The codeword for the review is SEQUOIA.' },
  { id: 'evretro', summary: 'Sprint retro', start: { dateTime: gAt(-10, 15) }, end: { dateTime: gAt(-10, 16) },
    description: '<p>What went well, what did not.</p><p>The codeword for the retro is LANTERN.</p>' },
  { id: 'evoffsite', summary: 'Company offsite', start: { date: gAt(20, 0).slice(0, 10) }, end: { date: gAt(22, 0).slice(0, 10) },
    description: 'Two days by the sea. The codeword for the offsite is MERIDIAN.' },
]
const G_SYNC_TOKEN = 'cal-sync-1'
const gEventView = e => ({
  kind: 'calendar#event', status: 'confirmed', updated: gAgo(2), created: gAgo(60),
  htmlLink: `https://calendar.google.example/event?eid=${e.id}`,
  organizer: { email: G_USER.emailAddress, displayName: G_USER.displayName, self: true }, ...e,
})

// Google's pages: the page token is an offset into the list, a page as large as asked and PAGE_SIZE allow.
function gPage(url, items, key, last = {}) {
  const start = Math.max(0, Number(url.searchParams.get('pageToken') ?? '0') || 0)
  const asked = Number(url.searchParams.get('pageSize') ?? url.searchParams.get('maxResults') ?? '100')
  const size = Math.min(PAGE_SIZE, Math.max(1, asked))
  const page = { [key]: items.slice(start, start + size) }
  if (start + size < items.length) page.nextPageToken = String(start + size)
  else Object.assign(page, last)
  return page
}

function googleError(res, status, message) {
  json(res, status, { error: { code: status, message, errors: [{ message, reason: status === 404 ? 'notFound' : 'invalid' }] } })
}

function googleOAuth(req, res, url, path) {
  if (path === 'authorize') {
    // Agrees at once: back to the app with a code and the state it was sent with.
    let back
    try {
      back = new URL(url.searchParams.get('redirect_uri') ?? '')
    } catch {
      return json(res, 400, { error: 'invalid_request' })
    }
    back.searchParams.set('code', 'fake-google-code')
    back.searchParams.set('state', url.searchParams.get('state') ?? '')
    res.writeHead(302, { Location: back.toString() })
    return res.end()
  }
  if (path === 'token' && req.method === 'POST') {
    let body = ''
    req.on('data', chunk => { body += chunk })
    req.on('end', () => {
      const grant = new URLSearchParams(body).get('grant_type')
      if (!['authorization_code', 'refresh_token', 'urn:ietf:params:oauth:grant-type:jwt-bearer'].includes(grant)) {
        return json(res, 400, { error: 'unsupported_grant_type' })
      }
      const answer = { access_token: 'fake-google-access-token', expires_in: 3600, token_type: 'Bearer' }
      if (grant === 'authorization_code') answer.refresh_token = 'fake-google-refresh-token'
      json(res, 200, answer)
    })
    return
  }
  return googleError(res, 404, 'Not Found')
}

function google(req, res, url, rest) {
  if (rest.startsWith('oauth/')) return googleOAuth(req, res, url, rest.slice('oauth/'.length))
  if (req.method !== 'GET') return json(res, 405, { message: 'Read only' })
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) {
    return googleError(res, 401, 'Request is missing required authentication credential.')
  }
  const q = url.searchParams

  // Drive
  if (rest === 'drive/v3/about') return json(res, 200, { user: G_USER })
  if (rest === 'drive/v3/changes/startPageToken') return json(res, 200, { startPageToken: G_CHANGE_TOKEN })
  if (rest === 'drive/v3/changes') {
    if (q.get('pageToken') !== G_CHANGE_TOKEN) return googleError(res, 404, 'Page token is not valid')
    return json(res, 200, { changes: [], newStartPageToken: G_CHANGE_TOKEN })
  }
  let m = rest.match(/^drive\/v3\/drives\/([^/]+)$/)
  if (m) {
    return decodeURIComponent(m[1]) === G_TEAM_DRIVE.id ? json(res, 200, G_TEAM_DRIVE) : googleError(res, 404, 'Shared drive not found')
  }
  if (rest === 'drive/v3/files') {
    const query = q.get('q') ?? ''
    const parent = query.match(/'([^']+)' in parents/)
    let files = gFiles.filter(f => !q.get('driveId') || f.driveId === q.get('driveId'))
    if (parent) {
      files = files.filter(f => f.parents.includes(parent[1]))
    } else if (!q.get('driveId')) {
      const mine = query.includes("'me' in owners")
      const shared = query.includes('sharedWithMe = true')
      files = files.filter(f => !f.driveId && ((mine && f.owned) || (shared && f.shared)))
    }
    return json(res, 200, gPage(url, files.map(gFileView), 'files'))
  }
  m = rest.match(/^drive\/v3\/files\/([^/]+)(\/export)?$/)
  if (m) {
    const f = gFiles.find(x => x.id === decodeURIComponent(m[1]))
    if (!f) return googleError(res, 404, `File not found: ${m[1]}`)
    if (m[2]) {
      if (!f.mimeType.startsWith(G_APPS) || f.body === undefined) return googleError(res, 403, 'Only Google files can be exported')
      res.writeHead(200, { 'Content-Type': `${q.get('mimeType') ?? 'text/plain'}; charset=utf-8` })
      return res.end(f.body)
    }
    if (q.get('alt') === 'media') {
      if (f.mimeType.startsWith(G_APPS)) return googleError(res, 403, 'Use export for Google files')
      res.writeHead(200, { 'Content-Type': f.mimeType })
      return res.end(f.body)
    }
    return json(res, 200, gFileView(f))
  }

  // Gmail
  if (rest === 'gmail/v1/users/me/profile') {
    return json(res, 200, { emailAddress: G_USER.emailAddress, messagesTotal: 5, threadsTotal: gThreads.length, historyId: G_HISTORY_ID })
  }
  if (rest === 'gmail/v1/users/me/labels') return json(res, 200, { labels: gLabels })
  if (rest === 'gmail/v1/users/me/history') {
    if (q.get('startHistoryId') !== G_HISTORY_ID) return googleError(res, 404, 'Requested entity was not found.')
    return json(res, 200, { historyId: G_HISTORY_ID })
  }
  if (rest === 'gmail/v1/users/me/threads') {
    const label = q.get('labelIds')
    const after = Number((q.get('q') ?? '').match(/after:(\d+)/)?.[1] ?? '0') * 1000
    const threads = gThreads
      .filter(t => t.messages.some(x => (!label || x.labels.includes(label)) && G_TODAY - x.daysAgo * G_DAY >= after))
      .map(t => ({ id: t.id, historyId: G_HISTORY_ID, snippet: (t.messages[0].text ?? '').slice(0, 80) }))
    return json(res, 200, gPage(url, threads, 'threads', { resultSizeEstimate: threads.length }))
  }
  m = rest.match(/^gmail\/v1\/users\/me\/threads\/([^/]+)$/)
  if (m) {
    const t = gThreads.find(x => x.id === decodeURIComponent(m[1]))
    if (!t) return googleError(res, 404, 'Requested entity was not found.')
    const full = q.get('format') !== 'metadata'
    return json(res, 200, { id: t.id, historyId: G_HISTORY_ID, messages: t.messages.map(x => gMessage(x, t.id, full)) })
  }

  // Calendar
  m = rest.match(/^calendar\/v3\/calendars\/([^/]+)(\/events(?:\/([^/]+))?)?$/)
  if (m) {
    const calendar = decodeURIComponent(m[1])
    if (calendar !== 'primary' && calendar !== G_USER.emailAddress) return googleError(res, 404, 'Not Found')
    if (!m[2]) return json(res, 200, { kind: 'calendar#calendar', id: G_USER.emailAddress, summary: G_USER.displayName, timeZone: 'UTC' })
    if (m[3]) {
      const e = gEvents.find(x => x.id === decodeURIComponent(m[3]))
      return e ? json(res, 200, gEventView(e)) : googleError(res, 404, 'Not Found')
    }
    if (q.has('syncToken')) {
      if (q.get('syncToken') !== G_SYNC_TOKEN) return googleError(res, 410, 'Sync token is no longer valid, a full sync is required.')
      return json(res, 200, { kind: 'calendar#events', items: [], nextSyncToken: G_SYNC_TOKEN })
    }
    const min = Date.parse(q.get('timeMin') ?? '1970-01-01T00:00:00Z')
    const max = Date.parse(q.get('timeMax') ?? '2999-01-01T00:00:00Z')
    const items = gEvents
      .filter(e => Date.parse(e.end.dateTime ?? e.end.date) > min && Date.parse(e.start.dateTime ?? e.start.date) < max)
      .map(gEventView)
    return json(res, 200, { kind: 'calendar#events', ...gPage(url, items, 'items', { nextSyncToken: G_SYNC_TOKEN }) })
  }
  return googleError(res, 404, 'Not Found')
}
// Its OAuth token endpoint takes a POST; everything else it answers is read only.

// -- Atlassian: Jira, Confluence, Bitbucket Cloud -------------------------------------------------

// Every Atlassian service here takes either credential the connectors send - a personal access
// token as a bearer (Data Center) or an email and an API token as Basic (Cloud) - and refuses a
// request with neither, as the real ones do. Which API a request reaches says which product line it
// is asking: Jira's /rest/api/2 is Data Center, /rest/api/3 is Cloud; Confluence's /rest/api is
// Data Center, /wiki/... is Cloud.
const atlassianAuthorized = req => /^(Bearer|Basic) \S+/.test(req.headers.authorization ?? '')

// Jira's timestamp shape: 2026-09-01T09:00:00.000+0000.
const jiraTime = minutes => new Date(T0 + minutes * 60_000).toISOString().replace('Z', '+0000')

const JIRA_PROJECT = 'HELP'
const jiraPerson = (name, accountId) => ({ displayName: name, name: name.toLowerCase(), accountId, emailAddress: `${name.toLowerCase()}@example.com` })
const jiraIssues = [
  {
    key: 'HELP-1', summary: 'Printer on the third floor jams every morning', type: 'Bug', status: 'Open',
    description: 'The printer jams before nine. The codeword for the printer is BERGAMOT.',
    reporter: jiraPerson('Alice', 'acc-alice'), assignee: jiraPerson('Bob', 'acc-bob'), created: 0, updated: 120,
    comments: [{ author: jiraPerson('Bob', 'acc-bob'), created: 60, body: 'Replaced the feed roller; watching it.' }],
  },
  {
    key: 'HELP-2', summary: 'New starters need a laptop checklist', type: 'Task', status: 'In Progress',
    description: 'Write the checklist IT walks through. The codeword for onboarding laptops is SUMAC.',
    reporter: jiraPerson('Carol', 'acc-carol'), assignee: null, created: 10, updated: 220,
    comments: [],
  },
  {
    key: 'HELP-3', summary: 'VPN drops when switching networks', type: 'Bug', status: 'Done',
    description: 'Moving from Wi-Fi to cable drops the tunnel. The codeword for the VPN is GALANGAL.',
    reporter: jiraPerson('Bob', 'acc-bob'), assignee: jiraPerson('Alice', 'acc-alice'), created: 20, updated: 320,
    comments: [{ author: jiraPerson('Alice', 'acc-alice'), created: 300, body: 'Fixed by the new client profile.' }],
  },
]

// Plain text as Atlassian Document Format - one paragraph, as Jira Cloud returns a description.
const adf = text => ({ type: 'doc', version: 1, content: [{ type: 'paragraph', content: [{ type: 'text', text }] }] })

// project = "KEY" [AND updated >= "yyyy-MM-dd HH:mm"] - the only JQL the connector writes. A project
// this server does not hold is the 400 Jira answers.
function jiraQuery(jql) {
  const project = /project\s*=\s*"?([A-Za-z0-9_]+)"?/.exec(jql ?? '')?.[1]?.toUpperCase()
  const since = /updated\s*>=\s*"([^"]+)"/.exec(jql ?? '')?.[1]
  if (project !== JIRA_PROJECT) return null
  const cutoff = since ? Date.parse(since.replace(' ', 'T') + ':00Z') : -Infinity
  return jiraIssues.filter(i => T0 + i.updated * 60_000 >= cutoff).sort((a, b) => a.key.localeCompare(b.key))
}

const jiraListed = i => ({
  id: String(10000 + Number(i.key.split('-')[1])), key: i.key,
  fields: { summary: i.summary, issuetype: { name: i.type }, updated: jiraTime(i.updated), reporter: i.reporter },
})

function jiraIssue(i, cloud) {
  const text = s => (cloud ? adf(s) : s)
  return {
    id: String(10000 + Number(i.key.split('-')[1])), key: i.key,
    names: { customfield_10010: 'Team' },
    fields: {
      summary: i.summary, issuetype: { name: i.type }, status: { name: i.status }, priority: { name: 'Medium' },
      reporter: i.reporter, assignee: i.assignee, created: jiraTime(i.created), updated: jiraTime(i.updated),
      labels: ['it'], components: [], fixVersions: [], versions: [], description: text(i.description),
      customfield_10010: { value: 'IT support' },
      comment: { comments: i.comments.map(c => ({ author: c.author, created: jiraTime(c.created), body: text(c.body) })) },
    },
    changelog: { histories: [] },
  }
}

function jira(req, res, url, rest, body) {
  if (!atlassianAuthorized(req)) return json(res, 401, { errorMessages: ['You are not authenticated.'] })
  const m = rest.match(/^rest\/api\/(2|3)\/(.*)$/)
  if (!m) return notFound(res)
  const [, version, tail] = m
  const cloud = version === '3'
  if (tail === 'myself') {
    return json(res, 200, { ...jiraPerson('Fake User', 'acc-fake'), timeZone: 'UTC' })
  }
  if (tail === 'search' && !cloud) {
    // Data Center: by offset, with a total.
    const issues = jiraQuery(url.searchParams.get('jql'))
    if (!issues) return json(res, 400, { errorMessages: ["The value does not exist for the field 'project'."] })
    const startAt = Math.max(0, Number(url.searchParams.get('startAt') ?? '0'))
    const max = Math.min(PAGE_SIZE, Math.max(1, Number(url.searchParams.get('maxResults') ?? '50')))
    return json(res, 200, { startAt, maxResults: max, total: issues.length,
      issues: issues.slice(startAt, startAt + max).map(jiraListed) })
  }
  if (tail === 'search') return json(res, 410, { errorMessages: ['This endpoint has been removed. Use /search/jql.'] })
  if (tail === 'search/jql' && cloud) {
    // Cloud: POST, by nextPageToken, no total.
    const issues = jiraQuery(body?.jql)
    if (!issues) return json(res, 400, { errorMessages: ["The value does not exist for the field 'project'."] })
    const start = Number(body?.nextPageToken ?? '0') || 0
    const max = Math.min(PAGE_SIZE, Math.max(1, Number(body?.maxResults ?? 50)))
    const page = issues.slice(start, start + max)
    const last = start + max >= issues.length
    // The agent's search names status and description; the sync's listing does not.
    const fields = Array.isArray(body?.fields) ? body.fields : []
    const shape = fields.includes('description') ? i => jiraIssue(i, cloud) : jiraListed
    return json(res, 200, { issues: page.map(shape), ...(last ? { isLast: true } : { nextPageToken: String(start + max), isLast: false }) })
  }
  const issue = tail.match(/^issue\/([^/]+)$/)
  if (issue) {
    const found = jiraIssues.find(i => i.key === decodeURIComponent(issue[1]).toUpperCase())
    if (!found) return json(res, 404, { errorMessages: ['Issue does not exist or you do not have permission to see it.'] })
    return json(res, 200, jiraIssue(found, cloud))
  }
  return notFound(res)
}

const CF_SPACE = { id: '98304', key: 'TEAM', name: 'Team handbook' }
const cfPeople = { 'acc-alice': 'Alice', 'acc-bob': 'Bob', 'acc-carol': 'Carol' }
const cfPages = [
  { id: '1001', title: 'Expense policy', parent: null, author: 'acc-alice', editor: 'acc-bob', created: 0, updated: 400,
    html: '<p>Claims go in within thirty days. The codeword for expenses is <strong>OLEANDER</strong>.</p>',
    labels: ['finance'] },
  { id: '1002', title: 'Travel booking', parent: '1001', author: 'acc-bob', editor: 'acc-bob', created: 30, updated: 410,
    html: '<p>Book through the portal. The codeword for travel is WISTERIA.</p><ul><li>Trains first</li><li>Flights over 500 km</li></ul>',
    labels: [] },
  { id: '1003', title: 'Office hours', parent: null, author: 'acc-carol', editor: 'acc-carol', created: 60, updated: 420,
    html: '<p>The office opens at eight. The codeword for the office is HIBISCUS.</p>', labels: ['office'] },
]
const cfComments = [
  { id: '2001', page: '1001', parent: null, author: 'acc-carol', created: 430, html: '<p>Does this cover taxis?</p>' },
  { id: '2002', page: '1001', parent: '2001', author: 'acc-alice', created: 440, html: '<p>Yes, with a receipt.</p>' },
]
const cfTime = minutes => new Date(T0 + minutes * 60_000).toISOString()
const cfWebui = p => `/spaces/${CF_SPACE.key}/pages/${p.id}/${p.title.replace(/ /g, '+')}`

// CQL's lastModified > "yyyy-MM-dd HH:mm" - the only CQL the connector writes.
function cfSince(cql) {
  const since = /lastModified\s*>\s*"([^"]+)"/i.exec(cql ?? '')?.[1]
  return since ? Date.parse(since.replace(' ', 'T') + ':00Z') : -Infinity
}

function cfOffset(res, url, items, base) {
  const start = Math.max(0, Number(url.searchParams.get('start') ?? '0'))
  const limit = Math.min(PAGE_SIZE, Math.max(1, Number(url.searchParams.get('limit') ?? '25')))
  const page = items.slice(start, start + limit)
  const links = { base }
  if (start + limit < items.length) {
    const next = new URL(url)
    next.searchParams.set('start', String(start + limit))
    links.next = next.pathname.replace(/^\/confluence/, '') + next.search
  }
  json(res, 200, { results: page, start, limit, size: page.length, _links: links })
}

function cfCursor(res, url, items, path) {
  const start = Number(url.searchParams.get('cursor') ?? '0') || 0
  const limit = Math.min(PAGE_SIZE, Math.max(1, Number(url.searchParams.get('limit') ?? '25')))
  const links = {}
  if (start + limit < items.length) {
    const next = new URL(url)
    next.searchParams.set('cursor', String(start + limit))
    links.next = path(next)
  }
  json(res, 200, { results: items.slice(start, start + limit), _links: links })
}

const cfV1Page = (p, site) => ({
  id: p.id, type: 'page', status: 'current', title: p.title,
  version: { when: cfTime(p.updated), by: { displayName: cfPeople[p.editor] } },
  _links: { webui: `/display/${CF_SPACE.key}/${p.title.replace(/ /g, '+')}` },
})

function confluence(req, res, url, rest, body) {
  if (!atlassianAuthorized(req)) return json(res, 401, { message: 'Unauthorized' })
  // Served at /confluence and at the /atlassian site; links are relative to whichever was asked.
  const prefix = `/${url.pathname.split('/')[1]}`
  const site = `${url.origin}${prefix}`
  const fromSite = next => next.pathname.slice(prefix.length) + next.search

  // -- Data Center: /rest/api ----------------------------------------------------------------
  if (rest === 'rest/api/user/current') return json(res, 200, { type: 'known', username: 'fake', displayName: 'Fake User' })
  if (rest.startsWith('rest/api/space/')) {
    return decodeURIComponent(rest.slice('rest/api/space/'.length)) === CF_SPACE.key
      ? json(res, 200, { key: CF_SPACE.key, name: CF_SPACE.name }) : notFound(res)
  }
  if (rest === 'rest/api/content') {
    if (url.searchParams.get('spaceKey') !== CF_SPACE.key) return cfOffset(res, url, [], site)
    return cfOffset(res, url, cfPages.map(p => cfV1Page(p, site)), site)
  }
  if (rest === 'rest/api/content/search') {
    const since = cfSince(url.searchParams.get('cql'))
    const hits = [
      ...cfPages.filter(p => T0 + p.updated * 60_000 > since).map(p => cfV1Page(p, site)),
      ...cfComments.filter(c => T0 + c.created * 60_000 > since).map(c => {
        const page = cfPages.find(p => p.id === c.page)
        return { id: c.id, type: 'comment', title: `Re: ${page.title}`, version: { when: cfTime(c.created) },
          container: { id: page.id, type: 'page', title: page.title } }
      }),
    ]
    return cfOffset(res, url, hits, site)
  }
  let m = rest.match(/^rest\/api\/content\/(\d+)$/)
  if (m) {
    const p = cfPages.find(x => x.id === m[1])
    if (!p) return notFound(res)
    const ancestors = p.parent ? [{ id: p.parent, title: cfPages.find(x => x.id === p.parent).title }] : []
    return json(res, 200, {
      ...cfV1Page(p, site), space: { key: CF_SPACE.key, name: CF_SPACE.name },
      history: { createdBy: { displayName: cfPeople[p.author] }, createdDate: cfTime(p.created) },
      ancestors, metadata: { labels: { results: p.labels.map(name => ({ name })) } },
      body: { view: { value: p.html } },
    })
  }
  m = rest.match(/^rest\/api\/content\/(\d+)\/child\/comment$/)
  if (m) {
    const items = cfComments.filter(c => c.page === m[1]).map(c => ({
      id: c.id, type: 'comment', version: { when: cfTime(c.created), by: { displayName: cfPeople[c.author] } },
      body: { view: { value: c.html } },
    }))
    return cfOffset(res, url, items, site)
  }

  // -- Cloud: /wiki/api/v2, and v1's search ----------------------------------------------------
  if (rest === 'wiki/rest/api/user/current') return json(res, 200, { type: 'known', accountId: 'acc-fake', displayName: 'Fake User' })
  if (rest === 'wiki/api/v2/spaces') {
    const keys = (url.searchParams.get('keys') ?? '').split(',')
    return json(res, 200, { results: keys.includes(CF_SPACE.key) ? [CF_SPACE] : [], _links: {} })
  }
  const v2Page = p => ({
    id: p.id, status: 'current', title: p.title, spaceId: CF_SPACE.id, parentId: p.parent, authorId: p.author,
    createdAt: cfTime(p.created), version: { number: 2, createdAt: cfTime(p.updated), authorId: p.editor },
    _links: { webui: cfWebui(p) },
  })
  m = rest.match(/^wiki\/api\/v2\/spaces\/(\d+)\/pages$/)
  if (m) {
    const items = m[1] === CF_SPACE.id ? cfPages.map(v2Page) : []
    return cfCursor(res, url, items, fromSite)
  }
  m = rest.match(/^wiki\/api\/v2\/pages\/(\d+)$/)
  if (m) {
    const p = cfPages.find(x => x.id === m[1])
    if (!p) return notFound(res)
    const format = url.searchParams.get('body-format')
    return json(res, 200, { ...v2Page(p), labels: { results: p.labels.map(name => ({ name })) },
      body: format ? { [format]: { value: p.html, representation: format } } : {} })
  }
  m = rest.match(/^wiki\/api\/v2\/pages\/(\d+)\/ancestors$/)
  if (m) {
    const p = cfPages.find(x => x.id === m[1])
    if (!p) return notFound(res)
    return json(res, 200, { results: p.parent ? [{ id: p.parent, type: 'page' }] : [], _links: {} })
  }
  const v2Comment = c => ({ id: c.id, status: 'current', pageId: c.page, version: { createdAt: cfTime(c.created), authorId: c.author },
    body: { storage: { value: c.html, representation: 'storage' } } })
  m = rest.match(/^wiki\/api\/v2\/pages\/(\d+)\/(footer|inline)-comments$/)
  if (m) {
    const items = m[2] === 'footer' ? cfComments.filter(c => c.page === m[1] && !c.parent).map(v2Comment) : []
    return cfCursor(res, url, items, fromSite)
  }
  m = rest.match(/^wiki\/api\/v2\/footer-comments\/(\d+)\/children$/)
  if (m) {
    return cfCursor(res, url, cfComments.filter(c => c.parent === m[1]).map(v2Comment), fromSite)
  }
  if (rest === 'wiki/api/v2/users-bulk' && req.method === 'POST') {
    const ids = Array.isArray(body?.accountIds) ? body.accountIds : []
    return json(res, 200, { results: ids.filter(id => cfPeople[id]).map(id => ({ accountId: id, displayName: cfPeople[id], publicName: cfPeople[id] })) })
  }
  if (rest === 'wiki/rest/api/space') {
    return json(res, 200, { results: [{ key: CF_SPACE.key, name: CF_SPACE.name }], start: 0, limit: 25, size: 1, _links: {} })
  }
  if (rest === 'wiki/rest/api/content/search') {
    return json(res, 200, { results: cfPages.map(p => cfV1Page(p, `${site}/wiki`)), start: 0, limit: 25, size: cfPages.length, _links: {} })
  }
  if (rest === 'wiki/rest/api/search') {
    const since = cfSince(url.searchParams.get('cql'))
    const hits = [
      ...cfPages.filter(p => T0 + p.updated * 60_000 > since).map(p => ({
        content: { id: p.id, type: 'page', title: p.title, version: { when: cfTime(p.updated) }, _links: { webui: cfWebui(p) } },
        lastModified: cfTime(p.updated),
      })),
      ...cfComments.filter(c => T0 + c.created * 60_000 > since).map(c => {
        const page = cfPages.find(p => p.id === c.page)
        return { content: { id: c.id, type: 'comment', title: `Re: ${page.title}`, version: { when: cfTime(c.created) },
          container: { id: page.id, type: 'page', title: page.title, _links: { webui: cfWebui(page) } } }, lastModified: cfTime(c.created) }
      }),
    ]
    // v1's next link is relative to the site's /wiki.
    return cfCursor(res, url, hits, next => fromSite(next).replace(/^\/wiki/, ''))
  }
  return notFound(res)
}

const BB_WORKSPACE = 'acme-team'
const BB_REPO = 'field-guide'
const BB_WEB = `https://bitbucket.example/${BB_WORKSPACE}/${BB_REPO}`
const BB_HEAD = 'c0ffee1234567890'
const bbUser = name => ({ display_name: name, nickname: name.toLowerCase(), type: 'user' })
const bbPulls = [
  { id: 1, title: 'Add the field safety checklist', state: 'MERGED', author: bbUser('Alice'), source: 'safety', created: 0, updated: 150,
    description: 'Adds docs/safety.md. The codeword for field safety is SAMPHIRE.', reviewers: [bbUser('Bob')],
    participants: [{ user: bbUser('Bob'), role: 'REVIEWER', approved: true, state: 'approved', participated_on: 140 }] },
  { id: 2, title: 'Translate the glossary', state: 'OPEN', author: bbUser('Carol'), source: 'glossary', created: 40, updated: 250,
    description: 'First pass at the glossary in three languages. The codeword for the glossary is QUINCE.', reviewers: [],
    participants: [] },
  { id: 3, title: 'Remove the old map tiles', state: 'DECLINED', author: bbUser('Bob'), source: 'tiles', created: 80, updated: 350,
    description: 'The tiles moved to the asset store. The codeword for the map tiles is LOVAGE.', reviewers: [bbUser('Alice')],
    participants: [{ user: bbUser('Alice'), role: 'REVIEWER', approved: false, state: 'changes_requested', participated_on: 340 }] },
]
const bbComments = {
  1: [{ id: 11, user: bbUser('Bob'), created: 100, raw: 'Link the first-aid page here.', inline: { path: 'docs/safety.md' } },
      { id: 12, user: bbUser('Alice'), created: 110, raw: 'Linked.', parent: { id: 11 } }],
  2: [],
  3: [{ id: 31, user: bbUser('Alice'), created: 330, raw: 'Keep one tile for the offline demo.' }],
}
const bbFiles = {
  'README.md': '# Field guide\n\nEverything a crew needs outside. The codeword for the guide is CHERVIL.\n',
  'docs/safety.md': '# Safety\n\nCheck the weather twice. The codeword for the checklist is SORREL.\n',
  'docs/glossary.txt': 'Glossary\n\nTerms the crews use.\n',
  'tools/build.sh': 'echo not documentation\n',
}
const bbTime = minutes => new Date(T0 + minutes * 60_000).toISOString().replace('Z', '+00:00')

// Bitbucket's pages: page numbers behind a whole `next` URL, absent on the last page.
function bbPaged(res, url, items) {
  const page = Math.max(1, Number(url.searchParams.get('page') ?? '1'))
  const pagelen = Math.min(PAGE_SIZE, Math.max(1, Number(url.searchParams.get('pagelen') ?? '10')))
  const out = { pagelen, page, size: items.length, values: items.slice((page - 1) * pagelen, page * pagelen) }
  if (page * pagelen < items.length) {
    const next = new URL(url)
    next.searchParams.set('page', String(page + 1))
    out.next = next.toString()
  }
  json(res, 200, out)
}

function bitbucket(req, res, url, rest) {
  if (!atlassianAuthorized(req)) return json(res, 401, { type: 'error', error: { message: 'Unauthorized' } })
  const base = `${url.origin}/bitbucket/2.0`
  const m0 = rest.match(/^2\.0\/(.*)$/)
  if (!m0) return notFound(res)
  const path = m0[1]
  if (path === 'user') return json(res, 200, { ...bbUser('Fake User'), username: 'fake-user' })
  const m = path.match(/^repositories\/([^/]+)\/([^/]+)(?:\/(.*))?$/)
  if (!m || m[1].toLowerCase() !== BB_WORKSPACE || m[2].toLowerCase() !== BB_REPO) {
    return json(res, 404, { type: 'error', error: { message: 'Repository not found' } })
  }
  const tail = m[3] ?? ''
  const pullView = p => ({
    id: p.id, title: p.title, state: p.state, author: p.author, description: p.description,
    summary: { raw: p.description }, source: { branch: { name: p.source } }, destination: { branch: { name: 'main' } },
    reviewers: p.reviewers, participants: p.participants.map(x => ({ ...x, participated_on: bbTime(x.participated_on) })),
    created_on: bbTime(p.created), updated_on: bbTime(p.updated),
    links: { html: { href: `${BB_WEB}/pull-requests/${p.id}` }, self: { href: `${base}/repositories/${BB_WORKSPACE}/${BB_REPO}/pullrequests/${p.id}` } },
  })
  if (tail === '') {
    return json(res, 200, { full_name: `${BB_WORKSPACE}/${BB_REPO}`, mainbranch: { name: 'main' }, links: { html: { href: BB_WEB } } })
  }
  if (tail === 'pullrequests') {
    const states = url.searchParams.getAll('state')
    const since = /updated_on\s*>\s*(\S+)/.exec(url.searchParams.get('q') ?? '')?.[1]
    const items = bbPulls
      .filter(p => (states.length ? states : ['OPEN']).includes(p.state))
      .filter(p => !since || T0 + p.updated * 60_000 > Date.parse(since))
      .sort((a, b) => a.created - b.created)
      .map(pullView)
    return bbPaged(res, url, items)
  }
  let n = tail.match(/^pullrequests\/(\d+)$/)
  if (n) {
    const p = bbPulls.find(x => x.id === Number(n[1]))
    return p ? json(res, 200, pullView(p)) : json(res, 404, { type: 'error', error: { message: 'Not found' } })
  }
  n = tail.match(/^pullrequests\/(\d+)\/comments$/)
  if (n) {
    const list = bbComments[Number(n[1])]
    if (!list) return json(res, 404, { type: 'error', error: { message: 'Not found' } })
    return bbPaged(res, url, list.map(c => ({ id: c.id, user: c.user, created_on: bbTime(c.created), content: { raw: c.raw },
      ...(c.inline ? { inline: c.inline } : {}), ...(c.parent ? { parent: c.parent } : {}), deleted: false })))
  }
  if (tail === 'refs/branches/main') {
    return json(res, 200, { name: 'main', target: { hash: BB_HEAD, date: bbTime(500) } })
  }
  n = tail.match(/^src\/([^/]+)\/(.*)$/)
  if (n) {
    const ref = decodeURIComponent(n[1])
    if (ref !== BB_HEAD && ref !== 'main') return json(res, 404, { type: 'error', error: { message: 'Commit not found' } })
    const path = n[2].split('/').map(decodeURIComponent).join('/')
    if (path === '' || path.endsWith('/')) {
      const files = Object.entries(bbFiles).filter(([p]) => p.startsWith(path)).map(([p, text]) => ({
        type: 'commit_file', path: p, size: Buffer.byteLength(text), commit: { hash: BB_HEAD },
      }))
      const dirs = [...new Set(files.map(f => f.path.split('/').slice(0, -1).join('/')).filter(Boolean))]
        .map(d => ({ type: 'commit_directory', path: d, commit: { hash: BB_HEAD } }))
      return bbPaged(res, url, [...dirs, ...files])
    }
    const text = bbFiles[path]
    if (text === undefined) return json(res, 404, { type: 'error', error: { message: 'No such file' } })
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
    return res.end(text)
  }
  return notFound(res)
}


// -- Notion ------------------------------------------------------------------------------------

// One workspace shared with the integration: a handbook with a page inside a toggle and a projects
// database of two rows, a page of meeting notes, and a page in the trash. A word per page that a
// search can find. Answers are Notion-Version 2026-03-11's: in_trash, data sources, page markdown.
const ntId = n => `0a7e0000-0000-4000-8000-${String(n).padStart(12, '0')}`
const NT_ALICE = { object: 'user', id: ntId(100), type: 'person', name: 'Alice Nguyen', person: { email: 'alice@fake.example' } }
const NT_BOT = {
  object: 'user', id: ntId(101), type: 'bot', name: 'Proxyma',
  bot: { owner: { type: 'workspace', workspace: true }, workspace_name: 'Fake Corp', workspace_id: ntId(102) },
}
const NT_HANDBOOK = ntId(1)
const NT_TOGGLE = ntId(2)
const NT_ONBOARDING = ntId(3)
const NT_PROJECTS_DB = ntId(4)
const NT_PROJECTS_DS = ntId(5)
const NT_APOLLO = ntId(6)
const NT_BILLING = ntId(7)
const NT_NOTES = ntId(8)
const NT_OLD = ntId(9)
const ntWorkspace = { type: 'workspace', workspace: true }
const ntRow = { type: 'data_source_id', data_source_id: NT_PROJECTS_DS, database_id: NT_PROJECTS_DB }
const ntText = text => [{ type: 'text', text: { content: text, link: null }, plain_text: text, href: null }]

// Each page: its parent, when it was last edited (minutes after T0), its properties and its markdown.
const ntPages = {
  [NT_HANDBOOK]: { title: 'Team Handbook', parent: ntWorkspace, at: 600,
    markdown: '## Welcome\n\nHow the team works. The handbook codeword is MARIGOLD.' },
  [NT_ONBOARDING]: { title: 'Onboarding', parent: { type: 'block_id', block_id: NT_TOGGLE }, at: 300,
    markdown: 'First week checklist. The onboarding codeword is PERIWINKLE.' },
  [NT_APOLLO]: { title: 'Apollo launch', parent: ntRow, at: 900, titleKey: 'Name', props: {
    Status: { id: 'st', type: 'status', status: { name: 'In progress' } },
    Owner: { id: 'ow', type: 'people', people: [{ object: 'user', id: NT_ALICE.id }] },
    Due: { id: 'du', type: 'date', date: { start: '2026-11-01', end: null } },
  }, markdown: '## Plan\n\nShip in November. The launch codeword is SAFFRON.' },
  [NT_BILLING]: { title: 'Billing revamp', parent: ntRow, at: 120, titleKey: 'Name', props: {
    Status: { id: 'st', type: 'status', status: { name: 'Done' } },
  }, markdown: 'Invoices moved to the new ledger. The billing codeword is JUNIPER.' },
  [NT_NOTES]: { title: 'Meeting notes', parent: ntWorkspace, at: 1200,
    markdown: '## Quarterly review\n\nNumbers were up. The meeting codeword is TAMARIND.' },
  [NT_OLD]: { title: 'Old plan', parent: ntWorkspace, at: 60, trashed: true,
    markdown: 'Abandoned. The trashed codeword is NETTLE.' },
}

// Each page's or block's children, as Notion's blocks.
const ntBlock = (id, type, body, extra = {}) => ({
  object: 'block', id, type, has_children: false, in_trash: false,
  created_time: at(0), last_edited_time: at(0), created_by: { object: 'user', id: NT_ALICE.id }, [type]: body, ...extra,
})
const ntChildren = {
  [NT_HANDBOOK]: [
    ntBlock(ntId(20), 'paragraph', { rich_text: ntText('How the team works.') }),
    ntBlock(NT_TOGGLE, 'toggle', { rich_text: ntText('New starters') }, { has_children: true }),
    ntBlock(NT_PROJECTS_DB, 'child_database', { title: 'Projects' }),
  ],
  [NT_TOGGLE]: [
    ntBlock(NT_ONBOARDING, 'child_page', { title: 'Onboarding' }, { last_edited_time: at(300) }),
  ],
}

function ntPage(id) {
  const p = ntPages[id]
  const properties = {
    [p.titleKey ?? 'title']: { id: 'title', type: 'title', title: ntText(p.title) },
    ...(p.props ?? {}),
  }
  return {
    object: 'page', id, created_time: at(0), last_edited_time: at(p.at),
    created_by: { object: 'user', id: NT_ALICE.id }, last_edited_by: { object: 'user', id: NT_ALICE.id },
    in_trash: !!p.trashed, is_archived: false, parent: p.parent, properties,
    url: `https://www.notion.so/${p.title.replace(/ /g, '-')}-${id.replace(/-/g, '')}`, public_url: null,
  }
}

// Notion's paging: page_size capped at --page-size, start_cursor naming the next offset.
function ntPaged(res, all, cursor, size) {
  const offset = Number((cursor ?? '').replace(/^c/, '') || '0')
  const limit = Math.min(PAGE_SIZE, Math.max(1, Number(size ?? 100)))
  const more = offset + limit < all.length
  json(res, 200, {
    object: 'list', results: all.slice(offset, offset + limit), has_more: more,
    next_cursor: more ? `c${offset + limit}` : null, type: 'page_or_data_source', request_status: { type: 'complete' },
  })
}

const ntError = (res, status, code, message) => json(res, status, { object: 'error', status, code, message })

function readBody(req) {
  return new Promise(resolve => {
    let body = ''
    req.on('data', chunk => { body += chunk })
    req.on('end', () => {
      try { resolve(body ? JSON.parse(body) : {}) } catch { resolve({}) }
    })
  })
}

async function notion(req, res, url, rest) {
  if (!rest.startsWith('v1/')) return ntError(res, 404, 'object_not_found', 'Unknown path.')
  const path = rest.slice('v1/'.length)
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) return ntError(res, 401, 'unauthorized', 'API token is invalid.')
  if (!req.headers['notion-version']) return ntError(res, 400, 'missing_version', 'Notion-Version header failed validation.')
  const body = req.method === 'POST' ? await readBody(req) : {}
  const q = name => url.searchParams.get(name)
  let m

  if (path === 'users/me') return json(res, 200, NT_BOT)
  if ((m = path.match(/^users\/([^/]+)$/))) {
    const user = [NT_ALICE, NT_BOT].find(u => u.id === m[1])
    return user ? json(res, 200, user) : ntError(res, 404, 'object_not_found', 'Could not find user.')
  }
  if (path === 'search' && req.method === 'POST') {
    const dir = body.sort?.direction === 'ascending' ? 1 : -1
    const pages = Object.keys(ntPages).map(ntPage)
      .sort((a, b) => dir * (Date.parse(a.last_edited_time) - Date.parse(b.last_edited_time)))
    return ntPaged(res, pages, body.start_cursor, body.page_size)
  }
  if ((m = path.match(/^pages\/([^/]+)\/markdown$/))) {
    const p = ntPages[m[1]]
    if (!p) return ntError(res, 404, 'object_not_found', 'Could not find page.')
    return json(res, 200, { object: 'page_markdown', id: m[1], markdown: p.markdown, truncated: false, unknown_block_ids: [] })
  }
  if ((m = path.match(/^pages\/([^/]+)$/))) {
    return ntPages[m[1]] ? json(res, 200, ntPage(m[1])) : ntError(res, 404, 'object_not_found', 'Could not find page.')
  }
  if ((m = path.match(/^blocks\/([^/]+)\/children$/))) {
    if (!ntPages[m[1]] && !ntChildren[m[1]]) return ntError(res, 404, 'object_not_found', 'Could not find block.')
    return ntPaged(res, ntChildren[m[1]] ?? [], q('start_cursor'), q('page_size'))
  }
  if ((m = path.match(/^blocks\/([^/]+)$/))) {
    if (m[1] === NT_TOGGLE) return json(res, 200, { ...ntChildren[NT_HANDBOOK][1], parent: { type: 'page_id', page_id: NT_HANDBOOK } })
    return ntError(res, 404, 'object_not_found', 'Could not find block.')
  }
  if (path === `databases/${NT_PROJECTS_DB}`) {
    return json(res, 200, {
      object: 'database', id: NT_PROJECTS_DB, title: ntText('Projects'), in_trash: false,
      parent: { type: 'page_id', page_id: NT_HANDBOOK }, data_sources: [{ id: NT_PROJECTS_DS, name: 'Projects' }],
      url: `https://www.notion.so/${NT_PROJECTS_DB.replace(/-/g, '')}`,
    })
  }
  if (path === `data_sources/${NT_PROJECTS_DS}`) {
    return json(res, 200, {
      object: 'data_source', id: NT_PROJECTS_DS, title: ntText('Projects'), in_trash: false,
      parent: { type: 'database_id', database_id: NT_PROJECTS_DB },
      url: `https://www.notion.so/${NT_PROJECTS_DS.replace(/-/g, '')}`,
    })
  }
  if (path === `data_sources/${NT_PROJECTS_DS}/query` && req.method === 'POST') {
    const after = body.filter?.last_edited_time?.on_or_after
    const rows = [NT_APOLLO, NT_BILLING].map(ntPage)
      .filter(r => !after || Date.parse(r.last_edited_time) >= Date.parse(after))
    return ntPaged(res, rows, body.start_cursor, body.page_size)
  }
  return ntError(res, 404, 'object_not_found', 'Could not find that object.')
}

// -- Airtable ----------------------------------------------------------------------------------

// One base of two tables, with a view that leaves out what is done, and a word per record that a
// search can find. Records name when they were created and changed (minutes after T0).
const AT_BASE = 'appFakeCorpOps001'
const AT_ALICE = { id: 'usrAlice000000001', email: 'alice@fake.example', name: 'Alice Nguyen' }
const AT_DENIED = 'INVALID_PERMISSIONS_OR_MODEL_NOT_FOUND'
const atTables = [
  {
    id: 'tblProjects000001', name: 'Projects', primaryFieldId: 'fldName0000000001',
    fields: [
      { id: 'fldName0000000001', name: 'Name', type: 'singleLineText' },
      { id: 'fldStatus00000001', name: 'Status', type: 'singleSelect' },
      { id: 'fldOwner000000001', name: 'Owner', type: 'singleCollaborator' },
      { id: 'fldNotes000000001', name: 'Notes', type: 'multilineText' },
      { id: 'fldFiles000000001', name: 'Files', type: 'multipleAttachments' },
      { id: 'fldVendor00000001', name: 'Vendor', type: 'multipleRecordLinks' },
    ],
    views: [
      { id: 'viwGrid0000000001', name: 'Grid view', type: 'grid' },
      { id: 'viwActive00000001', name: 'Active', type: 'grid' },
    ],
  },
  {
    id: 'tblVendors0000001', name: 'Vendors', primaryFieldId: 'fldVendorName0001',
    fields: [
      { id: 'fldVendorName0001', name: 'Name', type: 'singleLineText' },
      { id: 'fldVendorNote0001', name: 'Notes', type: 'multilineText' },
    ],
    views: [{ id: 'viwVendorGrid0001', name: 'Grid view', type: 'grid' }],
  },
]
const atRecords = {
  tblProjects000001: [
    { id: 'recApollo00000001', created: 0, changed: 900, fields: {
      Name: 'Apollo launch', Status: 'In progress', Owner: AT_ALICE,
      Notes: 'Ship in November.\nThe launch codeword is ZINNIA.',
      Files: [{ id: 'attPlan0000000001', filename: 'launch-plan.pdf', size: 1200, type: 'application/pdf' }],
      Vendor: ['recAcmePaper00001'],
    } },
    { id: 'recBilling0000001', created: 10, changed: 120, fields: {
      Name: 'Billing revamp', Status: 'Done', Notes: 'Invoices moved to the new ledger. The billing codeword is CALDERA.',
    } },
    { id: 'recHiring00000001', created: 20, changed: 20, fields: {
      Name: 'Hiring plan', Status: 'In progress', Owner: AT_ALICE, Notes: 'Two engineers in Q1. The hiring codeword is MERIDIAN.',
    } },
  ],
  tblVendors0000001: [
    { id: 'recAcmePaper00001', created: 5, changed: 5, fields: {
      Name: 'Acme Paper', Notes: 'Paper and toner. The vendor codeword is BRAMBLE.',
    } },
  ],
}
// An attachment's link, signed afresh on every answer as Airtable's are.
const atFile = f => ({ ...f, url: `https://dl.fake.example/${f.id}/${f.filename}?sig=${Date.now()}` })
const atName = id => Object.values(atRecords).flat().find(r => r.id === id)?.fields.Name ?? id

// A record's fields, in Airtable's JSON or - with cellFormat=string - as it writes them for a person.
function atFields(record, asString) {
  const out = {}
  for (const [name, value] of Object.entries(record.fields)) {
    if (name === 'Files') out[name] = asString ? value.map(f => `${f.filename} (${atFile(f).url})`).join(',') : value.map(atFile)
    else if (name === 'Vendor') out[name] = asString ? value.map(atName).join(',') : value
    else if (name === 'Owner') out[name] = asString ? value.name : value
    else out[name] = value
  }
  return out
}

const atError = (res, status, type, message) => json(res, status, { error: { type, message } })

function airtable(req, res, url, rest) {
  if (!rest.startsWith('v0/')) return atError(res, 404, 'NOT_FOUND', 'Could not find what you are looking for')
  const path = rest.slice('v0/'.length)
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) {
    return atError(res, 401, 'AUTHENTICATION_REQUIRED', 'Authentication required')
  }
  const q = name => url.searchParams.get(name)
  let m

  if (path === 'meta/whoami') return json(res, 200, { id: AT_ALICE.id, email: AT_ALICE.email })
  if (path === 'meta/bases') return json(res, 200, { bases: [{ id: AT_BASE, name: 'Fake Corp Ops', permissionLevel: 'create' }] })
  if ((m = path.match(/^meta\/bases\/([^/]+)\/tables$/))) {
    if (m[1] !== AT_BASE) return atError(res, 403, AT_DENIED, 'Invalid permissions, or the requested model was not found.')
    return json(res, 200, { tables: atTables })
  }
  if ((m = path.match(/^([^/]+)\/([^/]+)$/))) {
    if (m[1] !== AT_BASE) return atError(res, 403, AT_DENIED, 'Invalid permissions, or the requested model was not found.')
    const tableRef = decodeURIComponent(m[2])
    const table = atTables.find(t => t.id === tableRef || t.name === tableRef)
    if (!table) return atError(res, 404, 'TABLE_NOT_FOUND', 'Could not find table')
    let records = atRecords[table.id]
    const view = q('view')
    if (view && !table.views.some(v => v.id === view || v.name === view)) {
      return atError(res, 422, 'VIEW_NAME_NOT_FOUND', 'Could not find view')
    }
    if (view === 'viwActive00000001' || view === 'Active') records = records.filter(r => r.fields.Status !== 'Done')
    // The two formulas the connector writes: one record by its ID, and created or changed since.
    const formula = q('filterByFormula')
    if (formula) {
      const one = formula.match(/^RECORD_ID\(\)='([^']+)'$/)
      const since = formula.match(/DATETIME_PARSE\('([^']+)'\)/)
      if (one) records = records.filter(r => r.id === one[1])
      else if (since) records = records.filter(r => T0 + Math.max(r.created, r.changed) * 60_000 > Date.parse(since[1]))
    }
    const offset = Number(q('offset') ?? '0')
    const limit = Math.min(PAGE_SIZE, Math.max(1, Number(q('pageSize') ?? '100')), Number(q('maxRecords') ?? Infinity))
    const page = records.slice(offset, offset + limit).map(r => ({
      id: r.id, createdTime: at(r.created), fields: atFields(r, q('cellFormat') === 'string'),
    }))
    const more = offset + limit < records.length && !q('maxRecords')
    return json(res, 200, more ? { records: page, offset: String(offset + limit) } : { records: page })
  }
  return atError(res, 404, 'NOT_FOUND', 'Could not find what you are looking for')
}

// -- Zendesk -----------------------------------------------------------------------------------

// One Zendesk account: three tickets - one with an internal note, one deleted - and a Help Center with
// a published article and a draft, each holding a word only it has. Point a Zendesk connection's
// Zendesk address (More options) at http://127.0.0.1:<port>/zendesk, with any subdomain, agent email
// and token. To sign in through this server rather than Zendesk's, start the app with
//   -Dproxyma.connectors.zendesk.oauth-url=http://127.0.0.1:<port>/zendesk
// and give the connection any client identifier: the authorize page agrees at once and the token
// endpoint answers any code or refresh token with a token.
const ZD_ME = { id: 702, name: 'Jake Peralta', email: 'jake@example.com', role: 'agent' }
const zdUsers = [{ id: 701, name: 'Rosa Diaz', email: 'rosa@example.com', role: 'end-user' }, ZD_ME]
const zdGroups = [{ id: 30, name: 'Hardware' }]
const zdOrganizations = [{ id: 40, name: 'Nine-Nine Logistics' }]
const zdTickets = [
  {
    id: 1, subject: 'Label printer jams', status: 'open', priority: 'high', type: 'incident', requester_id: 701,
    assignee_id: 702, group_id: 30, organization_id: 40, tags: ['hardware'], created_at: at(0), updated_at: at(130),
  },
  {
    id: 2, subject: 'Invoice address change', status: 'solved', priority: 'normal', type: 'question', requester_id: 701,
    assignee_id: null, group_id: null, organization_id: 40, tags: ['billing'], created_at: at(20), updated_at: at(140),
  },
  {
    id: 3, subject: 'Duplicate request', status: 'deleted', priority: null, type: null, requester_id: 701,
    assignee_id: null, group_id: null, organization_id: null, tags: [], created_at: at(30), updated_at: at(150),
  },
]
const zdComments = {
  1: [
    { id: 100, author_id: 701, public: true, body: 'The label printer jams on every page. Our warehouse code is PELICAN.', created_at: at(0) },
    { id: 101, author_id: 702, public: false, body: 'Internal: the roller batch is faulty, codeword CORMORANT.', created_at: at(60) },
    { id: 102, author_id: 702, public: true, body: 'A technician will replace the roller on Monday.', created_at: at(130) },
  ],
  2: [{ id: 200, author_id: 701, public: true, body: 'Please send invoices to the new address. Reference ALBATROSS.', created_at: at(20) }],
  3: [{ id: 300, author_id: 701, public: true, body: 'Same as the printer ticket. Codeword OSPREY.', created_at: at(30) }],
}
const zdSections = [{ id: 55, name: 'Accounts', locale: 'en-us' }]
const zdArticles = [
  {
    id: 11, title: 'Reset your password', locale: 'en-us', section_id: 55, author_id: 702, draft: false, label_names: ['account'],
    body: '<h2>Steps</h2><ol><li>Open <strong>Settings</strong>.</li><li>Choose Reset. The recovery word is KINGFISHER.</li></ol>',
    created_at: at(5), updated_at: at(200), edited_at: at(200),
  },
  {
    id: 12, title: 'Unfinished draft', locale: 'en-us', section_id: 55, author_id: 702, draft: true, label_names: [],
    body: '<p>Not published yet: SANDPIPER.</p>', created_at: at(6), updated_at: at(210), edited_at: at(210),
  },
]
const zdSeconds = iso => Math.floor(Date.parse(iso) / 1000)
const zdArticleView = a => ({ ...a, html_url: `https://acme.zendesk.example/hc/${a.locale}/articles/${a.id}` })
// The users a page names, sideloaded as Zendesk's include=users does.
const zdUsersOf = ids => zdUsers.filter(u => ids.includes(u.id))

// A cursor-paginated list as Zendesk answers one: page[size], page[after], meta.has_more, links.next.
function zdCursorPage(url, items, key, extra) {
  const size = Math.min(PAGE_SIZE, Math.max(1, Number(url.searchParams.get('page[size]') ?? '100')))
  const from = Number(url.searchParams.get('page[after]') ?? '0') || 0
  const page = items.slice(from, from + size)
  const more = from + size < items.length
  const next = new URL(url)
  next.searchParams.set('page[after]', String(from + size))
  return {
    [key]: page, ...(extra ? extra(page) : {}),
    meta: { has_more: more, after_cursor: more ? String(from + size) : null },
    links: { next: more ? next.toString() : null },
  }
}

// The cursor-based ticket export. A cursor names the last ticket handed out - its updated_at in
// seconds and its id - and the next page starts after it; start_time starts at that second.
function zdTicketExport(res, url) {
  const q = url.searchParams
  let after
  if (q.get('cursor')) {
    const m = q.get('cursor').match(/^zc-(\d+)-(\d+)$/)
    if (!m) return json(res, 422, { error: 'InvalidCursor', description: 'The cursor is not valid.' })
    after = [Number(m[1]), Number(m[2])]
  } else if (q.get('start_time') !== null) {
    after = [Number(q.get('start_time')) - 1, Number.MAX_SAFE_INTEGER]
  } else {
    return json(res, 400, { error: 'InvalidPaginationParameter', description: 'start_time or cursor is required.' })
  }
  const size = Math.min(PAGE_SIZE, Math.max(1, Number(q.get('per_page') ?? '1000')))
  const key = t => [zdSeconds(t.updated_at), t.id]
  const later = zdTickets
    .filter(t => { const [s, id] = key(t); return s > after[0] || (s === after[0] && id > after[1]) })
    .sort((a, b) => key(a)[0] - key(b)[0] || a.id - b.id)
  const page = later.slice(0, size)
  const last = page.length ? key(page[page.length - 1]) : after
  json(res, 200, {
    tickets: page,
    ...(q.get('include') === 'users' ? { users: zdUsersOf(page.map(t => t.requester_id)) } : {}),
    after_cursor: `zc-${last[0]}-${Math.min(last[1], 999999999)}`,
    before_cursor: null,
    end_of_stream: later.length <= size,
  })
}

function zdHelpCenter(res, url, path) {
  if (path === 'locales') return json(res, 200, { locales: ['en-us'], default_locale: 'en-us' })
  if (path === 'incremental/articles') {
    const start = Number(url.searchParams.get('start_time') ?? 'NaN')
    if (!Number.isFinite(start)) return json(res, 400, { error: 'start_time is required' })
    const changed = zdArticles.filter(a => zdSeconds(a.updated_at) >= start)
    const end = changed.reduce((m, a) => Math.max(m, zdSeconds(a.updated_at)), start)
    const next = new URL(url)
    next.searchParams.set('start_time', String(end))
    return json(res, 200, {
      articles: changed.map(zdArticleView), users: zdUsersOf(changed.map(a => a.author_id)),
      count: changed.length, end_time: end, next_page: next.toString(),
    })
  }
  let m = path.match(/^sections\/(\d+)$/)
  if (m) {
    const section = zdSections.find(s => s.id === Number(m[1]))
    return section ? json(res, 200, { section }) : json(res, 404, { error: 'RecordNotFound' })
  }
  m = path.match(/^([a-z-]+)\/(?:sections\/(\d+)\/)?articles(?:\/(\d+))?$/)
  if (!m || m[1] !== 'en-us') return json(res, 404, { error: 'RecordNotFound' })
  if (m[3]) {
    const article = zdArticles.find(a => a.id === Number(m[3]))
    if (!article) return json(res, 404, { error: 'RecordNotFound' })
    return json(res, 200, {
      article: zdArticleView(article), users: zdUsersOf([article.author_id]),
      sections: zdSections.filter(s => s.id === article.section_id),
    })
  }
  const listed = zdArticles.filter(a => !m[2] || a.section_id === Number(m[2])).map(zdArticleView)
  return json(res, 200, zdCursorPage(url, listed, 'articles', page => ({ users: zdUsersOf(page.map(a => a.author_id)) })))
}

function zendesk(req, res, url, rest) {
  if (rest === 'oauth/authorizations/new') {
    let back
    try {
      back = new URL(url.searchParams.get('redirect_uri') ?? '')
    } catch {
      return json(res, 400, { error: 'invalid_request' })
    }
    back.searchParams.set('code', 'fake-zendesk-code')
    back.searchParams.set('state', url.searchParams.get('state') ?? '')
    res.writeHead(302, { Location: back.toString() })
    return res.end()
  }
  if (rest === 'oauth/tokens' && req.method === 'POST') {
    let body = ''
    req.on('data', chunk => { body += chunk })
    req.on('end', () => {
      const grant = new URLSearchParams(body).get('grant_type')
      if (!['authorization_code', 'refresh_token'].includes(grant)) return json(res, 400, { error: 'unsupported_grant_type' })
      json(res, 200, {
        access_token: 'fake-zendesk-access-token', refresh_token: 'fake-zendesk-refresh-token',
        token_type: 'bearer', scope: 'read', expires_in: 3600,
      })
    })
    return
  }
  if (req.method !== 'GET') return json(res, 405, { error: 'Read only' })
  if (!/^(Basic|Bearer) \S+/.test(req.headers.authorization ?? '')) {
    return json(res, 401, { error: "Couldn't authenticate you" })
  }
  if (!rest.startsWith('api/v2/')) return json(res, 404, { error: 'InvalidEndpoint' })
  const path = rest.slice('api/v2/'.length).replace(/\.json$/, '')
  if (path === 'users/me') return json(res, 200, { user: ZD_ME })
  if (path === 'incremental/tickets/cursor') return zdTicketExport(res, url)
  if (path.startsWith('help_center/')) return zdHelpCenter(res, url, path.slice('help_center/'.length))
  if (path === 'tickets') return json(res, 200, zdCursorPage(url, zdTickets.filter(t => t.status !== 'deleted'), 'tickets'))
  const m = path.match(/^tickets\/(\d+)(\/comments)?$/)
  if (m) {
    const ticket = zdTickets.find(t => t.id === Number(m[1]) && t.status !== 'deleted')
    if (!ticket) return json(res, 404, { error: 'RecordNotFound', description: 'Not found' })
    if (m[2]) {
      const comments = zdComments[ticket.id] ?? []
      return json(res, 200, zdCursorPage(url, comments, 'comments', page => ({ users: zdUsersOf(page.map(c => c.author_id)) })))
    }
    return json(res, 200, {
      ticket, users: zdUsersOf([ticket.requester_id, ticket.assignee_id]),
      groups: zdGroups.filter(g => g.id === ticket.group_id),
      organizations: zdOrganizations.filter(o => o.id === ticket.organization_id),
    })
  }
  return json(res, 404, { error: 'InvalidEndpoint' })
}

// -- Freshdesk ---------------------------------------------------------------------------------

// One Freshdesk account: tickets - one with a private note, one marked spam, one deleted - and a
// knowledge base with a folder, a subfolder and a draft, each item holding a word only it has. Point a
// Freshdesk connection's Freshdesk address (More options) at http://127.0.0.1:<port>/freshdesk, with
// any domain and API key.
const FD_ME = { id: 801, contact: { name: 'Linh Vo', email: 'linh@example.com' } }
const fdAgents = [FD_ME]
const fdContacts = [{ id: 901, name: 'Mai Tran', email: 'mai@example.com' }]
const fdTickets = [
  {
    id: 1, subject: 'Charger stopped working', status: 2, priority: 3, type: 'Problem', requester_id: 901, responder_id: 801,
    tags: ['hardware'], description_text: 'My charger stopped working after a week. Order code MARMOT.',
    created_at: at(0), updated_at: at(120), deleted: false, spam: false,
  },
  {
    id: 2, subject: 'Late delivery', status: 4, priority: 1, type: 'Question', requester_id: 901, responder_id: null,
    tags: [], description_text: 'The parcel arrived two days late. Tracking word OCELOT.',
    created_at: at(10), updated_at: at(130), deleted: false, spam: false,
  },
  {
    id: 3, subject: 'Win a prize', status: 2, priority: 1, type: null, requester_id: 901, responder_id: null,
    tags: [], description_text: 'Click here to win.', created_at: at(15), updated_at: at(135), deleted: false, spam: true,
  },
  {
    id: 4, subject: 'Duplicate of the charger ticket', status: 5, priority: 1, type: null, requester_id: 901, responder_id: null,
    tags: [], description_text: 'Opened twice by mistake.', created_at: at(16), updated_at: at(136), deleted: true, spam: false,
  },
]
const fdConversations = {
  1: [
    { id: 100, user_id: 801, private: false, incoming: false, source: 0, body_text: 'Please send a photo of the label.', created_at: at(30) },
    { id: 101, user_id: 801, private: true, incoming: false, source: 2, body_text: 'Batch 42 is faulty - codeword TAPIR.', created_at: at(60) },
    { id: 102, user_id: 901, private: false, incoming: true, source: 0, body_text: 'Here is the photo.', created_at: at(90) },
  ],
}
const fdCategories = [{ id: 10, name: 'Getting started' }]
const fdFolders = [
  { id: 20, name: 'Accounts', category_id: 10, parent_folder_id: null },
  { id: 21, name: 'Security', category_id: 10, parent_folder_id: 20 },
]
const fdArticles = [
  {
    id: 31, title: 'Reset a password', status: 2, folder_id: 20, category_id: 10, agent_id: 801, tags: ['account'],
    description_text: 'Open Settings, then choose Reset. The recovery word is ARMADILLO.', created_at: at(5), updated_at: at(200),
  },
  {
    id: 32, title: 'Two-factor sign-in', status: 2, folder_id: 21, category_id: 10, agent_id: 801, tags: [],
    description_text: 'Use an authenticator app for every sign-in.', created_at: at(6), updated_at: at(201),
  },
  {
    id: 33, title: 'Unfinished', status: 1, folder_id: 20, category_id: 10, agent_id: 801, tags: [],
    description_text: 'Not published yet: WOMBAT.', created_at: at(7), updated_at: at(202),
  },
]
const fdArticleView = a => ({ ...a, description: `<p>${a.description_text}</p>` })
const fdNotFound = res => json(res, 404, { code: 'resource_not_found', message: 'The requested resource was not found.' })

// Tickets as Freshdesk lists them: none deleted or spam unless that filter is asked for,
// updated_since, ordered by updated_at, Link-paginated by page and per_page.
function fdTicketList(res, url) {
  const q = url.searchParams
  const filter = q.get('filter')
  const since = q.get('updated_since')
  const listed = fdTickets
    .filter(t => (filter === 'deleted' ? t.deleted : filter === 'spam' ? t.spam : !t.deleted && !t.spam))
    .filter(t => !since || Date.parse(t.updated_at) >= Date.parse(since))
    .sort((a, b) => (q.get('order_type') === 'asc' ? 1 : -1) * (Date.parse(a.updated_at) - Date.parse(b.updated_at)))
    .map(t => (q.get('include') === 'requester' ? { ...t, requester: fdContacts.find(c => c.id === t.requester_id) } : t))
  return paged(res, url, listed)
}

function freshdesk(req, res, url, rest) {
  if (!/^Basic \S+/.test(req.headers.authorization ?? '')) {
    return json(res, 401, { code: 'invalid_credentials', message: 'You have to be logged in to perform this action.' })
  }
  if (!rest.startsWith('api/v2/')) return fdNotFound(res)
  const path = rest.slice('api/v2/'.length)
  if (path === 'agents/me') return json(res, 200, FD_ME)
  let m = path.match(/^(agents|contacts)\/(\d+)$/)
  if (m) {
    const person = (m[1] === 'agents' ? fdAgents : fdContacts).find(p => p.id === Number(m[2]))
    return person ? json(res, 200, person) : fdNotFound(res)
  }
  if (path === 'tickets') return fdTicketList(res, url)
  m = path.match(/^tickets\/(\d+)(\/conversations)?$/)
  if (m) {
    const ticket = fdTickets.find(t => t.id === Number(m[1]))
    if (!ticket) return fdNotFound(res)
    if (m[2]) return paged(res, url, fdConversations[ticket.id] ?? [])
    const view = { ...ticket }
    if (url.searchParams.get('include') === 'requester') view.requester = fdContacts.find(c => c.id === ticket.requester_id)
    return json(res, 200, view)
  }
  if (path === 'solutions/categories') return json(res, 200, fdCategories)
  m = path.match(/^solutions\/categories\/(\d+)(\/folders)?$/)
  if (m) {
    const category = fdCategories.find(c => c.id === Number(m[1]))
    if (!category) return fdNotFound(res)
    return json(res, 200, m[2] ? fdFolders.filter(f => f.category_id === category.id && f.parent_folder_id === null) : category)
  }
  m = path.match(/^solutions\/folders\/(\d+)(\/subfolders|\/articles)?$/)
  if (m) {
    const folder = fdFolders.find(f => f.id === Number(m[1]))
    if (!folder) return fdNotFound(res)
    if (m[2] === '/subfolders') return json(res, 200, fdFolders.filter(f => f.parent_folder_id === folder.id))
    if (m[2] === '/articles') return paged(res, url, fdArticles.filter(a => a.folder_id === folder.id).map(fdArticleView))
    return json(res, 200, folder)
  }
  m = path.match(/^solutions\/articles\/(\d+)$/)
  if (m) {
    const article = fdArticles.find(a => a.id === Number(m[1]))
    return article ? json(res, 200, fdArticleView(article)) : fdNotFound(res)
  }
  return fdNotFound(res)
}

// -- HubSpot -----------------------------------------------------------------------------------

// One account: two companies, two contacts, a deal and a ticket, each with a word only it holds,
// and one archived company - listed only with archived=true, so a full sync never indexes it. Any
// dated version answers (`/crm/objects/2026-03/...`), and v3 under the old paths.
const HS_ACCOUNT = { portalId: 4242424, uiDomain: 'app.hubspot.example', timeZone: 'Asia/Ho_Chi_Minh', accountType: 'STANDARD' }
const hsOwners = [{ id: '901', firstName: 'Linh', lastName: 'Pham', email: 'linh@fake-corp.example' }]
const hsPipelines = {
  deals: [{ id: 'default', label: 'Sales pipeline', stages: [{ id: 'contractsent', label: 'Contract sent' }, { id: 'closedwon', label: 'Closed won' }] }],
  tickets: [{ id: '0', label: 'Support pipeline', stages: [{ id: '1', label: 'New' }, { id: '4', label: 'Closed' }] }],
}
const hsRecords = {
  companies: [
    {
      id: '101', modified: at(130), properties: {
        name: 'Kestrel Logistics', domain: 'kestrel.example', industry: 'TRANSPORTATION_TRUCKING_RAILROAD', city: 'Hai Phong',
        country: 'Vietnam', hubspot_owner_id: '901', createdate: at(0),
        description: 'Cold-chain trucking in the north. Their warehouse codeword is PANGOLIN.',
      },
      associations: { contacts: ['201'], deals: ['301'], notes: ['401'], calls: ['402'] },
    },
    {
      id: '102', modified: at(140), properties: {
        name: 'Brightwater Clinics', domain: 'brightwater.example', industry: 'HOSPITAL_HEALTH_CARE', city: 'Da Nang',
        country: 'Vietnam', hubspot_owner_id: '901', createdate: at(10), description: 'A chain of six clinics.',
      },
      associations: { contacts: ['202'], tickets: ['501'] },
    },
    {
      id: '103', modified: at(150), archivedAt: at(150), properties: {
        name: 'Defunct Holdings', createdate: at(20), description: 'Archived. The codeword AXOLOTL must never be found.',
      },
      associations: {},
    },
  ],
  contacts: [
    {
      id: '201', modified: at(160), properties: {
        firstname: 'Mai', lastname: 'Nguyen', email: 'mai@kestrel.example', jobtitle: 'CFO', company: 'Kestrel Logistics',
        hubspot_owner_id: '901', createdate: at(30),
      },
      associations: { companies: ['101'], deals: ['301'] },
    },
    {
      id: '202', modified: at(170), properties: {
        firstname: 'Tom', lastname: 'Reyes', email: 'tom@brightwater.example', jobtitle: 'IT lead', createdate: at(40),
      },
      associations: { companies: ['102'], tickets: ['501'] },
    },
  ],
  deals: [
    {
      id: '301', modified: at(180), properties: {
        dealname: 'Kestrel fleet renewal', pipeline: 'default', dealstage: 'contractsent', amount: '48000',
        closedate: at(2000), hubspot_owner_id: '901', createdate: at(50),
        description: 'Three-year renewal of the fleet tracking plan. Pricing codeword OKAPI.',
      },
      associations: { companies: ['101'], contacts: ['201'], notes: ['401'] },
    },
  ],
  tickets: [
    {
      id: '501', modified: at(190), properties: {
        subject: 'Invoice export fails', hs_pipeline: '0', hs_pipeline_stage: '1', hs_ticket_priority: 'HIGH',
        createdate: at(60), content: 'The monthly invoice export stops at row 500. Escalation codeword CASSOWARY.',
      },
      associations: { companies: ['102'], contacts: ['202'] },
    },
  ],
  notes: [
    {
      id: '401', modified: at(135), properties: {
        hs_note_body: '<p>Visited the Hai Phong depot. The site visit codeword is NARWHAL.</p>', hs_timestamp: at(135),
        hubspot_owner_id: '901',
      },
    },
  ],
  calls: [
    {
      id: '402', modified: at(136), properties: {
        hs_call_title: 'Quarterly review', hs_call_body: 'Walked through the on-time delivery numbers.',
        hs_call_direction: 'OUTBOUND', hs_timestamp: at(136), hubspot_owner_id: '901',
      },
    },
  ],
}
const HS_MODIFIED = type => (type === 'contacts' ? 'lastmodifieddate' : 'hs_lastmodifieddate')

// A record as HubSpot answers it: the properties asked for (with its modified date under its own name).
function hsView(type, r, wanted) {
  const all = { ...r.properties, [HS_MODIFIED(type)]: r.modified, hs_object_id: r.id }
  const properties = {}
  for (const p of wanted ?? Object.keys(all)) if (p) properties[p] = all[p] ?? null
  const view = { id: r.id, properties, createdAt: r.properties.createdate ?? r.modified, updatedAt: r.modified, archived: !!r.archivedAt }
  if (r.archivedAt) view.archivedAt = r.archivedAt
  return view
}

// HubSpot's cursor pages: `after` is an offset, `paging.next.after` names the next.
function hsPage(items, after, limit, cap) {
  const start = after ? Number(after) : 0
  const size = Math.min(PAGE_SIZE, Math.max(1, Number(limit ?? 10)), cap)
  const end = Math.min(items.length, start + size)
  const body = { results: items.slice(start, end) }
  if (end < items.length) body.paging = { next: { after: String(end) } }
  return body
}

function hsBody(req, done) {
  const chunks = []
  req.on('data', c => chunks.push(c))
  req.on('end', () => {
    try {
      done(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'))
    } catch {
      done(null)
    }
  })
}

function hsError(res, status, category, message) {
  json(res, status, { status: 'error', message, category, correlationId: '00000000-0000-0000-0000-000000000000' })
}

function hubspot(req, res, url, rest) {
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) {
    return hsError(res, 401, 'INVALID_AUTHENTICATION', 'Authentication credentials not found.')
  }
  let m
  if ((m = rest.match(/^account-info\/[^/]+\/details$/))) return json(res, 200, HS_ACCOUNT)
  if ((m = rest.match(/^crm\/(?:owners\/[^/]+|v3\/owners)$/))) return json(res, 200, { results: hsOwners })
  if ((m = rest.match(/^crm\/(?:pipelines\/[^/]+|v3\/pipelines)\/(deals|tickets)$/))) {
    return json(res, 200, { results: hsPipelines[m[1]] })
  }
  m = rest.match(/^crm\/(?:objects\/[^/]+|v3\/objects)\/([a-z]+)(?:\/(.+))?$/)
  if (!m || !hsRecords[m[1]]) return hsError(res, 404, 'NOT_FOUND', 'Unknown path.')
  const [, type, tail] = m
  const records = hsRecords[type]
  const props = url.searchParams.get('properties')?.split(',')

  if (!tail && req.method === 'GET') {
    const archived = url.searchParams.get('archived') === 'true'
    const items = records.filter(r => !!r.archivedAt === archived).map(r => hsView(type, r, props))
    return json(res, 200, hsPage(items, url.searchParams.get('after'), url.searchParams.get('limit'), 100))
  }
  if (tail === 'search' && req.method === 'POST') {
    return hsBody(req, body => {
      if (!body) return hsError(res, 400, 'VALIDATION_ERROR', 'Body is not JSON.')
      const filter = body.filterGroups?.[0]?.filters?.[0]
      const from = filter?.operator === 'GTE' ? Number(filter.value) : 0
      const items = records.filter(r => !r.archivedAt && Date.parse(r.modified) >= from)
        .sort((a, b) => Date.parse(a.modified) - Date.parse(b.modified))
        .map(r => hsView(type, r, body.properties))
      json(res, 200, { total: items.length, ...hsPage(items, body.after, body.limit, 200) })
    })
  }
  if (tail === 'batch/read' && req.method === 'POST') {
    return hsBody(req, body => {
      if (!body) return hsError(res, 400, 'VALIDATION_ERROR', 'Body is not JSON.')
      const ids = (body.inputs ?? []).map(i => String(i.id))
      const results = records.filter(r => !r.archivedAt && ids.includes(r.id)).map(r => hsView(type, r, body.properties))
      json(res, 200, { status: 'COMPLETE', results })
    })
  }
  if (tail && /^\d+$/.test(tail) && req.method === 'GET') {
    const r = records.find(x => x.id === tail && !x.archivedAt)
    if (!r) return hsError(res, 404, 'OBJECT_NOT_FOUND', 'resource not found')
    const view = hsView(type, r, props)
    const wanted = url.searchParams.get('associations')?.split(',') ?? []
    const associations = {}
    for (const to of wanted) {
      const ids = r.associations?.[to] ?? []
      if (ids.length) associations[to] = { results: ids.map(id => ({ id, type: `${type}_to_${to}` })) }
    }
    if (Object.keys(associations).length) view.associations = associations
    return json(res, 200, view)
  }
  hsError(res, 404, 'NOT_FOUND', 'Unknown path.')
}

// -- Salesforce --------------------------------------------------------------------------------

// One org, reached with the JWT bearer flow - any assertion is accepted - or with any bearer: two
// accounts, a contact, an opportunity and a case, each with a word only it holds, and an account
// deleted on the second day, which the getDeleted resource names and no query returns. SOQL is
// answered by a small interpreter that knows the shapes the connector sends: a field list with
// relationship paths, WHERE conditions joined by AND (=, >, IN), ORDER BY and LIMIT.
const SF_PREFIX = '/salesforce'
const SF_USER = { preferred_username: 'integration@fake-corp.example', name: 'Fake Integration' }
const sfUser = name => ({ attributes: { type: 'User' }, Name: name })
const sfStamp = minutes => at(minutes).replace('Z', '+0000')
const sfRecords = {
  Account: [
    {
      Id: '001FAKE00000001AAA', Name: 'Harbor Freightways', Type: 'Customer', Industry: 'Transportation',
      Phone: '+84 225 555 0101', BillingCity: 'Hai Phong', BillingCountry: 'Vietnam', OwnerId: '005FAKE00000001AAA',
      Owner: sfUser('Linh Pham'), CreatedDate: sfStamp(0), LastModifiedDate: sfStamp(200), SystemModstamp: sfStamp(200),
      Description: 'Port logistics customer since 2024. Their account codeword is TARDIGRADE.',
    },
    {
      Id: '001FAKE00000002AAA', Name: 'Lotus Pharmacies', Type: 'Prospect', Industry: 'Healthcare',
      OwnerId: '005FAKE00000001AAA', Owner: sfUser('Linh Pham'), CreatedDate: sfStamp(10),
      LastModifiedDate: sfStamp(210), SystemModstamp: sfStamp(210), Description: 'Forty stores in the south.',
    },
  ],
  Contact: [
    {
      Id: '003FAKE00000001AAA', Name: 'Quang Le', Title: 'Head of Operations', Email: 'quang@harbor.example',
      AccountId: '001FAKE00000001AAA', Account: { Name: 'Harbor Freightways' }, OwnerId: '005FAKE00000001AAA',
      Owner: sfUser('Linh Pham'), CreatedDate: sfStamp(20), LastModifiedDate: sfStamp(220), SystemModstamp: sfStamp(220),
    },
  ],
  Opportunity: [
    {
      Id: '006FAKE00000001AAA', Name: 'Harbor dock scheduling', StageName: 'Negotiation/Review', Amount: 36000,
      CloseDate: '2026-12-15', Probability: 70, AccountId: '001FAKE00000001AAA', Account: { Name: 'Harbor Freightways' },
      OwnerId: '005FAKE00000001AAA', Owner: sfUser('Linh Pham'), CreatedDate: sfStamp(30), LastModifiedDate: sfStamp(230),
      SystemModstamp: sfStamp(230), Description: 'Dock scheduling for two terminals. Negotiation codeword CAPYBARA.',
    },
  ],
  Case: [
    {
      Id: '500FAKE00000001AAA', CaseNumber: '00001042', Subject: 'Tracking map shows no trucks', Status: 'Working',
      Priority: 'High', Origin: 'Email', AccountId: '001FAKE00000001AAA', Account: { Name: 'Harbor Freightways' },
      OwnerId: '005FAKE00000001AAA', Owner: sfUser('Linh Pham'), CreatedDate: sfStamp(40), LastModifiedDate: sfStamp(240),
      SystemModstamp: sfStamp(240), Description: 'The live map is empty since the update. Support codeword QUOKKA.',
    },
  ],
  CaseComment: [
    { ParentId: '500FAKE00000001AAA', CommentBody: 'Cleared the map cache; trucks are back.', CreatedDate: sfStamp(250), CreatedBy: sfUser('Linh Pham') },
  ],
  Note: [
    { ParentId: '001FAKE00000001AAA', Title: 'Site visit', Body: 'Toured the container yard with Quang.', CreatedDate: sfStamp(205), CreatedBy: sfUser('Linh Pham') },
  ],
  Task: [
    {
      WhatId: '006FAKE00000001AAA', Subject: 'Send revised quote', Status: 'Completed', ActivityDate: '2026-09-10',
      Description: 'Sent with the two-terminal discount.', Owner: sfUser('Linh Pham'),
    },
  ],
  Event: [], OpportunityContactRole: [], ContentDocumentLink: [], ContentNote: [],
}
const sfDeleted = { Account: [{ id: '001FAKE00000003AAA', deletedDate: sfStamp(1500) }] }
const sfDescribed = ['Account', 'Contact', 'Opportunity', 'Case']
const sfLocators = new Map()

const sfPath = (r, path) => path.split('.').reduce((v, k) => (v == null ? undefined : v[k]), r)

// One record cut to the fields a SELECT names, relationships kept nested as Salesforce answers them.
function sfProject(object, r, fields) {
  const out = { attributes: { type: object } }
  for (const f of fields) {
    const parts = f.split('.')
    if (parts.length === 1) { out[f] = r[f] ?? null; continue }
    const [rel, field] = parts
    if (r[rel] == null) { out[rel] = null; continue }
    out[rel] = { ...(out[rel] ?? { attributes: { type: rel } }), [field]: r[rel][field] ?? null }
  }
  return out
}

function sfCompare(value, op, literal) {
  if (op === 'IN') return literal.includes(value)
  if (op === '>') return Date.parse(String(value).replace('+0000', 'Z')) > Date.parse(literal)
  return String(value) === literal
}

// The SOQL the connector sends, answered; null for a query it cannot read.
function sfQuery(soql) {
  const m = soql.match(/^SELECT (.+?) FROM (\w+)(?: WHERE (.+?))?(?: ORDER BY (.+?))?(?: LIMIT (\d+))?$/)
  if (!m) return null
  const [, list, object, where, orderBy, limit] = m
  const records = sfRecords[object]
  if (!records) return undefined
  const fields = list.split(',').map(f => f.trim())
  let rows = records
  if (where) {
    for (const cond of where.split(' AND ')) {
      const c = cond.match(/^([\w.]+) (=|>|IN) (.+)$/)
      if (!c) return null
      const literal = c[2] === 'IN' ? [...c[3].matchAll(/'([^']*)'/g)].map(x => x[1]) : c[3].replace(/^'|'$/g, '')
      rows = rows.filter(r => sfCompare(sfPath(r, c[1]), c[2], literal))
    }
  }
  if (orderBy) {
    const key = orderBy.split(',')[0].trim().split(' ')[0]
    const desc = /\bDESC\b/.test(orderBy.split(',')[0])
    rows = [...rows].sort((a, b) => String(sfPath(a, key) ?? '').localeCompare(String(sfPath(b, key) ?? '')) * (desc ? -1 : 1))
  }
  if (limit) rows = rows.slice(0, Number(limit))
  return rows.map(r => sfProject(object, r, fields))
}

// A query's answer, at most --page-size records, the rest behind a nextRecordsUrl.
function sfAnswer(res, version, rows, start, locator) {
  const end = Math.min(rows.length, start + PAGE_SIZE)
  const body = { totalSize: rows.length, done: end >= rows.length, records: rows.slice(start, end) }
  if (!body.done) body.nextRecordsUrl = `${SF_PREFIX}/services/data/v${version}/query/${locator}-${end}`
  json(res, 200, body)
}

function sfError(res, status, errorCode, message) {
  json(res, status, [{ message, errorCode }])
}

function salesforce(req, res, url, rest) {
  if (rest === 'services/oauth2/token' && req.method === 'POST') {
    const chunks = []
    req.on('data', c => chunks.push(c))
    req.on('end', () => {
      const form = new URLSearchParams(Buffer.concat(chunks).toString('utf8'))
      if (form.get('grant_type') !== 'urn:ietf:params:oauth:grant-type:jwt-bearer' || !form.get('assertion')) {
        return json(res, 400, { error: 'unsupported_grant_type', error_description: 'grant type not supported' })
      }
      json(res, 200, {
        access_token: '00DFAKE!fake-session-token', instance_url: `http://${req.headers.host}${SF_PREFIX}`,
        id: `http://${req.headers.host}${SF_PREFIX}/id/00DFAKE/005FAKE`, token_type: 'Bearer', scope: 'api id',
      })
    })
    return
  }
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) {
    return sfError(res, 401, 'INVALID_SESSION_ID', 'Session expired or invalid')
  }
  if (rest === 'services/oauth2/userinfo') return json(res, 200, SF_USER)
  let m = rest.match(/^services\/data\/v(\d+\.\d)\/(.+)$/)
  if (!m) return sfError(res, 404, 'NOT_FOUND', 'The requested resource does not exist')
  const [, version, tail] = m
  if ((m = tail.match(/^sobjects\/(\w+)\/describe$/))) {
    if (!sfDescribed.includes(m[1])) return sfError(res, 404, 'NOT_FOUND', 'The requested resource does not exist')
    const names = new Set()
    for (const r of sfRecords[m[1]]) for (const k of Object.keys(r)) names.add(k)
    const fields = [...names].filter(k => typeof sfRecords[m[1]][0][k] !== 'object' || k.endsWith('Id')).map(k => ({
      name: k, label: k.replace(/([a-z])([A-Z])/g, '$1 $2'),
      relationshipName: k.endsWith('Id') && k !== 'Id' ? k.slice(0, -2) : null,
    }))
    return json(res, 200, { name: m[1], fields })
  }
  if ((m = tail.match(/^sobjects\/(\w+)\/deleted\/?$/))) {
    const start = Date.parse((url.searchParams.get('start') ?? '').replace('+00:00', 'Z'))
    const end = Date.parse((url.searchParams.get('end') ?? '').replace('+00:00', 'Z'))
    if (Number.isNaN(start) || Number.isNaN(end)) return sfError(res, 400, 'INVALID_REPLICATION_DATE', 'start and end are required')
    const deletedRecords = (sfDeleted[m[1]] ?? []).filter(d => {
      const t = Date.parse(d.deletedDate.replace('+0000', 'Z'))
      return t >= start && t <= end
    })
    return json(res, 200, { deletedRecords, earliestDateAvailable: sfStamp(0), latestDateCovered: new Date(end).toISOString() })
  }
  if (tail === 'query') {
    const rows = sfQuery(url.searchParams.get('q') ?? '')
    if (rows === null) return sfError(res, 400, 'MALFORMED_QUERY', 'unexpected token')
    if (rows === undefined) return sfError(res, 400, 'INVALID_TYPE', 'sObject type is not supported.')
    const locator = `01gFAKE${sfLocators.size + 1}`
    sfLocators.set(locator, rows)
    return sfAnswer(res, version, rows, 0, locator)
  }
  if ((m = tail.match(/^query\/(01gFAKE\d+)-(\d+)$/))) {
    const rows = sfLocators.get(m[1])
    if (!rows) return sfError(res, 400, 'INVALID_QUERY_LOCATOR', 'invalid query locator')
    return sfAnswer(res, version, rows, Number(m[2]), m[1])
  }
  sfError(res, 404, 'NOT_FOUND', 'The requested resource does not exist')
}

// -- Asana -------------------------------------------------------------------------------------

// One workspace, two projects, three tasks - one in both projects - each holding a word only it has
// (CHALCEDONY, LABRADORITE, TOURMALINE). The Events feed of Launch plan reports task 1399 deleted, so
// a changes pass lists it and its fetch, a 404, removes it. A request without a sync token is a 412
// carrying the current one, as Asana's is; a token other than the current one has expired.
const AS_ME = { gid: '1001', name: 'Fake User', email: 'fake-user@example.com' }
const AS_WORKSPACE = { gid: '1101', name: 'Fake Workspace' }
const asProjects = [{ gid: '1201', name: 'Launch plan' }, { gid: '1202', name: 'Hiring' }]
const asPerson = name => ({ gid: `u-${name.toLowerCase()}`, name })
const asTasks = [
  {
    gid: '1301', name: 'Book the launch venue', projects: [['1201', 'Doing']], assignee: asPerson('Bob'), created_by: asPerson('Alice'),
    notes: 'A hall for two hundred people near the station. The codeword for the venue is CHALCEDONY.', due_on: '2026-10-30',
    completed: false, created: 0, modified: 130, tags: ['venue'], custom_fields: [{ name: 'Priority', display_value: 'High' }],
    stories: [
      { type: 'system', resource_subtype: 'assigned', text: 'Alice assigned to Bob', created_by: asPerson('Alice'), at: 5 },
      { type: 'comment', resource_subtype: 'comment_added', text: 'Two halls answered; one is free on the date.', created_by: asPerson('Bob'), at: 60 },
      { type: 'comment', resource_subtype: 'comment_added', text: 'Book the free one.', created_by: asPerson('Alice'), at: 120 },
    ],
    subtasks: [{ gid: '1311', name: 'Ask for the floor plan', completed: true, assignee: asPerson('Bob'), due_on: '2026-10-10' }],
  },
  {
    gid: '1302', name: 'Hire a support lead', projects: [['1202', 'Interviewing']], assignee: asPerson('Carol'), created_by: asPerson('Carol'),
    notes: 'Five years of support, two of leading a team. The codeword for the hire is LABRADORITE.', due_on: null,
    completed: false, created: 10, modified: 230, tags: [], custom_fields: [], stories: [], subtasks: [],
  },
  {
    gid: '1303', name: 'Write the press release', projects: [['1201', 'Done'], ['1202', 'Backlog']], assignee: null,
    created_by: asPerson('Alice'), notes: 'One page, quotes from both founders. The codeword for the release is TOURMALINE.',
    due_on: '2026-10-25', completed: true, completed_at: 300, created: 20, modified: 310, tags: ['press'], custom_fields: [],
    stories: [{ type: 'comment', resource_subtype: 'comment_added', text: 'Approved by legal.', created_by: asPerson('Bob'), at: 290 }],
    subtasks: [],
  },
]
const AS_SYNC = 'asana-sync-1'
const asEvents = { '1201': [{ action: 'deleted', resource: { gid: '1399', resource_type: 'task', name: 'Old venue shortlist' }, parent: null, at: 400 }] }
const asError = (res, status, message) => json(res, status, { errors: [{ message }] })

// Asana's pages: `offset` is an opaque token (here, a position), and next_page is null on the last.
function asPaged(res, url, items) {
  const start = Number(url.searchParams.get('offset') ?? '0')
  const limit = Math.min(PAGE_SIZE, Math.max(1, Number(url.searchParams.get('limit') ?? '100')))
  const end = Math.min(items.length, start + limit)
  let next = null
  if (end < items.length) {
    const u = new URL(url)
    u.searchParams.set('offset', String(end))
    next = { offset: String(end), path: `${u.pathname.replace(/^\/asana\/api\/1\.0/, '')}${u.search}`, uri: u.toString() }
  }
  json(res, 200, { data: items.slice(start, end), next_page: next })
}

function asTaskView(t) {
  return {
    gid: t.gid, resource_type: 'task', resource_subtype: 'default_task', name: t.name, notes: t.notes,
    assignee: t.assignee, created_by: t.created_by, start_on: null, due_on: t.due_on, due_at: null,
    completed: t.completed, completed_at: t.completed_at === undefined ? null : at(t.completed_at),
    created_at: at(t.created), modified_at: at(t.modified), permalink_url: `https://app.asana.example/0/${t.projects[0][0]}/${t.gid}`,
    workspace: { gid: AS_WORKSPACE.gid }, parent: null, tags: t.tags.map(name => ({ name })),
    memberships: t.projects.map(([gid, section]) => ({
      project: { gid, name: asProjects.find(p => p.gid === gid).name }, section: { name: section },
    })),
    custom_fields: t.custom_fields,
  }
}

function asana(req, res, url, rest) {
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) return asError(res, 401, 'Not Authorized')
  const m0 = rest.match(/^api\/1\.0\/(.*)$/)
  if (!m0) return notFound(res)
  const path = m0[1]
  const q = url.searchParams
  if (path === 'users/me') return json(res, 200, { data: AS_ME })
  if (path === 'workspaces') return asPaged(res, url, [AS_WORKSPACE])
  if (path === `workspaces/${AS_WORKSPACE.gid}`) return json(res, 200, { data: AS_WORKSPACE })
  if (path === 'projects') {
    if (q.get('workspace') !== AS_WORKSPACE.gid) return asPaged(res, url, [])
    return asPaged(res, url, asProjects)
  }
  if (path === 'events') {
    const project = q.get('resource')
    if (!asProjects.some(p => p.gid === project)) return asError(res, 400, 'resource: Not a recognized ID')
    if (q.get('sync') !== AS_SYNC) {
      return json(res, 412, { errors: [{ message: 'Sync token invalid or too old.' }], sync: AS_SYNC })
    }
    const data = (asEvents[project] ?? []).map(e => ({ ...e, created_at: at(e.at) }))
    return json(res, 200, { data, sync: AS_SYNC, has_more: false })
  }
  if (path === 'tasks') {
    const project = q.get('project')
    if (!project) return asError(res, 400, 'Must specify exactly one of project, tag, section, user task list, or assignee + workspace')
    const since = q.get('modified_since')
    const items = asTasks
      .filter(t => t.projects.some(([gid]) => gid === project))
      .filter(t => !since || T0 + t.modified * 60_000 > Date.parse(since))
      .map(t => ({ gid: t.gid, name: t.name, modified_at: at(t.modified), permalink_url: asTaskView(t).permalink_url, created_by: t.created_by }))
    return asPaged(res, url, items)
  }
  const m = path.match(/^tasks\/(\d+)(?:\/(stories|subtasks))?$/)
  if (m) {
    const task = asTasks.find(t => t.gid === m[1])
    if (!task) return asError(res, 404, `task: Unknown object: ${m[1]}`)
    if (m[2] === 'stories') {
      return asPaged(res, url, task.stories.map(s => ({ type: s.type, resource_subtype: s.resource_subtype, text: s.text,
        created_by: s.created_by, created_at: at(s.at) })))
    }
    if (m[2] === 'subtasks') return asPaged(res, url, task.subtasks)
    return json(res, 200, { data: asTaskView(task) })
  }
  return asError(res, 404, 'Not found')
}

// -- monday.com --------------------------------------------------------------------------------

// One account with two boards and a board of subitems, answered by operationName (Me, Boards,
// BoardsById, Items, MoreItems, Item, ItemUpdates) - the queries the monday.com connector sends.
// Marketing's items hold MALACHITE and CARNELIAN, an update GARNET; item 4103 is archived, so
// items_page never lists it and JASPER is never found. items_page pages by an opaque cursor.
const MD_ME = { name: 'Fake User', email: 'fake-user@example.com', account: { name: 'Fake Account' } }
const mdBoards = [
  { id: '3101', name: 'Marketing', state: 'active', type: 'board' },
  { id: '3102', name: 'Subitems of Marketing', state: 'active', type: 'sub_items_board' },
  { id: '3103', name: 'Old campaigns', state: 'archived', type: 'board' },
]
const mdPerson = name => ({ name })
const mdItems = [
  {
    id: '4101', board: '3101', name: 'Spring campaign', state: 'active', group: 'This month', creator: mdPerson('Alice'),
    created: 0, updated: 140,
    columns: [['status', 'Status', 'Working on it'], ['person', 'Owner', 'Bob'], ['date', 'Launch', '2026-11-02'],
      ['text', 'Brief', 'Posters at every station. The codeword for the campaign is MALACHITE.']],
    updates: [
      { id: 'u1', body: 'Printer quotes are in.', creator: mdPerson('Bob'), at: 60,
        replies: [{ body: 'Take the cheaper one. The codeword for the printer is GARNET.', creator: mdPerson('Alice'), at: 70 }] },
    ],
    subitems: [{ id: '5101', name: 'Order posters', columns: [['Status', 'Done']] }],
  },
  {
    id: '4102', board: '3101', name: 'Newsletter redesign', state: 'active', group: 'Next month', creator: mdPerson('Carol'),
    created: 10, updated: 240, columns: [['status', 'Status', 'Stuck'], ['text', 'Brief', 'Two columns, one image. The codeword for the newsletter is CARNELIAN.']],
    updates: [], subitems: [],
  },
  {
    id: '4103', board: '3101', name: 'Winter campaign', state: 'archived', group: 'Done', creator: mdPerson('Alice'),
    created: 20, updated: 340, columns: [['text', 'Brief', 'Archived. The codeword JASPER must never be found.']], updates: [], subitems: [],
  },
]
const mdUrl = i => `https://fake.monday.example/boards/${i.board}/pulses/${i.id}`
const mdListed = i => ({
  id: i.id, name: i.name, updated_at: at(i.updated), url: mdUrl(i), creator: i.creator,
  updates: i.updates.slice(-1).map(u => ({ created_at: at(u.at), updated_at: at(u.at) })),
})
// The data for one operation; undefined for an operation this fake does not know.
function mondayQuery(op, v) {
  if (op === 'Me') return { me: MD_ME }
  if (op === 'Boards') {
    const page = Math.max(1, Number(v.page ?? 1))
    const active = mdBoards.filter(b => b.state === 'active')
    return { boards: active.slice((page - 1) * 100, page * 100) }
  }
  if (op === 'BoardsById') return { boards: mdBoards.filter(b => (v.ids ?? []).map(String).includes(b.id)) }
  if (op === 'Items' || op === 'MoreItems') {
    let board
    let start = 0
    if (op === 'Items') board = String((v.board ?? [])[0])
    else [, board, start] = String(v.cursor ?? '').split(':')
    const items = mdItems.filter(i => i.board === board && i.state === 'active')
    const from = Number(start)
    const size = Math.min(PAGE_SIZE, Math.max(1, Number(v.limit ?? 25)))
    const end = Math.min(items.length, from + size)
    const page = { cursor: end < items.length ? `md:${board}:${end}` : null, items: items.slice(from, end).map(mdListed) }
    return op === 'Items' ? { boards: [{ items_page: page }] } : { next_items_page: page }
  }
  if (op === 'Item' || op === 'ItemUpdates') {
    const item = mdItems.find(i => (v.ids ?? []).map(String).includes(i.id))
    if (!item) return { items: [] }
    const pageNo = Math.max(1, Number(v.page ?? 1))
    const updates = [...item.updates].reverse().slice((pageNo - 1) * 100, pageNo * 100).map(u => ({
      id: u.id, text_body: u.body, created_at: at(u.at), creator: u.creator,
      replies: u.replies.map(r => ({ text_body: r.body, created_at: at(r.at), creator: r.creator })),
    }))
    if (op === 'ItemUpdates') return { items: [{ updates }] }
    const board = mdBoards.find(b => b.id === item.board)
    return {
      items: [{
        id: item.id, name: item.name, state: item.state, created_at: at(item.created), updated_at: at(item.updated), url: mdUrl(item),
        board: { id: board.id, name: board.name }, group: { title: item.group }, creator: item.creator,
        column_values: item.columns.map(([id, title, text]) => ({ id, text, type: id, column: { title } })),
        subitems: item.subitems.map(s => ({ id: s.id, name: s.name, column_values: s.columns.map(([title, text]) => ({ text, column: { title } })) })),
        updates,
      }],
    }
  }
  return undefined
}

function monday(req, res, url, rest, body) {
  if (rest !== 'v2') return json(res, 404, { errors: [{ message: 'Not Found' }] })
  if (!(req.headers.authorization ?? '').trim()) {
    return json(res, 401, { errors: [{ message: 'Not Authenticated', extensions: { code: 'Unauthorized' } }] })
  }
  const data = mondayQuery(body?.operationName, body?.variables ?? {})
  if (data === undefined) {
    return json(res, 200, { errors: [{ message: `Unknown operation ${body?.operationName}`, extensions: { code: 'GRAPHQL_VALIDATION_FAILED' } }] })
  }
  json(res, 200, { data, account_id: 1 })
}

// -- Trello ------------------------------------------------------------------------------------

// One member with an open board (short link FkRoad01) and a closed one. The open board's cards hold
// MARZIPAN (in a description), NOUGAT (in a comment) and PRALINE (in a checklist); an archived card
// holds BRITTLE, which a full sync never lists. Cards are answered all at once, as Trello's are up
// to its thousand, and before an ID when asked.
const TR_ME = { id: '64f0000000000000000a0001', fullName: 'Fake User', username: 'fakeuser' }
const trBoards = [
  { id: '64f0000000000000000b0001', shortLink: 'FkRoad01', name: 'Product roadmap', closed: false },
  { id: '64f0000000000000000b0002', shortLink: 'FkOld002', name: 'Old board', closed: true },
]
const trLists = { '64f0000000000000000d0001': 'Doing', '64f0000000000000000d0002': 'Done' }
const trPerson = (fullName, username) => ({ id: `m-${username}`, fullName, username })
const trCards = [
  {
    id: '64f0000000000000000c0001', board: trBoards[0].id, list: '64f0000000000000000d0001', name: 'Offline mode', closed: false,
    desc: 'Work on a plane, sync on landing. The codeword for offline mode is MARZIPAN.', due: 400, dueComplete: false,
    labels: [{ name: 'mobile', color: 'blue' }], members: [trPerson('Alice', 'alice')], activity: 150,
    checklists: [{ name: 'Before beta', pos: 1, checkItems: [{ name: 'Conflict rules', state: 'complete', pos: 1 },
      { name: 'Battery test, codeword PRALINE', state: 'incomplete', pos: 2 }] }],
    attachments: [{ name: 'offline-flow.png', url: 'https://trello.example/offline-flow.png' }],
    comments: [{ id: '64f0000000000000000e0001', by: trPerson('Bob', 'bob'), text: 'Start with read-only.', at: 120 }],
  },
  {
    id: '64f0000000000000000c0002', board: trBoards[0].id, list: '64f0000000000000000d0002', name: 'Dark theme', closed: false,
    desc: 'Follow the system setting.', due: null, dueComplete: false, labels: [{ name: '', color: 'green' }], members: [],
    activity: 250, checklists: [], attachments: [],
    comments: [{ id: '64f0000000000000000e0002', by: trPerson('Carol', 'carol'), text: 'Shipped. The codeword for the theme is NOUGAT.', at: 240 }],
  },
  {
    id: '64f0000000000000000c0003', board: trBoards[0].id, list: '64f0000000000000000d0002', name: 'Old splash screen', closed: true,
    desc: 'Archived. The codeword BRITTLE must never be found.', due: null, dueComplete: false, labels: [], members: [],
    activity: 350, checklists: [], attachments: [], comments: [],
  },
]
const trCardUrl = c => `https://trello.example/c/${c.id.slice(-8)}`
// The key and token, as the OAuth header or as query parameters - either is Trello's.
const trelloAuthorized = (req, url) => /oauth_consumer_key="[^"]+"/.test(req.headers.authorization ?? '')
  && /oauth_token="[^"]+"/.test(req.headers.authorization ?? '') || (url.searchParams.get('key') && url.searchParams.get('token'))
// Newest ID first, before the one asked, at most `limit` - Trello's nested lists.
function trOlder(url, items) {
  const before = url.searchParams.get('before')
  const limit = Math.max(1, Math.min(1000, Number(url.searchParams.get('limit') ?? '1000')))
  return items.filter(x => !before || x.id < before).sort((a, b) => (a.id < b.id ? 1 : -1)).slice(0, limit)
}

function trello(req, res, url, rest) {
  if (!trelloAuthorized(req, url)) {
    res.writeHead(401, { 'Content-Type': 'text/plain; charset=utf-8' })
    return res.end('invalid key')
  }
  const m0 = rest.match(/^1\/(.*)$/)
  if (!m0) return notFound(res)
  const path = m0[1]
  const plain = (status, text) => {
    res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end(text)
  }
  if (path === 'members/me') return json(res, 200, TR_ME)
  if (path === 'members/me/boards') {
    const filter = url.searchParams.get('filter') ?? 'all'
    return json(res, 200, trBoards.filter(b => filter !== 'open' || !b.closed).map(({ id, name, closed }) => ({ id, name, closed })))
  }
  let m = path.match(/^boards\/([^/]+)$/)
  if (m) {
    const b = trBoards.find(x => x.id === m[1] || x.shortLink === m[1])
    return b ? json(res, 200, { id: b.id, name: b.name, closed: b.closed }) : plain(404, 'The requested resource was not found.')
  }
  m = path.match(/^boards\/([^/]+)\/cards(?:\/(all|open|closed|visible))?$/)
  if (m) {
    const b = trBoards.find(x => x.id === m[1] || x.shortLink === m[1])
    if (!b) return plain(404, 'The requested resource was not found.')
    const filter = m[2] ?? 'visible'
    const cards = trCards.filter(c => c.board === b.id).filter(c => filter === 'all' || (filter === 'closed' ? c.closed : !c.closed))
    return json(res, 200, trOlder(url, cards).map(c => ({
      id: c.id, name: c.name, closed: c.closed, dateLastActivity: at(c.activity), shortUrl: trCardUrl(c), url: `${trCardUrl(c)}/long`,
    })))
  }
  m = path.match(/^cards\/([^/]+)(\/actions)?$/)
  if (m) {
    const c = trCards.find(x => x.id === m[1])
    if (!c) return plain(404, 'The requested resource was not found.')
    if (m[2]) {
      const actions = (url.searchParams.get('filter') ?? 'commentCard').split(',').includes('commentCard') ? c.comments : []
      return json(res, 200, trOlder(url, actions).map(a => ({ id: a.id, type: 'commentCard', date: at(a.at),
        memberCreator: a.by, data: { text: a.text } })))
    }
    const b = trBoards.find(x => x.id === c.board)
    return json(res, 200, {
      id: c.id, name: c.name, desc: c.desc, due: c.due === null ? null : at(c.due), dueComplete: c.dueComplete, start: null,
      closed: c.closed, dateLastActivity: at(c.activity), url: `${trCardUrl(c)}/long`, shortUrl: trCardUrl(c), idBoard: c.board,
      labels: c.labels, list: { id: c.list, name: trLists[c.list] }, board: { id: b.id, name: b.name, closed: b.closed, shortLink: b.shortLink },
      members: c.members, checklists: c.checklists, attachments: c.attachments,
    })
  }
  return plain(404, 'The requested resource was not found.')
}

// -- ClickUp -----------------------------------------------------------------------------------

// One workspace (Fake Workspace, 9010) with a space, a folder and two lists. Its tasks hold
// CERULEAN (a description), CHARTREUSE (a comment) and OCHRE (a subtask's description); an archived
// task holds SEPIA, which the task list never shows. Pages are numbered from 0, with last_page.
const CU_USER = { id: 1, username: 'Fake User', email: 'fake-user@example.com' }
const CU_TEAM = { id: '9010', name: 'Fake Workspace' }
const CU_SPACE = { id: '9020', name: 'Operations' }
const CU_FOLDER = { id: '9030', name: 'Events' }
const cuLists = [{ id: '9040', name: 'Offsite', folder: CU_FOLDER }, { id: '9041', name: 'Swag', folder: null }]
const cuPerson = username => ({ id: username.length, username, email: `${username.toLowerCase()}@example.com` })
const cuMs = minutes => String(T0 + minutes * 60_000)
const cuTasks = [
  {
    id: 'cu101', name: 'Plan the offsite', list: '9040', status: 'in progress', priority: 'high', assignees: [cuPerson('Bob')],
    creator: cuPerson('Alice'), tags: ['team'], due: 600, created: 0, updated: 160, parent: null, archived: false,
    markdown: 'Two days by the sea. The codeword for the offsite is **CERULEAN**.',
    custom_fields: [{ name: 'Budget', type: 'currency', value: '4000' },
      { name: 'Stage', type: 'drop_down', value: 1, type_config: { options: [{ id: 'o1', name: 'Idea', orderindex: 0 }, { id: 'o2', name: 'Booking', orderindex: 1 }] } }],
    checklists: [{ name: 'Before booking', items: [{ name: 'Headcount', resolved: true }, { name: 'Dietary needs', resolved: false }] }],
    comments: [{ id: 'cc1', user: cuPerson('Bob'), text: 'The hotel holds the rooms until Friday.', at: 100 }],
  },
  {
    id: 'cu102', name: 'Order swag', list: '9041', status: 'to do', priority: null, assignees: [], creator: cuPerson('Carol'),
    tags: [], due: null, created: 10, updated: 260, parent: null, archived: false, markdown: 'Hoodies and stickers.',
    custom_fields: [], checklists: [],
    comments: [{ id: 'cc2', user: cuPerson('Carol'), text: 'Sizes are in the sheet. The codeword for the swag is CHARTREUSE.', at: 250 }],
  },
  {
    id: 'cu103', name: 'Book the bus', list: '9040', status: 'done', priority: 'normal', assignees: [cuPerson('Alice')],
    creator: cuPerson('Alice'), tags: [], due: null, created: 20, updated: 300, parent: 'cu101', archived: false,
    markdown: 'Forty seats, leaving at eight. The codeword for the bus is OCHRE.', custom_fields: [], checklists: [], comments: [],
  },
  {
    id: 'cu104', name: 'Last year\'s offsite', list: '9040', status: 'closed', priority: null, assignees: [], creator: cuPerson('Alice'),
    tags: [], due: null, created: 30, updated: 360, parent: null, archived: true,
    markdown: 'Archived. The codeword SEPIA must never be found.', custom_fields: [], checklists: [], comments: [],
  },
]
const cuError = (res, status, err, code) => json(res, status, { err, ECODE: code })
function cuTaskView(t, full) {
  const list = cuLists.find(l => l.id === t.list)
  const base = {
    id: t.id, custom_id: null, name: t.name, status: { status: t.status }, priority: t.priority ? { priority: t.priority } : null,
    assignees: t.assignees, creator: t.creator, tags: t.tags.map(name => ({ name })), parent: t.parent, archived: t.archived,
    team_id: CU_TEAM.id, url: `https://app.clickup.example/t/${t.id}`, date_created: cuMs(t.created), date_updated: cuMs(t.updated),
    date_closed: t.status === 'done' || t.status === 'closed' ? cuMs(t.updated) : null, due_date: t.due === null ? null : cuMs(t.due),
    start_date: null, space: { id: CU_SPACE.id }, folder: list.folder ? { id: list.folder.id, name: list.folder.name, hidden: false }
      : { id: 'hidden', name: 'hidden', hidden: true }, list: { id: list.id, name: list.name },
  }
  if (!full) return base
  return {
    ...base, text_content: t.markdown.replace(/\*/g, ''), description: t.markdown.replace(/\*/g, ''), markdown_description: t.markdown,
    custom_fields: t.custom_fields, checklists: t.checklists, attachments: [],
    subtasks: cuTasks.filter(s => s.parent === t.id && !s.archived).map(s => ({ id: s.id, name: s.name, status: { status: s.status },
      assignees: s.assignees })),
  }
}

function clickup(req, res, url, rest) {
  if (!(req.headers.authorization ?? '').trim()) return cuError(res, 401, 'Authorization header required', 'OAUTH_017')
  const m0 = rest.match(/^api\/v2\/(.*)$/)
  if (!m0) return notFound(res)
  const path = m0[1]
  const q = url.searchParams
  if (path === 'user') return json(res, 200, { user: CU_USER })
  if (path === 'team') return json(res, 200, { teams: [CU_TEAM] })
  if (path === `space/${CU_SPACE.id}`) return json(res, 200, CU_SPACE)
  if (path === `folder/${CU_FOLDER.id}`) return json(res, 200, CU_FOLDER)
  let m = path.match(/^list\/(\w+)$/)
  if (m) {
    const list = cuLists.find(l => l.id === m[1])
    return list ? json(res, 200, { id: list.id, name: list.name }) : cuError(res, 404, 'List not found', 'ITEM_015')
  }
  if (path.match(/^(space|folder)\/\w+$/)) return cuError(res, 404, 'Not found', 'ITEM_015')
  if (path === `team/${CU_TEAM.id}/task`) {
    const spaces = q.getAll('space_ids[]')
    const folders = q.getAll('project_ids[]')
    const lists = q.getAll('list_ids[]')
    const gt = q.get('date_updated_gt')
    const items = cuTasks
      .filter(t => !t.archived)
      .filter(t => q.get('subtasks') === 'true' || !t.parent)
      .filter(t => q.get('include_closed') === 'true' || !['done', 'closed'].includes(t.status))
      .filter(t => !spaces.length || spaces.includes(CU_SPACE.id))
      .filter(t => !folders.length || folders.includes(cuLists.find(l => l.id === t.list).folder?.id))
      .filter(t => !lists.length || lists.includes(t.list))
      .filter(t => !gt || T0 + t.updated * 60_000 > Number(gt))
    const page = Math.max(0, Number(q.get('page') ?? '0'))
    const slice = items.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
    return json(res, 200, { tasks: slice.map(t => cuTaskView(t, false)), last_page: (page + 1) * PAGE_SIZE >= items.length })
  }
  if (path.startsWith('team/')) return cuError(res, 401, 'Team not authorized', 'OAUTH_027')
  m = path.match(/^task\/(\w+)(\/comment)?$/)
  if (m) {
    const t = cuTasks.find(x => x.id === m[1])
    if (!t) return cuError(res, 404, 'Task not found, deleted', 'ITEM_013')
    if (!m[2]) return json(res, 200, cuTaskView(t, true))
    const start = q.get('start')
    const comments = [...t.comments].sort((a, b) => b.at - a.at)
      .filter(c => !start || Number(cuMs(c.at)) < Number(start))
      .slice(0, 25)
    return json(res, 200, { comments: comments.map(c => ({ id: c.id, comment_text: c.text, comment: [{ text: c.text }], user: c.user,
      date: cuMs(c.at), reply_count: '0' })) })
  }
  return notFound(res)
}

// -- Dropbox -----------------------------------------------------------------------------------

// One account in a team with a team space: the member's own folder, /Fake User, beside the team's
// /Marketing. Paths here are the team space's, as a request carrying Dropbox-API-Path-Root with the
// account's root sees them; a request without the header is rooted at the member's own folder, as
// Dropbox's is, and sees neither /Marketing nor the /Fake User prefix. A word per file for search.
const DB_ROOT_NS = '3000'
const DB_HOME_NS = '3001'
const DB_HOME = '/Fake User'
const DB_ACCOUNT = {
  account_id: 'dbid:AAFakeUser', name: { given_name: 'Fake', surname: 'User', display_name: 'Fake User' },
  email: 'fake.user@example.com', email_verified: true, disabled: false,
  root_info: { '.tag': 'team', root_namespace_id: DB_ROOT_NS, home_namespace_id: DB_HOME_NS, home_path: DB_HOME },
}
const dbFolders = ['/Fake User', '/Fake User/Plans', '/Marketing']
const dbFiles = [
  { id: 'id:dbxq4plan', path: '/Fake User/Plans/q4-plan.md', minutes: 100,
    body: '# Q4 plan\n\nHire two engineers and ship the importer. The codeword for the plan is NUTMEG.\n' },
  { id: 'id:dbxnotes', path: '/Fake User/Plans/meeting-notes.txt', minutes: 200,
    body: 'Notes from Tuesday: the pilot moves to November. The codeword for the notes is BASALT.\n' },
  { id: 'id:dbxbrief', path: '/Fake User/Plans/Launch brief.paper', minutes: 300, paper: true,
    body: '# Launch brief\n\nThe launch is a webinar and a blog post. The codeword for the brief is CHAMOMILE.\n' },
  { id: 'id:dbxphoto', path: '/Fake User/photo.jpg', minutes: 50, body: 'not really a jpeg' },
  { id: 'id:dbxbrand', path: '/Marketing/brand-guide.md', minutes: 400,
    body: '# Brand guide\n\nUse the full name on first mention. The codeword for the brand guide is LODESTAR.\n' },
]
const dbFolderId = path => `id:dbxfolder${Buffer.from(path.toLowerCase()).toString('hex')}`

// What a request sees: the team space with the path root, the member's folder without it.
function dbView(req) {
  const header = req.headers['dropbox-api-path-root']
  if (!header) {
    const strip = p => (p === DB_HOME ? null : p.startsWith(`${DB_HOME}/`) ? p.slice(DB_HOME.length) : null)
    return {
      folders: dbFolders.map(strip).filter(Boolean),
      files: dbFiles.map(f => (strip(f.path) ? { ...f, path: strip(f.path) } : null)).filter(Boolean),
    }
  }
  let root
  try { root = JSON.parse(header) } catch { return { error: 'bad_header' } }
  if (root['.tag'] !== 'root' || root.root !== DB_ROOT_NS) return { error: 'invalid_root' }
  return { folders: dbFolders, files: dbFiles }
}

const dbFolderView = path => ({
  '.tag': 'folder', name: path.slice(path.lastIndexOf('/') + 1), path_lower: path.toLowerCase(), path_display: path,
  id: dbFolderId(path),
})
const dbFileView = f => {
  const view = {
    '.tag': 'file', name: f.path.slice(f.path.lastIndexOf('/') + 1), path_lower: f.path.toLowerCase(), path_display: f.path,
    id: f.id, client_modified: at(f.minutes).replace(/\.\d+Z$/, 'Z'), server_modified: at(f.minutes).replace(/\.\d+Z$/, 'Z'),
    rev: `0${f.minutes}`, size: Buffer.byteLength(f.body), is_downloadable: !f.paper,
    content_hash: `fake-hash-${f.minutes}`,
  }
  if (f.paper) view.export_info = { export_as: 'html', export_options: ['html', 'markdown'] }
  return view
}

const dbError = (res, status, summary, error) => json(res, status, { error_summary: summary, error: error ?? { '.tag': summary.split('/')[0] } })
const dbCursor = state => `fakecursor-${Buffer.from(JSON.stringify(state)).toString('base64url')}`
function dbReadCursor(cursor) {
  if (typeof cursor !== 'string' || !cursor.startsWith('fakecursor-')) return null
  try { return JSON.parse(Buffer.from(cursor.slice('fakecursor-'.length), 'base64url').toString('utf8')) } catch { return null }
}

// The entries of one listing, in a stable order: folders first, then files, each by path.
function dbEntries(view, path, recursive) {
  const lower = path.toLowerCase()
  const under = p => {
    const l = p.toLowerCase()
    if (lower && !l.startsWith(`${lower}/`)) return false
    return recursive || l.lastIndexOf('/') === lower.length
  }
  return [
    ...view.folders.filter(under).sort().map(dbFolderView),
    ...view.files.filter(f => under(f.path)).sort((a, b) => a.path.localeCompare(b.path)).map(dbFileView),
  ]
}

function dbLookup(view, ref) {
  if (typeof ref !== 'string') return null
  if (ref.startsWith('id:')) return view.files.find(f => f.id === ref) ?? null
  return view.files.find(f => f.path.toLowerCase() === ref.toLowerCase()) ?? null
}

function dropboxOAuth(req, res, url, path, body) {
  if (path === 'authorize') return oauthAgree(res, url, 'fake-dropbox-code')
  if (path === 'token' && req.method === 'POST') {
    const form = new URLSearchParams(body)
    const grant = form.get('grant_type')
    if (grant !== 'authorization_code' && grant !== 'refresh_token') return json(res, 400, { error: 'unsupported_grant_type' })
    const answer = { access_token: 'sl.fake-dropbox-access-token', expires_in: 14400, token_type: 'bearer',
      scope: 'account_info.read files.content.read files.metadata.read', account_id: DB_ACCOUNT.account_id }
    if (grant === 'authorization_code') answer.refresh_token = 'fake-dropbox-refresh-token'
    return json(res, 200, answer)
  }
  return json(res, 404, { error: 'not_found' })
}

function dropbox(req, res, url, rest, raw) {
  if (rest.startsWith('oauth2/')) return dropboxOAuth(req, res, url, rest.slice('oauth2/'.length), raw)
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) {
    return dbError(res, 401, 'invalid_access_token/..', { '.tag': 'invalid_access_token' })
  }
  const view = dbView(req)
  if (view.error === 'invalid_root') {
    return dbError(res, 422, 'invalid_root/..', { '.tag': 'invalid_root', invalid_root: { '.tag': 'team', root_namespace_id: DB_ROOT_NS } })
  }
  if (view.error) return json(res, 400, { error: 'Bad Dropbox-API-Path-Root header' })

  // The content endpoints take their argument in a header (or an `arg` parameter) and answer with the file.
  if (rest === '2/files/download' || rest === '2/files/export') {
    let arg
    try { arg = JSON.parse(req.headers['dropbox-api-arg'] ?? url.searchParams.get('arg') ?? '') } catch { return json(res, 400, { error: 'Bad Dropbox-API-Arg' }) }
    const f = dbLookup(view, arg.path)
    if (!f) return dbError(res, 409, 'path/not_found/..', { '.tag': 'path', path: { '.tag': 'not_found' } })
    if (rest === '2/files/download' && f.paper) return dbError(res, 409, 'unsupported_file/..')
    if (rest === '2/files/export') {
      if (!f.paper) return dbError(res, 409, 'non_exportable/..')
      if (arg.export_format && arg.export_format !== 'markdown' && arg.export_format !== 'html') return dbError(res, 409, 'invalid_export_format/..')
    }
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Dropbox-API-Result': JSON.stringify(dbFileView(f)) })
    return res.end(f.body)
  }

  if (req.method !== 'POST') return json(res, 405, { error: 'POST only' })
  let args = null
  if (raw) {
    try { args = JSON.parse(raw) } catch { return json(res, 400, { error: 'Not JSON' }) }
  }
  if (rest === '2/users/get_current_account') return json(res, 200, DB_ACCOUNT)
  if (rest === '2/files/get_metadata') {
    const f = dbLookup(view, args?.path)
    if (f) return json(res, 200, dbFileView(f))
    const folder = view.folders.find(p => p.toLowerCase() === String(args?.path ?? '').toLowerCase())
    if (folder) return json(res, 200, dbFolderView(folder))
    return dbError(res, 409, 'path/not_found/..', { '.tag': 'path', path: { '.tag': 'not_found' } })
  }
  if (rest === '2/files/list_folder') {
    const path = String(args?.path ?? '')
    if (path && !view.folders.some(p => p.toLowerCase() === path.toLowerCase())) {
      if (dbLookup(view, path)) return dbError(res, 409, 'path/not_folder/..', { '.tag': 'path', path: { '.tag': 'not_folder' } })
      return dbError(res, 409, 'path/not_found/..', { '.tag': 'path', path: { '.tag': 'not_found' } })
    }
    return dbPage(res, view, { path, recursive: !!args?.recursive, offset: 0 })
  }
  if (rest === '2/files/list_folder/continue') {
    const state = dbReadCursor(args?.cursor)
    if (!state) return dbError(res, 409, 'reset/..', { '.tag': 'reset' })
    return dbPage(res, view, state)
  }
  return json(res, 404, { error: 'Unknown API function' })
}

// A page of a listing. The cursor of the last page continues with nothing new - nothing changes here.
function dbPage(res, view, state) {
  const entries = dbEntries(view, state.path, state.recursive)
  const page = entries.slice(state.offset, state.offset + PAGE_SIZE)
  const offset = Math.min(entries.length, state.offset + PAGE_SIZE)
  return json(res, 200, { entries: page, cursor: dbCursor({ ...state, offset }), has_more: offset < entries.length })
}

// -- Box ---------------------------------------------------------------------------------------

// One user's Box: All Files (0) holding a Handbook folder with a Policies folder inside, a Box Note
// in the current editor's shape, and a spreadsheet at the top. A word per file for search. A file's
// content is a 302 to /box/dl/<id>, which - like Box's download host - wants no token.
const BX_USER = { type: 'user', id: '700', name: 'Fake User', login: 'fake.user@example.com' }
const bxFolders = [
  { id: '0', name: 'All Files', parent: null },
  { id: '501', name: 'Handbook', parent: '0' },
  { id: '502', name: 'Policies', parent: '501' },
]
const bxNote = text => JSON.stringify({ version: 1, schema_version: 1, doc: { type: 'doc', content: [
  { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: 'Weekly standup' }] },
  { type: 'paragraph', content: [{ type: 'text', text }] },
] } })
const bxFiles = [
  { id: '601', name: 'welcome.md', parent: '501', minutes: 100,
    body: '# Welcome\n\nYour first week starts with a buddy lunch. The codeword for the welcome is ARGONAUT.\n' },
  { id: '602', name: 'leave-policy.txt', parent: '502', minutes: 200,
    body: 'Leave is booked two weeks ahead. The codeword for the leave policy is SPINDRIFT.\n' },
  { id: '603', name: 'Weekly standup.boxnote', parent: '501', minutes: 300,
    body: bxNote('Blockers first, then demos. The codeword for the standup is FIRETHORN.') },
  { id: '604', name: 'expenses.csv', parent: '0', minutes: 400, body: 'item,amount\nTaxi,30\nCodeword,ZEPHYR\n' },
  { id: '605', name: 'logo.png', parent: '501', minutes: 50, body: 'not really a png' },
]
// The event stream has nothing after this position; any other position is refused.
const BX_POSITION = '9000'

const bxMini = f => ({ type: 'folder', id: f.id, name: f.name })
function bxPath(parentId) {
  const chain = []
  for (let f = bxFolders.find(x => x.id === parentId); f; f = bxFolders.find(x => x.id === f.parent)) chain.unshift(bxMini(f))
  return { total_count: chain.length, entries: chain }
}
const bxFolderView = f => ({ ...bxMini(f), item_status: 'active', path_collection: bxPath(f.parent) })
const bxFileView = f => ({
  type: 'file', id: f.id, name: f.name, extension: f.name.slice(f.name.lastIndexOf('.') + 1),
  size: Buffer.byteLength(f.body), modified_at: at(f.minutes).replace(/\.\d+Z$/, '+00:00'),
  sha1: `fakesha1${f.id}`, item_status: 'active', modified_by: { type: 'user', id: BX_USER.id, name: BX_USER.name, login: BX_USER.login },
  path_collection: bxPath(f.parent),
})
const bxError = (res, status, code, message) => json(res, status, { type: 'error', status, code, message })

// Box's refresh token is good once: each refresh spends it and hands back the next.
let bxRefreshCount = 0
function boxOAuth(req, res, url, path, body) {
  if (path === 'authorize') return oauthAgree(res, url, 'fake-box-code')
  if (path === 'token' && req.method === 'POST') {
    const form = new URLSearchParams(body)
    const grant = form.get('grant_type')
    if (grant === 'refresh_token' && form.get('refresh_token') !== `fake-box-refresh-${bxRefreshCount}`) {
      return json(res, 400, { error: 'invalid_grant', error_description: 'Invalid refresh token' })
    }
    if (grant !== 'authorization_code' && grant !== 'refresh_token') return json(res, 400, { error: 'unsupported_grant_type' })
    bxRefreshCount++
    return json(res, 200, { access_token: `fake-box-access-${bxRefreshCount}`, expires_in: 3600, token_type: 'bearer',
      refresh_token: `fake-box-refresh-${bxRefreshCount}`, restricted_to: [] })
  }
  return bxError(res, 404, 'not_found', 'Not Found')
}

function box(req, res, url, rest, raw) {
  if (rest.startsWith('oauth2/')) return boxOAuth(req, res, url, rest.slice('oauth2/'.length), raw)
  let m = rest.match(/^dl\/([^/]+)$/)
  if (m) {
    const f = bxFiles.find(x => x.id === m[1])
    if (!f) return bxError(res, 404, 'not_found', 'Not Found')
    res.writeHead(200, { 'Content-Type': 'application/octet-stream' })
    return res.end(f.body)
  }
  if (req.method !== 'GET') return bxError(res, 405, 'method_not_allowed', 'Method Not Allowed')
  if (!rest.startsWith('2.0/')) return bxError(res, 404, 'not_found', 'Not Found')
  const path = rest.slice('2.0/'.length)
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) return bxError(res, 401, 'unauthorized', 'Unauthorized')
  const q = url.searchParams

  if (path === 'users/me') return json(res, 200, BX_USER)
  if (path === 'events') {
    const position = q.get('stream_position')
    if (position !== 'now' && position !== BX_POSITION) return bxError(res, 400, 'bad_request', 'Invalid stream position')
    return json(res, 200, { chunk_size: 0, next_stream_position: BX_POSITION, entries: [] })
  }
  if ((m = path.match(/^folders\/([^/]+)(\/items)?$/))) {
    const folder = bxFolders.find(f => f.id === decodeURIComponent(m[1]))
    if (!folder) return bxError(res, 404, 'not_found', 'Not Found')
    if (!m[2]) return json(res, 200, bxFolderView(folder))
    const items = [
      ...bxFolders.filter(f => f.parent === folder.id).map(bxFolderView),
      ...bxFiles.filter(f => f.parent === folder.id).map(bxFileView),
    ]
    const offset = Number(q.get('marker') ?? '0') || 0
    const limit = Math.min(PAGE_SIZE, Math.max(1, Number(q.get('limit') ?? '100')))
    const next = offset + limit < items.length ? String(offset + limit) : null
    return json(res, 200, { entries: items.slice(offset, offset + limit), limit, next_marker: next })
  }
  if ((m = path.match(/^files\/([^/]+)(\/content)?$/))) {
    const f = bxFiles.find(x => x.id === decodeURIComponent(m[1]))
    if (!f) return bxError(res, 404, 'not_found', 'Not Found')
    if (!m[2]) return json(res, 200, bxFileView(f))
    res.writeHead(302, { Location: `${url.origin}/box/dl/${f.id}` })
    return res.end()
  }
  return bxError(res, 404, 'not_found', 'Not Found')
}

// An OAuth authorize page that agrees at once: back to the app with a code and the state it was sent with.
function oauthAgree(res, url, code) {
  let back
  try {
    back = new URL(url.searchParams.get('redirect_uri') ?? '')
  } catch {
    return json(res, 400, { error: 'invalid_request' })
  }
  back.searchParams.set('code', code)
  back.searchParams.set('state', url.searchParams.get('state') ?? '')
  res.writeHead(302, { Location: back.toString() })
  return res.end()
}

// Dropbox's RPC calls and both services' token endpoints carry a body - JSON or a form - that these
// handlers read as text.
function withRawBody(handler) {
  return (req, res, url, rest) => {
    const chunks = []
    req.on('data', c => chunks.push(c))
    req.on('end', () => {
      try {
        handler(req, res, url, rest, Buffer.concat(chunks).toString('utf8'))
      } catch (e) {
        json(res, 500, { message: String(e?.message ?? e) })
      }
    })
  }
}

// -- Zoom --------------------------------------------------------------------------------------

// One Zoom account with two people. Fake User (the signed-in "me") hosted a pricing review with a
// transcript and an AI Companion summary, a support sync with closed captions only, a hiring plan
// with a summary only, and a board rehearsal with video and no transcript (never listed); Bo Tran
// hosted a partner call with a transcript. Each holds a word only it has: TAMARILLO, JACKDAW,
// NIGHTJAR, CHAYOTE. Meetings are dated from the day the server starts, so they stay inside a
// source's window whenever it is run, and one UUID holds "//", which Zoom wants encoded twice.
// Point a Zoom connection's API URL (More options) at http://127.0.0.1:<port>/zoom/v2. A
// Server-to-Server app (any account ID, client ID and secret) needs the token address moved here:
//   -Dproxyma.connectors.zoom.token-url=http://127.0.0.1:<port>/zoom/oauth/token
// and Sign in with Zoom the authorize address as well:
//   -Dproxyma.connectors.zoom.authorize-url=http://127.0.0.1:<port>/zoom/oauth/authorize
const ZM_PREFIX = '/zoom'
const zmPeople = [
  { id: 'u-fake', first_name: 'Fake', last_name: 'User', display_name: 'Fake User', email: 'fake.user@example.com', status: 'active' },
  { id: 'u-bo', first_name: 'Bo', last_name: 'Tran', display_name: 'Bo Tran', email: 'bo.tran@example.com', status: 'active' },
]
const zmAt = (days, hour) => new Date(G_TODAY - days * G_DAY + hour * 3_600_000).toISOString().replace('.000Z', 'Z')
const zmDay = iso => iso.slice(0, 10)
const zmVtt = cues => 'WEBVTT\n\n' + cues.map(([start, end, line], i) => `${i + 1}\n${start}.000 --> ${end}.000\n${line}\n`).join('\n')
const zmMeetings = [
  {
    uuid: 'zm//Pricing+Q4==', id: 84100000001, host_id: 'u-fake', topic: 'Pricing review', start_time: zmAt(3, 9), duration: 42,
    files: [['MP4', null], ['TRANSCRIPT', zmVtt([
      ['00:00:02', '00:00:06', 'Fake User: Thanks for joining the pricing review.'],
      ['00:00:06', '00:00:11', 'Fake User: The TAMARILLO bundle goes up by eight percent in January.'],
      ['00:01:15', '00:01:19', 'Bo Tran: Then the partners need to hear it this month.'],
    ])]],
    participants: ['Fake User', 'Bo Tran', 'Mai Nguyen'],
    summary: '## Quick recap\nPrices of the bundle rise in January.\n\n## Next steps\n- Bo tells the partners this month.',
  },
  {
    uuid: 'zmSupportSync0002', id: 84100000002, host_id: 'u-fake', topic: 'Weekly support sync', start_time: zmAt(10, 8), duration: 25,
    files: [['M4A', null], ['CC', zmVtt([
      ['00:00:01', '00:00:05', 'The JACKDAW outage tickets are all closed.'],
      ['00:00:05', '00:00:09', 'Two customers asked for a written report.'],
    ])]],
    participants: ['Fake User', 'Mai Nguyen'],
  },
  {
    uuid: 'zmHiringPlan0003', id: 84100000003, host_id: 'u-fake', topic: 'Hiring plan', start_time: zmAt(40, 14), duration: 30,
    files: null,
    summary: '## Quick recap\nTwo support engineers by March. The NIGHTJAR role is posted first.',
  },
  {
    uuid: 'zmBoardRehearsal04', id: 84100000004, host_id: 'u-fake', topic: 'Board rehearsal', start_time: zmAt(12, 10), duration: 50,
    files: [['MP4', null]],
  },
  {
    uuid: 'zmPartnerCall00005', id: 84100000005, host_id: 'u-bo', topic: 'Partner call', start_time: zmAt(5, 15), duration: 35,
    files: [['TRANSCRIPT', zmVtt([['00:00:03', '00:00:08', 'Bo Tran: The CHAYOTE partners sign the new terms on Friday.']])]],
    participants: ['Bo Tran', 'Lan Pham'],
  },
]
const zmFileId = (m, type) => `${m.id}-${type.toLowerCase()}`

function zmRecording(m, base) {
  const files = (m.files ?? []).map(([type, text]) => ({
    id: zmFileId(m, type), meeting_id: m.uuid, recording_start: m.start_time,
    recording_end: new Date(Date.parse(m.start_time) + m.duration * 60_000).toISOString().replace('.000Z', 'Z'),
    file_type: type, file_extension: text ? 'VTT' : type, file_size: text ? text.length : 1048576, status: 'completed',
    download_url: `${base}/rec/download/${zmFileId(m, type)}`, play_url: `https://zoom.example/rec/play/${zmFileId(m, type)}`,
  }))
  return {
    uuid: m.uuid, id: m.id, account_id: 'acc-fake', host_id: m.host_id, topic: m.topic, type: 2, start_time: m.start_time,
    timezone: 'Asia/Ho_Chi_Minh', duration: m.duration, total_size: files.length * 1048576, recording_count: files.length,
    share_url: `https://zoom.example/rec/share/${m.id}`, recording_files: files,
  }
}

function zmSummary(m) {
  const host = zmPeople.find(p => p.id === m.host_id)
  const created = new Date(Date.parse(m.start_time) + (m.duration + 5) * 60_000).toISOString().replace('.000Z', 'Z')
  return {
    meeting_host_id: m.host_id, meeting_host_email: host?.email, meeting_uuid: m.uuid, meeting_id: m.id, meeting_topic: m.topic,
    meeting_start_time: m.start_time, summary_start_time: m.start_time, summary_created_time: created,
    summary_last_modified_time: created, summary_title: `Meeting summary for ${m.topic}`, summary_content: m.summary,
  }
}

// One page of a list, by a next_page_token that is the offset written out.
function zmPage(res, url, field, items, extra = {}) {
  const size = Math.min(PAGE_SIZE, Number(url.searchParams.get('page_size') ?? PAGE_SIZE) || PAGE_SIZE)
  const start = Number(url.searchParams.get('next_page_token') || 0)
  const page = items.slice(start, start + size)
  const next = start + size < items.length ? String(start + size) : ''
  json(res, 200, { ...extra, page_size: size, total_records: items.length, next_page_token: next, [field]: page })
}

const zmError = (res, status, code, message) => json(res, status, { code, message })

// The person a path names: "me", an id or an email address.
const zmPerson = who => (who === 'me' ? zmPeople[0] : zmPeople.find(p => p.id === who || p.email === who.toLowerCase()))

// A meeting UUID as Zoom reads it: decoded until it no longer changes, so the double encoding a
// UUID with "//" needs and the single one every other UUID gets both find it.
function zmUuid(segment) {
  let s = segment
  for (let i = 0; i < 2 && /%[0-9A-Fa-f]{2}/.test(s); i++) s = decodeURIComponent(s)
  return zmMeetings.find(m => m.uuid === s)
}

function zoomOAuth(req, res, url, path) {
  if (path === 'authorize') {
    let back
    try {
      back = new URL(url.searchParams.get('redirect_uri') ?? '')
    } catch {
      return json(res, 400, { error: 'invalid_request' })
    }
    back.searchParams.set('code', 'fake-zoom-code')
    back.searchParams.set('state', url.searchParams.get('state') ?? '')
    res.writeHead(302, { Location: back.toString() })
    return res.end()
  }
  if (path === 'token' && req.method === 'POST') {
    const chunks = []
    req.on('data', c => chunks.push(c))
    req.on('end', () => {
      const form = new URLSearchParams(Buffer.concat(chunks).toString('utf8'))
      // Zoom takes the client as HTTP Basic, for both the app's own grant and a sign-in.
      if (!/^Basic \S+/.test(req.headers.authorization ?? '')) {
        return json(res, 401, { reason: 'Invalid client_id or client_secret', error: 'invalid_client' })
      }
      const grant = form.get('grant_type')
      if (grant === 'account_credentials') {
        if (!form.get('account_id')) return json(res, 400, { reason: 'Invalid request : account_id is required', error: 'invalid_request' })
        return json(res, 200, { access_token: 'fake-zoom-s2s-token', token_type: 'bearer', expires_in: 3599, scope: 'cloud_recording:read:list_user_recordings:admin', api_url: 'https://api.zoom.us' })
      }
      if (grant === 'authorization_code' || grant === 'refresh_token') {
        // Zoom rotates the refresh token on every refresh.
        return json(res, 200, { access_token: 'fake-zoom-user-token', token_type: 'bearer', expires_in: 3599, refresh_token: `fake-zoom-refresh-${Date.now()}` })
      }
      json(res, 400, { reason: 'Unsupported grant type', error: 'unsupported_grant_type' })
    })
    return
  }
  zmError(res, 404, 404, 'Not Found')
}

function zoom(req, res, url, rest) {
  if (rest.startsWith('oauth/')) return zoomOAuth(req, res, url, rest.slice('oauth/'.length))
  if (req.method !== 'GET') return json(res, 405, { message: 'Read only' })
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) return zmError(res, 401, 124, 'Invalid access token.')
  const base = `http://${req.headers.host}${ZM_PREFIX}`
  let m
  if ((m = rest.match(/^rec\/download\/([\w-]+)$/))) {
    for (const meeting of zmMeetings) {
      for (const [type, text] of meeting.files ?? []) {
        if (zmFileId(meeting, type) === m[1] && text) {
          res.writeHead(200, { 'Content-Type': 'text/vtt; charset=utf-8' })
          return res.end(text)
        }
      }
    }
    return zmError(res, 404, 3301, 'This recording does not exist.')
  }
  if (!rest.startsWith('v2/')) return zmError(res, 404, 404, 'Not Found')
  const path = rest.slice(3)
  if (path === 'users') {
    return zmPage(res, url, 'users', zmPeople.filter(p => (url.searchParams.get('status') ?? 'active') === p.status))
  }
  if ((m = path.match(/^users\/([^/]+)$/))) {
    const person = zmPerson(decodeURIComponent(m[1]))
    return person ? json(res, 200, person) : zmError(res, 404, 1001, 'User does not exist.')
  }
  if ((m = path.match(/^users\/([^/]+)\/(recordings|meeting_summaries)$/))) {
    const person = zmPerson(decodeURIComponent(m[1]))
    if (!person) return zmError(res, 404, 1001, 'User does not exist.')
    const recordings = m[2] === 'recordings'
    const from = (url.searchParams.get('from') ?? '').slice(0, 10)
    const to = (url.searchParams.get('to') ?? '').slice(0, 10)
    if (recordings && from && to && Date.parse(to) - Date.parse(from) > 31 * G_DAY) {
      return zmError(res, 400, 300, 'The date range can only be one month.')
    }
    const inRange = x => (!from || zmDay(x.start_time) >= from) && (!to || zmDay(x.start_time) <= to)
    const mine = zmMeetings.filter(x => x.host_id === person.id && inRange(x))
    if (recordings) {
      return zmPage(res, url, 'meetings', mine.filter(x => x.files).map(x => zmRecording(x, base)), { from, to })
    }
    return zmPage(res, url, 'summaries', mine.filter(x => x.summary).map(zmSummary), { from, to })
  }
  if ((m = path.match(/^meetings\/([^/]+)\/(recordings|meeting_summary)$/))) {
    const meeting = zmUuid(m[1])
    if (m[2] === 'recordings') {
      return meeting?.files ? json(res, 200, zmRecording(meeting, base)) : zmError(res, 404, 3301, 'This recording does not exist.')
    }
    return meeting?.summary ? json(res, 200, zmSummary(meeting)) : zmError(res, 404, 3322, 'Meeting summary does not exist.')
  }
  if ((m = path.match(/^past_meetings\/([^/]+)\/participants$/))) {
    const meeting = zmUuid(m[1])
    if (!meeting) return zmError(res, 404, 3001, 'Meeting does not exist.')
    const people = (meeting.participants ?? []).map((name, i) => ({ id: `p-${i}`, name, user_email: '', duration: 600 }))
    return zmPage(res, url, 'participants', people)
  }
  zmError(res, 404, 404, 'Not Found')
}

// -- Microsoft Graph: OneDrive, SharePoint, Teams, Teams chats, Outlook --------------------------

// One tenant's worth of Microsoft 365, as one signed-in user sees it through Graph v1.0. Point the
// app at it with -Dproxyma.connectors.microsoft.graph-url=http://127.0.0.1:8798/graph/v1.0 (the
// sessions and the Outlook pickers read that property), and set the deployment's Microsoft 365
// app registration (Settings > Network > Microsoft 365) to any tenant id, client id and secret,
// with the authority host http://127.0.0.1:8798/graph/login - its sign-in then agrees at once.
//
// Codewords: OneDrive PERSIMMON (Documents/Reports/Q3 summary.txt), KUMQUAT (Documents/Notes.md),
// FEIJOA (Budget.csv); SharePoint site Eng at https://fake.sharepoint.example/sites/Eng - LOQUAT
// (its page), PITAYA (a library file); Teams channel Engineering/General - RAMBUTAN (a message),
// JUJUBE (its reply); Teams chats - LONGAN; Outlook Inbox - SOURSOP; Calendar - MANGOSTEEN.
//
// Every delta feed has one current token: a request without one enumerates, a request with the
// current one has nothing new, any other token has expired (410 resyncRequired, with a Location to
// start again from), as Graph answers a link it no longer honors.
const MS_DAY = 86_400_000
const MS_TODAY = Date.parse(new Date().toISOString().slice(0, 10) + 'T00:00:00Z')
const msAgo = days => new Date(MS_TODAY - days * MS_DAY).toISOString()
const MS_USER = { id: 'user-fake-1', displayName: 'Fake User', userPrincipalName: 'fake.user@contoso.example', mail: 'fake.user@contoso.example' }
const MS_TOKEN = 'DCURRENT'

// OneDrive: one drive, a few folders, files that are text so any build can read them.
const OD_DRIVE = 'b!fakeOneDrive01'
const odItems = [
  { id: 'od-root', name: 'root', root: true, folder: true },
  { id: 'od-docs', name: 'Documents', parent: 'od-root', folder: true },
  { id: 'od-reports', name: 'Reports', parent: 'od-docs', folder: true },
  { id: 'od-q3', name: 'Q3 summary.txt', parent: 'od-reports', days: 2,
    body: 'Third quarter summary. Revenue grew in every region. The codeword for the summary is PERSIMMON.\n' },
  { id: 'od-notes', name: 'Notes.md', parent: 'od-docs', days: 5,
    body: '# Notes\n\nCall the printer vendor on Monday. The codeword for the notes is KUMQUAT.\n' },
  { id: 'od-budget', name: 'Budget.csv', parent: 'od-root', days: 9, body: 'item,cost\nChairs,1200\nCodeword,FEIJOA\n' },
  { id: 'od-lock', name: '~$Draft.docx', parent: 'od-docs', days: 1, body: 'lock' },
  { id: 'od-notebook', name: 'Team notebook', parent: 'od-docs', package: true },
]

// SharePoint: one site with one page and one library.
const SP_HOST = 'fake.sharepoint.example'
const SP_SITE = `${SP_HOST},site-eng-1,web-eng-1`
const SP_DRIVE = 'b!fakeSpLibrary01'
const spItems = [
  { id: 'sp-root', name: 'root', root: true, folder: true },
  { id: 'sp-spec', name: 'Release checklist.txt', parent: 'sp-root', days: 3,
    body: 'Before a release: tag, build, smoke test. The codeword for the checklist is PITAYA.\n' },
]
const spPages = [{ id: 'page-handbook', name: 'Handbook.aspx', title: 'Engineering handbook', days: 4,
  html: '<p>How we build things.</p><p>The codeword for the handbook is LOQUAT.</p>' }]

// Teams: one team, one channel, a thread with a reply; one chat.
const TEAM = { id: 'team-eng-1', displayName: 'Engineering' }
const CHANNEL = { id: '19:general-eng@thread.tacv2', displayName: 'General' }
const teamsMessages = [
  { id: '1760000000001', subject: 'Release train', days: 3, from: 'Bao Nguyen',
    html: '<p>The release train leaves on Thursday. The codeword for the release is RAMBUTAN.</p>',
    replies: [{ id: '1760000000002', from: 'Chi Tran', days: 2, text: 'Ready on our side. The codeword for the reply is JUJUBE.' }] },
]
const CHAT = { id: '19:chat-minh@unq.gbl.spaces', topic: null, members: ['Minh Pham', 'Fake User'] }
const chatMessages = [
  { id: '1760000000101', from: 'Minh Pham', days: 1, text: 'Can we meet about the budget? The codeword for the chat is LONGAN.' },
  { id: '1760000000102', from: 'Fake User', days: 1, text: 'Thursday works.' },
]

// Outlook: the Inbox and one calendar.
const MAIL_FOLDER = { id: 'AAMkInbox=', displayName: 'Inbox' }
const mailMessages = [
  { id: 'AAMkMsg1=', subject: 'Contract renewal', from: { name: 'Carol Ng', address: 'carol@client.example' }, days: 6,
    html: '<p>The renewal terms are agreed. The codeword for the renewal is SOURSOP.</p>' },
]
const CALENDAR = { id: 'AAMkCal1=', name: 'Calendar' }
const calendarEvents = [
  { id: 'AAMkEvt1=', subject: 'Design review', days: -3,
    text: 'Walk through the new sources page. The codeword for the review is MANGOSTEEN.' },
]

const msBase = url => `${url.origin}/graph/v1.0`
const msError = (res, status, code, message, headers = {}) => json(res, status, { error: { code, message } }, headers)

function msPage(res, url, all, extra = {}) {
  const start = Math.max(0, Number(url.searchParams.get('$skiptoken') ?? '0') || 0)
  const page = { value: all.slice(start, start + PAGE_SIZE) }
  if (start + PAGE_SIZE < all.length) {
    const next = new URL(url)
    next.searchParams.set('$skiptoken', String(start + PAGE_SIZE))
    page['@odata.nextLink'] = next.toString()
  } else Object.assign(page, extra)
  return json(res, 200, page)
}

function driveItemView(url, driveId, items, item) {
  const view = { id: item.id, name: item.name, lastModifiedDateTime: msAgo(item.days ?? 30),
    webUrl: `https://fake-my.sharepoint.example/personal/${encodeURIComponent(item.name)}`,
    lastModifiedBy: { user: { displayName: MS_USER.displayName } } }
  if (item.root) view.root = {}
  if (item.folder) view.folder = { childCount: items.filter(i => i.parent === item.id).length }
  if (item.package) { view.package = { type: 'oneNote' }; view.file = {} }
  if (item.body !== undefined) {
    view.file = { mimeType: 'text/plain', hashes: { quickXorHash: Buffer.from(item.id).toString('base64') } }
    view.size = Buffer.byteLength(item.body)
  }
  if (item.parent) {
    const names = []
    for (let p = items.find(i => i.id === item.parent); p && !p.root; p = items.find(i => i.id === p.parent)) names.unshift(p.name)
    view.parentReference = { driveId, id: item.parent, path: '/drive/root:' + names.map(n => '/' + encodeURIComponent(n)).join('') }
  }
  return view
}

function driveDelta(res, url, driveId, items) {
  const token = url.searchParams.get('token')
  const deltaLink = `${msBase(url)}/drives/${driveId}/root/delta?token=${MS_TOKEN}`
  if (token === 'latest' || token === MS_TOKEN) return json(res, 200, { value: [], '@odata.deltaLink': deltaLink })
  if (token) {
    return msError(res, 410, 'resyncRequired', 'Resync required. Replace any local items with the server\'s version.',
      { Location: `${msBase(url)}/drives/${driveId}/root/delta` })
  }
  // A delta entry carries no path, as Graph's do not.
  const entries = items.map(i => {
    const v = driveItemView(url, driveId, items, i)
    if (v.parentReference) delete v.parentReference.path
    return v
  })
  return msPage(res, url, entries, { '@odata.deltaLink': deltaLink })
}

// True when the request was this drive's, and answered.
function driveRoutes(req, res, url, rest, driveId, items) {
  if (rest === `drives/${driveId}/root/delta`) {
    driveDelta(res, url, driveId, items)
    return true
  }
  const prefix = `drives/${driveId}/items/`
  if (!rest.startsWith(prefix)) return false
  const m = rest.slice(prefix.length).match(/^([^/]+)(\/children|\/content)?$/)
  if (!m) return false
  answerDriveItem(res, url, driveId, items, m)
  return true
}

function answerDriveItem(res, url, driveId, items, m) {
  const item = items.find(i => i.id === decodeURIComponent(m[1]))
  if (!item) return msError(res, 404, 'itemNotFound', 'The resource could not be found.')
  if (m[2] === '/children') {
    return msPage(res, url, items.filter(i => i.parent === item.id).map(i => driveItemView(url, driveId, items, i)))
  }
  if (m[2] === '/content') {
    if (item.body === undefined) return msError(res, 404, 'itemNotFound', 'The item has no content.')
    // A pre-authenticated download address on another path, which the app fetches without its token.
    res.writeHead(302, { Location: `${url.origin}/graph/download/${driveId}/${item.id}?tempauth=fake` })
    return res.end()
  }
  return json(res, 200, driveItemView(url, driveId, items, item))
}

function teamsMessageView(m) {
  return { id: m.id, messageType: 'message', subject: m.subject ?? null, createdDateTime: msAgo(m.days),
    lastModifiedDateTime: msAgo(m.days), deletedDateTime: null, webUrl: `https://teams.example/l/message/${m.id}`,
    from: { user: { displayName: m.from } },
    body: m.html ? { contentType: 'html', content: m.html } : { contentType: 'text', content: m.text } }
}

// "lastModifiedDateTime gt X" and "... lt Y", the filters the chat and channel requests send.
function msTimeFilter(url) {
  const filter = url.searchParams.get('$filter') ?? ''
  const gt = filter.match(/lastModifiedDateTime gt (\S+)/)
  const lt = filter.match(/lastModifiedDateTime lt (\S+)/)
  return m => (!gt || Date.parse(m.lastModifiedDateTime) > Date.parse(gt[1])) && (!lt || Date.parse(m.lastModifiedDateTime) < Date.parse(lt[1]))
}

function msLogin(req, res, url, path) {
  const m = path.match(/^([^/]+)\/oauth2\/v2\.0\/(authorize|token)$/)
  if (!m) return msError(res, 404, 'NotFound', 'Not Found')
  if (m[2] === 'authorize') {
    // Agrees at once: back to the app with a code that carries the tenant and the nonce, so the
    // ID token below answers the request it was asked for.
    let back
    try {
      back = new URL(url.searchParams.get('redirect_uri') ?? '')
    } catch {
      return json(res, 400, { error: 'invalid_request' })
    }
    const code = Buffer.from(JSON.stringify({ tenant: m[1], nonce: url.searchParams.get('nonce') ?? '' })).toString('base64url')
    back.searchParams.set('code', code)
    back.searchParams.set('state', url.searchParams.get('state') ?? '')
    res.writeHead(302, { Location: back.toString() })
    return res.end()
  }
  if (req.method !== 'POST') return json(res, 405, { error: 'invalid_request' })
  let body = ''
  req.on('data', chunk => { body += chunk })
  req.on('end', () => {
    const form = new URLSearchParams(body)
    const grant = form.get('grant_type')
    if (grant !== 'authorization_code' && grant !== 'refresh_token') return json(res, 400, { error: 'unsupported_grant_type' })
    let carried = { tenant: m[1], nonce: '' }
    if (grant === 'authorization_code') {
      try { carried = JSON.parse(Buffer.from(form.get('code') ?? '', 'base64url').toString('utf8')) } catch { /* the defaults */ }
    }
    const claims = { tid: carried.tenant, oid: MS_USER.id, preferred_username: MS_USER.userPrincipalName,
      name: MS_USER.displayName, nonce: carried.nonce }
    const idToken = [{ alg: 'none', typ: 'JWT' }, claims].map(p => Buffer.from(JSON.stringify(p)).toString('base64url')).join('.') + '.'
    json(res, 200, { token_type: 'Bearer', expires_in: 3600, access_token: 'fake-graph-access-token',
      refresh_token: 'fake-graph-refresh-token', scope: form.get('scope') ?? '', id_token: idToken })
  })
}

function graph(req, res, url, rest) {
  if (rest.startsWith('login/')) return msLogin(req, res, url, rest.slice('login/'.length))
  if (req.method !== 'GET') return json(res, 405, { message: 'Read only' })
  let m = rest.match(/^download\/([^/]+)\/([^/]+)$/)
  if (m) {
    // Pre-authenticated, as Graph's own: no token needed. (The app sends none to a download host on
    // another address; this one is on the fake's own address, so a token that comes along is ignored.)
    const item = [...odItems, ...spItems].find(i => i.id === decodeURIComponent(m[2]))
    if (!item || item.body === undefined) return msError(res, 404, 'itemNotFound', 'Not found')
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
    return res.end(item.body)
  }
  if (!rest.startsWith('v1.0/')) return msError(res, 404, 'NotFound', 'Not Found')
  if (!/^Bearer \S+/.test(req.headers.authorization ?? '')) {
    return msError(res, 401, 'InvalidAuthenticationToken', 'Access token is empty.')
  }
  const path = rest.slice('v1.0/'.length)
  const q = url.searchParams

  // OneDrive
  if (path === 'me') return json(res, 200, MS_USER)
  if (path === 'me/drive') return json(res, 200, { id: OD_DRIVE, driveType: 'business', webUrl: 'https://fake-my.sharepoint.example/personal' })
  m = path.match(/^me\/drive\/root:\/(.+?):?$/)
  if (m) {
    const names = m[1].split('/').map(decodeURIComponent).filter(Boolean)
    let item = odItems.find(i => i.root)
    for (const name of names) item = item && odItems.find(i => i.parent === item.id && i.name.toLowerCase() === name.toLowerCase())
    return item ? json(res, 200, driveItemView(url, OD_DRIVE, odItems, item)) : msError(res, 404, 'itemNotFound', 'Item not found')
  }
  if (driveRoutes(req, res, url, path, OD_DRIVE, odItems) || driveRoutes(req, res, url, path, SP_DRIVE, spItems)) return

  // SharePoint
  m = path.match(/^sites\/([^/:]+):\/(.+)$/)
  if (m) {
    const site = decodeURIComponent(m[2]).replace(/\/+$/, '')
    if (m[1] !== SP_HOST || site.toLowerCase() !== 'sites/eng') return msError(res, 404, 'itemNotFound', 'Requested site could not be found')
    return json(res, 200, { id: SP_SITE, displayName: 'Engineering', webUrl: `https://${SP_HOST}/sites/Eng` })
  }
  m = path.match(/^sites\/([^/]+)\/(drives|pages\/microsoft\.graph\.sitePage|pages\/([^/]+)\/microsoft\.graph\.sitePage)$/)
  if (m) {
    if (decodeURIComponent(m[1]) !== SP_SITE) return msError(res, 404, 'itemNotFound', 'Requested site could not be found')
    if (m[2] === 'drives') return msPage(res, url, [{ id: SP_DRIVE, name: 'Documents', driveType: 'documentLibrary' }])
    const pageView = p => ({ id: p.id, name: p.name, title: p.title, lastModifiedDateTime: msAgo(p.days),
      webUrl: `https://${SP_HOST}/sites/Eng/SitePages/${p.name}` })
    if (!m[3]) return msPage(res, url, spPages.map(pageView))
    const page = spPages.find(p => p.id === decodeURIComponent(m[3]))
    if (!page) return msError(res, 404, 'itemNotFound', 'Page not found')
    return json(res, 200, { ...pageView(page), description: 'How the engineering team works.',
      canvasLayout: { horizontalSections: [{ columns: [{ webparts: [{ innerHtml: page.html }] }] }] } })
  }

  // Teams channels
  if (path === 'me/joinedTeams') return msPage(res, url, [TEAM])
  if (path === `teams/${TEAM.id}/channels`) return msPage(res, url, [CHANNEL])
  m = path.match(/^teams\/([^/]+)\/channels\/([^/]+)\/messages(\/delta|\/([^/]+)(\/replies)?)?$/)
  if (m) {
    if (decodeURIComponent(m[1]) !== TEAM.id || decodeURIComponent(m[2]) !== CHANNEL.id) {
      return msError(res, 404, 'NotFound', 'Channel not found')
    }
    const base = `${msBase(url)}/teams/${TEAM.id}/channels/${encodeURIComponent(CHANNEL.id)}/messages`
    const all = teamsMessages.map(teamsMessageView)
    if (m[3] === '/delta') {
      const deltaLink = `${base}/delta?$deltatoken=${MS_TOKEN}`
      const token = q.get('$deltatoken')
      if (token === MS_TOKEN) return json(res, 200, { value: [], '@odata.deltaLink': deltaLink })
      if (token) return msError(res, 410, 'resyncRequired', 'The delta token has expired.')
      return msPage(res, url, all.filter(msTimeFilter(url)), { '@odata.deltaLink': deltaLink })
    }
    if (!m[4]) return msPage(res, url, all)
    const message = teamsMessages.find(x => x.id === decodeURIComponent(m[4]))
    if (!message) return msError(res, 404, 'NotFound', 'Message not found')
    if (!m[5]) return json(res, 200, teamsMessageView(message))
    return msPage(res, url, message.replies.map(r => teamsMessageView({ ...r })))
  }

  // Teams chats
  if (path === 'me/chats') {
    return msPage(res, url, [{ id: CHAT.id, topic: CHAT.topic, chatType: 'oneOnOne', lastUpdatedDateTime: msAgo(1),
      webUrl: `https://teams.example/l/chat/${encodeURIComponent(CHAT.id)}`,
      members: CHAT.members.map(displayName => ({ displayName })) }])
  }
  m = path.match(/^me\/chats\/([^/]+)\/messages$/)
  if (m) {
    if (decodeURIComponent(m[1]) !== CHAT.id) return msError(res, 404, 'NotFound', 'Chat not found')
    const all = chatMessages.map(teamsMessageView).filter(msTimeFilter(url))
      .sort((a, b) => Date.parse(b.lastModifiedDateTime) - Date.parse(a.lastModifiedDateTime))
    return msPage(res, url, all)
  }

  // Outlook mail
  if (path === 'me/mailFolders') return msPage(res, url, [{ ...MAIL_FOLDER, childFolderCount: 0, totalItemCount: mailMessages.length }])
  m = path.match(/^me\/mailFolders\/([^/]+)(\/childFolders|\/messages(\/delta)?)?$/)
  if (m) {
    if (decodeURIComponent(m[1]) !== MAIL_FOLDER.id) return msError(res, 404, 'ErrorItemNotFound', 'The specified folder could not be found.')
    if (!m[2]) return json(res, 200, MAIL_FOLDER)
    if (m[2] === '/childFolders') return msPage(res, url, [])
    const view = x => ({ id: x.id, subject: x.subject, receivedDateTime: msAgo(x.days), lastModifiedDateTime: msAgo(x.days),
      webLink: `https://outlook.example/?ItemID=${encodeURIComponent(x.id)}`, attachments: [] })
    if (!m[3]) return msPage(res, url, mailMessages.map(view))
    const deltaLink = `${msBase(url)}/me/mailFolders/${encodeURIComponent(MAIL_FOLDER.id)}/messages/delta?$deltatoken=${MS_TOKEN}`
    const token = q.get('$deltatoken')
    if (token === MS_TOKEN) return json(res, 200, { value: [], '@odata.deltaLink': deltaLink })
    if (token) return msError(res, 410, 'SyncStateNotFound', 'The sync state could not be found.')
    return msPage(res, url, mailMessages.map(view), { '@odata.deltaLink': deltaLink })
  }
  m = path.match(/^me\/messages\/([^/]+)$/)
  if (m) {
    const x = mailMessages.find(y => y.id === decodeURIComponent(m[1]))
    if (!x) return msError(res, 404, 'ErrorItemNotFound', 'The specified object was not found in the store.')
    return json(res, 200, { subject: x.subject, from: { emailAddress: x.from }, toRecipients: [{ emailAddress: { name: MS_USER.displayName, address: MS_USER.mail } }],
      receivedDateTime: msAgo(x.days), webLink: `https://outlook.example/?ItemID=${encodeURIComponent(x.id)}`,
      body: { contentType: 'html', content: x.html }, attachments: [] })
  }

  // Outlook calendar
  if (path === 'me/calendars') return msPage(res, url, [{ ...CALENDAR, owner: { address: MS_USER.mail } }])
  m = path.match(/^me\/calendars\/([^/]+)(\/events|\/calendarView)?$/)
  if (m) {
    if (decodeURIComponent(m[1]) !== CALENDAR.id) return msError(res, 404, 'ErrorItemNotFound', 'The specified calendar could not be found.')
    if (!m[2]) return json(res, 200, CALENDAR)
    return msPage(res, url, calendarEvents.map(eventView))
  }
  m = path.match(/^me\/events\/([^/]+)(\/instances)?$/)
  if (m) {
    const e = calendarEvents.find(x => x.id === decodeURIComponent(m[1]))
    if (!e) return msError(res, 404, 'ErrorItemNotFound', 'The specified object was not found in the store.')
    return m[2] ? msPage(res, url, []) : json(res, 200, eventView(e))
  }
  return msError(res, 404, 'NotFound', `No fake Graph resource at /${path}`)
}

function eventView(e) {
  const start = new Date(MS_TODAY - e.days * MS_DAY + 9 * 3_600_000).toISOString().replace('Z', '0000')
  const end = new Date(MS_TODAY - e.days * MS_DAY + 10 * 3_600_000).toISOString().replace('Z', '0000')
  return { id: e.id, type: 'singleInstance', subject: e.subject, isAllDay: false, isCancelled: false,
    originalStartTimeZone: 'UTC', lastModifiedDateTime: msAgo(10), webLink: `https://outlook.example/calendar/item/${encodeURIComponent(e.id)}`,
    start: { dateTime: start, timeZone: 'UTC' }, end: { dateTime: end, timeZone: 'UTC' },
    organizer: { emailAddress: { name: MS_USER.displayName, address: MS_USER.mail } },
    body: { contentType: 'text', content: e.text } }
}

// -- the server --------------------------------------------------------------------------------

// Jira Cloud's search and Confluence Cloud's people lookup are POSTs with the question in a JSON
// body; these handlers take that body parsed, where the others read their own.
function withJsonBody(handler) {
  return (req, res, url, rest) => {
    const chunks = []
    req.on('data', c => chunks.push(c))
    req.on('end', () => {
      let body = null
      if (chunks.length) {
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch { return json(res, 400, { message: 'Not JSON' }) }
      }
      try {
        handler(req, res, url, rest, body)
      } catch (e) {
        json(res, 500, { message: String(e?.message ?? e) })
      }
    })
  }
}

const SERVICES = {
  github, slack, gitlab, linear, google,
  jira: withJsonBody(jira), confluence: withJsonBody(confluence), bitbucket, notion, airtable, zendesk, freshdesk,
  // One Atlassian Cloud site: Confluence under /wiki, Jira at the root - as SITE.atlassian.net is.
  atlassian: withJsonBody((req, res, url, rest, body) => (rest.startsWith('wiki/') ? confluence : jira)(req, res, url, rest, body)),
  hubspot, salesforce,
  asana, monday: withJsonBody(monday), trello, clickup,
  dropbox: withRawBody(dropbox), box: withRawBody(box),
  zoom,
  graph,
}
// What a service answers besides GET. Every service is still read only.
const METHODS = {
  google: ['GET', 'POST'], gitlab: ['GET', 'HEAD'], linear: ['POST'],
  jira: ['GET', 'POST'], confluence: ['GET', 'POST'], atlassian: ['GET', 'POST'], notion: ['GET', 'POST'], zendesk: ['GET', 'POST'],
  hubspot: ['GET', 'POST'], salesforce: ['GET', 'POST'],
  monday: ['POST'],
  dropbox: ['GET', 'POST'], box: ['GET', 'POST'],
  zoom: ['GET', 'POST'],
  graph: ['GET', 'POST'],
}

function json(res, status, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers })
  res.end(JSON.stringify(body))
}

function notFound(res) {
  json(res, 404, { message: 'Not Found', documentation_url: 'https://docs.github.com/rest' })
}

const server = createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host ?? `127.0.0.1:${PORT}`}`)
  if (LOG) process.stderr.write(`${req.method} ${url.pathname}${url.search}\n`)

  if (url.pathname === '/_control/health') return json(res, 200, { ok: true })
  if (url.pathname === '/_control/requests') {
    if (req.method === 'DELETE') { requests.length = 0; return json(res, 200, { ok: true }) }
    return json(res, 200, requests)
  }

  requests.push({ method: req.method, path: url.pathname, query: url.search, at: new Date().toISOString() })
  const [, service, ...rest] = url.pathname.split('/')
  const handler = SERVICES[service]
  if (!handler) return json(res, 404, { message: `No fake service called "${service}"` })
  if (!(METHODS[service] ?? ['GET']).includes(req.method)) return json(res, 405, { message: 'Read only' })
  const failed = e => json(res, 500, { message: String(e?.message ?? e) })
  try {
    Promise.resolve(handler(req, res, url, rest.join('/'))).catch(failed)
  } catch (e) {
    failed(e)
  }
})

server.listen(PORT, '127.0.0.1', () => {
  // The format is a contract, as fake-llm's is: `listening <port>`.
  process.stdout.write(`listening ${server.address().port}\n`)
})
