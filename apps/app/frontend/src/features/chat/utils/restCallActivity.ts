import type { Translate } from '@/i18n'

// What a REST PASSTHROUGH call is actually doing - callJiraApi, callConfluenceApi and
// callBitbucketApi, the escape hatches the model reaches for when no dedicated tool covers an
// endpoint. Their names say nothing ("Call Bitbucket api"), and derive() in toolActivity.ts could
// only add the one argument left after path redaction, so the thread read "Call Bitbucket api -
// GET" - the maintainer's report. The method and the REST path together say exactly what the call
// does, so this reads them: GET .../repos/web/pull-requests is "Listing pull requests - web".
//
// The REST path is NOT a filesystem path. redactFilePaths drops every *path argument to keep
// server paths out of the thread, and this deliberately reads `path` before that: it is a URL
// path on the Atlassian server, never a location on this machine. What it quotes back is only
// ever an identifier out of it - an issue key, a page id, a repository slug, a file NAME.
//
// Plain .ts with no JSX and only a TYPE import (which type stripping erases, alias and all), so
// `node --test` reaches it - restCallActivity.test.ts.
//
// TRANSLATION. Rule phrases are finite English and are catalog keys, checked through the RULES
// entry in check-catalogs.mjs. The fallback names a resource taken from the path at runtime, so -
// like derive() - its verb template is translated and the resource word stays as the API spells it.

export type RestActivity = {
  running: string
  done: string
  // The detail reads as the phrase's direct object ("Opening pull request #12") rather than an aside.
  takesObject: boolean
  detail?: string
}

// Method classes. Named so the rule table below carries no quoted strings but its prose - the
// catalog checker reads every quoted literal in that block as English to translate.
const GET = /^GET$/
const POST = /^POST$/
const PUT = /^(PUT|PATCH)$/
const DELETE = /^DELETE$/
const WRITE = /^(POST|PUT|PATCH|DELETE)$/

type Detail = (m: RegExpMatchArray) => string | undefined
// [method, path pattern after `rest/<family>/<version>/` with the family in front, running,
// done, detail, detail-is-object]
type Rule = readonly [RegExp, string, string, string, Detail?, true?]

// A malformed percent sequence (`100%.md`) makes decodeURIComponent throw, and this runs during
// render - so it reads back as written instead.
const decode = (s: string): string => { try { return decodeURIComponent(s) } catch { return s } }
const g = (i: number): Detail => m => m[i] ? decode(m[i]) : undefined
const pr = (i: number): Detail => m => m[i] ? `#${m[i]}` : undefined
const sha = (i: number): Detail => m => m[i]?.slice(0, 8)
// The file NAME only - a repository path can be long, and the name is what a reader recognizes.
const fileName = (i: number): Detail => m => m[i] ? decode(m[i].split('/').filter(Boolean).pop() ?? '') || undefined : undefined
const O = true

// A Bitbucket repository: group 1 is the slug.
const R = String.raw`api/projects/[^/]+/repos/([^/]+)`
const PRN = String.raw`${R}/pull-requests/(\d+)`
const K = String.raw`api/issue/([A-Za-z][A-Za-z0-9_]*-\d+|\d+)`
const C = String.raw`api/content/(\d+)`
const SHA = String.raw`([0-9a-fA-F]{7,40})`

// Most specific first; the first match wins.
const RULES: Record<string, readonly Rule[]> = {
  callBitbucketApi: [
    [GET, `${PRN}/activities`, 'Reading the activity on pull request', 'Read the activity on pull request', pr(2), O],
    [GET, `${PRN}/comments(?:/\\d+)?`, 'Reading comments on pull request', 'Read comments on pull request', pr(2), O],
    [POST, `${PRN}/comments`, 'Commenting on pull request', 'Commented on pull request', pr(2), O],
    [PUT, `${PRN}/comments/\\d+`, 'Editing a comment on pull request', 'Edited a comment on pull request', pr(2), O],
    [DELETE, `${PRN}/comments/\\d+`, 'Deleting a comment on pull request', 'Deleted a comment on pull request', pr(2), O],
    [GET, `${PRN}/commits`, 'Listing the commits of pull request', 'Listed the commits of pull request', pr(2), O],
    [GET, `${PRN}/changes`, 'Listing the files changed in pull request', 'Listed the files changed in pull request', pr(2), O],
    [GET, `${PRN}/diff(?:/.*)?`, 'Reading the diff of', 'Read the diff of', pr(2), O],
    [GET, `${PRN}/merge`, 'Checking the merge status of pull request', 'Checked the merge status of pull request', pr(2), O],
    [POST, `${PRN}/merge`, 'Merging pull request', 'Merged pull request', pr(2), O],
    [POST, `${PRN}/decline`, 'Declining pull request', 'Declined pull request', pr(2), O],
    [POST, `${PRN}/reopen`, 'Reopening pull request', 'Reopened pull request', pr(2), O],
    [POST, `${PRN}/approve`, 'Approving pull request', 'Approved pull request', pr(2), O],
    [DELETE, `${PRN}/approve`, 'Withdrawing approval of pull request', 'Withdrew approval of pull request', pr(2), O],
    [GET, `${PRN}/participants(?:/.*)?`, 'Reading the reviewers of pull request', 'Read the reviewers of pull request', pr(2), O],
    [WRITE, `${PRN}/participants(?:/.*)?`, 'Updating the reviewers of pull request', 'Updated the reviewers of pull request', pr(2), O],
    [GET, PRN, 'Opening pull request', 'Opened pull request', pr(2), O],
    [PUT, PRN, 'Editing pull request', 'Edited pull request', pr(2), O],
    [DELETE, PRN, 'Deleting pull request', 'Deleted pull request', pr(2), O],
    [GET, `${R}/pull-requests`, 'Listing pull requests', 'Listed pull requests', g(1)],
    [POST, `${R}/pull-requests`, 'Opening a pull request', 'Opened a pull request', g(1)],
    [GET, `${R}/commits/${SHA}/(?:diff|changes)(?:/.*)?`, 'Reading the commit diff', 'Read the commit diff', sha(2)],
    [GET, `${R}/commits/${SHA}`, 'Opening commit', 'Opened commit', sha(2), O],
    [GET, `${R}/commits`, 'Reading commit history', 'Read commit history', g(1)],
    [GET, `${R}/compare/.*`, 'Comparing branches', 'Compared branches', g(1)],
    [GET, `${R}/branches(?:/default)?`, 'Listing branches', 'Listed branches', g(1)],
    [GET, `${R}/tags`, 'Listing tags', 'Listed tags', g(1)],
    [POST, `${R}/tags`, 'Creating a tag', 'Created a tag', g(1)],
    [GET, `${R}/(?:browse|raw)/(.+)`, 'Reading file', 'Read file', fileName(2)],
    [GET, `${R}/browse`, 'Browsing repository', 'Browsed repository', g(1)],
    [GET, `${R}/files(?:/.*)?`, 'Listing files', 'Listed files', g(1)],
    [GET, `${R}/settings/hooks(?:/.*)?`, 'Reading repository hooks', 'Read repository hooks', g(1)],
    [WRITE, `${R}/settings/hooks(?:/.*)?`, 'Changing repository hooks', 'Changed repository hooks', g(1)],
    [GET, `${R}/settings(?:/.*)?`, 'Reading repository settings', 'Read repository settings', g(1)],
    [WRITE, `${R}/settings(?:/.*)?`, 'Changing repository settings', 'Changed repository settings', g(1)],
    [GET, `${R}/permissions(?:/.*)?`, 'Reading repository permissions', 'Read repository permissions', g(1)],
    [WRITE, `${R}/permissions(?:/.*)?`, 'Changing repository permissions', 'Changed repository permissions', g(1)],
    [GET, `${R}/forks`, 'Listing forks', 'Listed forks', g(1)],
    [GET, R, 'Opening repository', 'Opened repository', g(1), O],
    [GET, String.raw`api/projects/([^/]+)/repos`, 'Listing repositories in', 'Listed repositories in', g(1), O],
    [GET, String.raw`api/projects/([^/]+)`, 'Opening Bitbucket project', 'Opened Bitbucket project', g(1), O],
    [GET, String.raw`api/projects`, 'Listing Bitbucket projects', 'Listed Bitbucket projects'],
    [GET, String.raw`api/repos`, 'Searching repositories', 'Searched repositories'],
    [GET, String.raw`api/(?:dashboard|inbox)/pull-requests(?:/count)?`, 'Listing your pull requests', 'Listed your pull requests'],
    [GET, String.raw`api/users(?:/.*)?`, 'Looking up Bitbucket users', 'Looked up Bitbucket users'],
    [GET, String.raw`build-status/commits/${SHA}(?:/.*)?`, 'Checking the build status of commit', 'Checked the build status of commit', sha(1), O],
  ],
  callJiraApi: [
    [GET, `${K}/watchers`, 'Reading the watchers of', 'Read the watchers of', g(1), O],
    [POST, `${K}/watchers`, 'Adding a watcher to', 'Added a watcher to', g(1), O],
    [DELETE, `${K}/watchers`, 'Removing a watcher from', 'Removed a watcher from', g(1), O],
    [GET, `${K}/remotelink(?:/.*)?`, 'Reading the links on', 'Read the links on', g(1), O],
    [DELETE, `${K}/remotelink(?:/.*)?`, 'Removing a link from', 'Removed a link from', g(1), O],
    [WRITE, `${K}/remotelink(?:/.*)?`, 'Adding a link to', 'Added a link to', g(1), O],
    [POST, `${K}/attachments`, 'Attaching a file to', 'Attached a file to', g(1), O],
    [GET, `${K}/comment(?:/\\d+)?`, 'Reading comments on', 'Read comments on', g(1), O],
    [POST, `${K}/comment`, 'Commenting on', 'Commented on', g(1), O],
    [PUT, `${K}/comment/\\d+`, 'Editing a comment on', 'Edited a comment on', g(1), O],
    [DELETE, `${K}/comment/\\d+`, 'Deleting a comment on', 'Deleted a comment on', g(1), O],
    [GET, `${K}/worklog(?:/.*)?`, 'Reading the work log of', 'Read the work log of', g(1), O],
    [POST, `${K}/worklog`, 'Logging work on', 'Logged work on', g(1), O],
    [GET, `${K}/transitions`, 'Checking the transitions of', 'Checked the transitions of', g(1), O],
    [POST, `${K}/transitions`, 'Moving', 'Moved', g(1), O],
    [GET, `${K}/changelog`, 'Reading the history of', 'Read the history of', g(1), O],
    [POST, `${K}/votes`, 'Voting for', 'Voted for', g(1), O],
    [DELETE, `${K}/votes`, 'Removing a vote from', 'Removed a vote from', g(1), O],
    [PUT, `${K}/assignee`, 'Assigning', 'Assigned', g(1), O],
    [GET, `${K}/editmeta`, 'Reading the editable fields of', 'Read the editable fields of', g(1), O],
    [GET, K, 'Opening', 'Opened', g(1), O],
    [PUT, K, 'Updating', 'Updated', g(1), O],
    [DELETE, K, 'Deleting', 'Deleted', g(1), O],
    [POST, String.raw`api/issue(?:/bulk)?`, 'Creating a Jira issue', 'Created a Jira issue'],
    [GET, String.raw`api/search`, 'Searching Jira issues', 'Searched Jira issues'],
    [POST, String.raw`api/search`, 'Searching Jira issues', 'Searched Jira issues'],
    [POST, String.raw`api/issueLink`, 'Linking Jira issues', 'Linked Jira issues'],
    [DELETE, String.raw`api/issueLink/(\d+)`, 'Removing an issue link', 'Removed an issue link'],
    [GET, String.raw`api/issueLinkType(?:/.*)?`, 'Listing link types', 'Listed link types'],
    [GET, String.raw`api/attachment/(\d+)`, 'Opening attachment', 'Opened attachment', g(1), O],
    [DELETE, String.raw`api/attachment/(\d+)`, 'Deleting attachment', 'Deleted attachment', g(1), O],
    [GET, String.raw`api/filter/(?:favourite|my)`, 'Listing your Jira filters', 'Listed your Jira filters'],
    [GET, String.raw`api/filter/(\d+)`, 'Opening Jira filter', 'Opened Jira filter', g(1), O],
    [PUT, String.raw`api/filter/(\d+)`, 'Updating Jira filter', 'Updated Jira filter', g(1), O],
    [POST, String.raw`api/filter`, 'Creating a Jira filter', 'Created a Jira filter'],
    [GET, String.raw`api/(?:user|groupuserpicker)(?:/.*)?`, 'Looking up Jira users', 'Looked up Jira users'],
    [GET, String.raw`api/myself`, 'Checking your Jira account', 'Checked your Jira account'],
    [GET, String.raw`api/project/([^/]+)/components`, 'Listing the components of', 'Listed the components of', g(1), O],
    [GET, String.raw`api/project/([^/]+)/versions?`, 'Listing the versions of', 'Listed the versions of', g(1), O],
    [GET, String.raw`api/project/([^/]+)/statuses`, 'Listing the statuses of', 'Listed the statuses of', g(1), O],
    [GET, String.raw`api/project/([^/]+)/role(?:/.*)?`, 'Reading the roles of', 'Read the roles of', g(1), O],
    [GET, String.raw`api/project/([^/]+)`, 'Opening Jira project', 'Opened Jira project', g(1), O],
    [GET, String.raw`api/project`, 'Listing Jira projects', 'Listed Jira projects'],
    [GET, String.raw`api/field`, 'Listing Jira fields', 'Listed Jira fields'],
    [POST, String.raw`api/component`, 'Creating a component', 'Created a component'],
    [POST, String.raw`api/version`, 'Creating a version', 'Created a version'],
    [GET, String.raw`agile/board`, 'Listing boards', 'Listed boards'],
    [GET, String.raw`agile/board/(\d+)/sprint`, 'Listing the sprints of board', 'Listed the sprints of board', g(1), O],
    [GET, String.raw`agile/board/(\d+)/backlog`, 'Reading the backlog of board', 'Read the backlog of board', g(1), O],
    [GET, String.raw`agile/board/(\d+)/epic`, 'Listing the epics on board', 'Listed the epics on board', g(1), O],
    [GET, String.raw`agile/board/(\d+)/(?:issue|epic/[^/]+/issue)`, 'Listing the issues on board', 'Listed the issues on board', g(1), O],
    [GET, String.raw`agile/board/(\d+)(?:/configuration)?`, 'Opening board', 'Opened board', g(1), O],
    [GET, String.raw`agile/sprint/(\d+)/issue`, 'Listing the issues in sprint', 'Listed the issues in sprint', g(1), O],
    [POST, String.raw`agile/sprint/(\d+)/issue`, 'Moving issues into sprint', 'Moved issues into sprint', g(1), O],
    [GET, String.raw`agile/sprint/(\d+)`, 'Opening sprint', 'Opened sprint', g(1), O],
    [DELETE, String.raw`agile/sprint/(\d+)`, 'Deleting sprint', 'Deleted sprint', g(1), O],
    [WRITE, String.raw`agile/sprint/(\d+)`, 'Updating sprint', 'Updated sprint', g(1), O],
    [POST, String.raw`agile/sprint`, 'Creating a sprint', 'Created a sprint'],
    [POST, String.raw`agile/backlog/issue`, 'Moving issues to the backlog', 'Moved issues to the backlog'],
    [GET, String.raw`agile/epic/([^/]+)/issue`, 'Listing the issues in epic', 'Listed the issues in epic', g(1), O],
    [POST, String.raw`agile/epic/([^/]+)/issue`, 'Moving issues into epic', 'Moved issues into epic', g(1), O],
    [PUT, String.raw`agile/issue/rank`, 'Ranking issues', 'Ranked issues'],
    [GET, String.raw`agile/issue/([^/]+)`, 'Opening', 'Opened', g(1), O],
  ],
  callConfluenceApi: [
    [GET, String.raw`api/(?:content/)?search`, 'Searching Confluence', 'Searched Confluence'],
    [GET, `${C}/label`, 'Reading the labels of page', 'Read the labels of page', g(1), O],
    [POST, `${C}/label`, 'Labeling page', 'Labeled page', g(1), O],
    [DELETE, `${C}/label(?:/.*)?`, 'Removing a label from page', 'Removed a label from page', g(1), O],
    [GET, `${C}/child/attachment(?:/.*)?`, 'Listing the attachments of page', 'Listed the attachments of page', g(1), O],
    [WRITE, `${C}/child/attachment(?:/.*)?`, 'Attaching a file to page', 'Attached a file to page', g(1), O],
    [GET, `${C}/child/page`, 'Listing the child pages of page', 'Listed the child pages of page', g(1), O],
    [GET, `${C}/child/comment`, 'Reading comments on page', 'Read comments on page', g(1), O],
    [GET, `${C}/child`, 'Listing the children of page', 'Listed the children of page', g(1), O],
    [GET, `${C}/descendant(?:/.*)?`, 'Listing the pages under page', 'Listed the pages under page', g(1), O],
    [GET, `${C}/restriction(?:/.*)?`, 'Reading the restrictions of page', 'Read the restrictions of page', g(1), O],
    [WRITE, `${C}/restriction(?:/.*)?`, 'Changing the restrictions of page', 'Changed the restrictions of page', g(1), O],
    [GET, `${C}/(?:history|version)(?:/.*)?`, 'Reading the history of page', 'Read the history of page', g(1), O],
    [GET, `${C}/property(?:/.*)?`, 'Reading the properties of page', 'Read the properties of page', g(1), O],
    [WRITE, `${C}/property(?:/.*)?`, 'Changing the properties of page', 'Changed the properties of page', g(1), O],
    [WRITE, `${C}/(?:pagehierarchy/)?copy(?:/.*)?`, 'Copying page', 'Copied page', g(1), O],
    [WRITE, `${C}/move/.*`, 'Moving page', 'Moved page', g(1), O],
    [GET, C, 'Opening page', 'Opened page', g(1), O],
    [PUT, C, 'Updating page', 'Updated page', g(1), O],
    [DELETE, C, 'Deleting page', 'Deleted page', g(1), O],
    [GET, String.raw`api/content`, 'Listing Confluence pages', 'Listed Confluence pages'],
    [POST, String.raw`api/content`, 'Creating a Confluence page', 'Created a Confluence page'],
    [POST, String.raw`api/user/watch/content/(\d+)`, 'Watching page', 'Watched page', g(1), O],
    [DELETE, String.raw`api/user/watch/content/(\d+)`, 'Unwatching page', 'Stopped watching page', g(1), O],
    [GET, String.raw`api/user/watch/content/(\d+)`, 'Checking whether you watch page', 'Checked whether you watch page', g(1), O],
    [GET, String.raw`api/space/([^/]+)/content(?:/.*)?`, 'Listing the pages in space', 'Listed the pages in space', g(1), O],
    [GET, String.raw`api/space/([^/]+)`, 'Opening space', 'Opened space', g(1), O],
    [GET, String.raw`api/space`, 'Listing Confluence spaces', 'Listed Confluence spaces'],
    [GET, String.raw`api/template(?:/.*)?`, 'Listing Confluence templates', 'Listed Confluence templates'],
    [POST, String.raw`api/contentbody/convert/.*`, 'Converting page content', 'Converted page content'],
    [GET, String.raw`api/(?:user|group)(?:/.*)?`, 'Looking up Confluence users', 'Looked up Confluence users'],
  ],
}

const PRODUCT: Record<string, string> = {
  callBitbucketApi: 'Bitbucket',
  callJiraApi: 'Jira',
  callConfluenceApi: 'Confluence',
}

export function isRestPassthrough(toolName: string): boolean {
  return toolName in RULES
}

// `/rest/api/1.0/projects/P/repos/r` -> `api/projects/P/repos/r`; `/wiki/rest/api/content/1` ->
// `api/content/1`; `/rest/build-status/1.0/commits/abc` -> `build-status/commits/abc`. The version
// segment is dropped - Jira's 2, 3 and latest, Bitbucket's 1.0 - since no rule depends on it.
function normalize(path: string): { key: string; query: URLSearchParams } {
  const [rawPath, rawQuery = ''] = path.trim().split('?', 2)
  const segments = rawPath.split('/').filter(Boolean)
  if (segments[0] === 'wiki') segments.shift()
  if (segments[0] === 'rest') segments.shift()
  const family = segments.shift() ?? ''
  if (segments[0] && /^(latest|\d[\d.]*)$/.test(segments[0])) segments.shift()
  return { key: [family, ...segments].join('/'), query: new URLSearchParams(rawQuery) }
}

// The one query that identifies a search: JQL or CQL, from the query string or a POST body.
function searchDetail(query: URLSearchParams, body: unknown): string | undefined {
  const fromQuery = query.get('jql') ?? query.get('cql')
  if (fromQuery) return fromQuery
  if (typeof body === 'string') {
    try {
      const parsed = JSON.parse(body) as Record<string, unknown>
      const q = parsed.jql ?? parsed.cql
      if (typeof q === 'string') return q
    } catch { /* not JSON - no detail */ }
  }
  return undefined
}

const ID = /^(\d+|[A-Za-z][A-Za-z0-9_]*-\d+|[0-9a-fA-F]{7,40})$/

function words(segment: string): string {
  return segment.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[-_]+/g, ' ').toLowerCase()
}

/**
 * The phrase for one passthrough call, or undefined when the tool is not a passthrough or its
 * arguments carry no usable method and path. Never undefined for a passthrough WITH a path: an
 * endpoint no rule names still reads as "Reading watchers from Jira" off its last resource word.
 */
export function restCallActivity(toolName: string, args: Record<string, unknown>, t: Translate): RestActivity | undefined {
  const rules = RULES[toolName]
  if (!rules) return undefined
  const method = typeof args.method === 'string' ? args.method.trim().toUpperCase() : ''
  const path = typeof args.path === 'string' ? args.path : ''
  if (!method || !path) return undefined
  const { key, query } = normalize(path)

  for (const [methods, pattern, running, done, detail, takesObject] of rules) {
    if (!methods.test(method)) continue
    const m = key.match(new RegExp(`^${pattern}/?$`))
    if (!m) continue
    const isSearch = /search$/.test(pattern)
    return {
      running: t(running),
      done: t(done),
      takesObject: takesObject === true,
      detail: isSearch ? searchDetail(query, args.body) : detail?.(m),
    }
  }

  // No rule: name the last resource the path addresses, and the last identifier in it.
  const segments = key.split('/').slice(1).map(decode)
  const resource = [...segments].reverse().find(s => !ID.test(s))
  const id = [...segments].reverse().find(s => ID.test(s))
  const what = resource ? words(resource) : ''
  const product = PRODUCT[toolName]
  const vars = { what, product }
  const [running, done] = !what
    ? [t('Calling {product}', vars), t('Called {product}', vars)]
    : GET.test(method) ? [t('Reading {what} from {product}', vars), t('Read {what} from {product}', vars)]
    : POST.test(method) ? [t('Sending {what} to {product}', vars), t('Sent {what} to {product}', vars)]
    : PUT.test(method) ? [t('Updating {what} in {product}', vars), t('Updated {what} in {product}', vars)]
    : [t('Deleting {what} from {product}', vars), t('Deleted {what} from {product}', vars)]
  return { running, done, takesObject: false, detail: id }
}
