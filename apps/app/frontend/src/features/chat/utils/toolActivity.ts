// Explicit .ts extensions, so `node --test` can load this module - toolActivity.test.ts.
import { redactFilePaths } from './redactFilePaths.ts'
import { isRestPassthrough, restCallActivity } from './restCallActivity.ts'
import { NOUNS, mcpToolParts, toolDisplayName } from './toolDisplayName.ts'
import type { ToolCallInfo } from '../types/chat.types'
import type { Translate } from '@/i18n'

// What a tool call SAYS while the thread is watching it.
//
// This is a different job from toolDisplayName(), and the two are deliberately not merged.
// toolDisplayName answers "what is this tool called" and is right for a modal title or a consent
// prompt, where the tool is the subject of the sentence around it. This answers "what is happening
// right now", for the one line a reader actually watches during a turn - so it is a clause, in the
// tense the call is currently in: "Searching your sources", then "Searched 2 sources".
//
// The header used to render toolDisplayName plus a dump of the argument values, which produced
// "Search Indexed Sources - release notes 5 0.3" - a function signature read aloud. Naming the
// tool tells the reader nothing they wanted; what the agent is DOING with it is the whole content
// of that line.
//
// Three layers, most specific first:
//   1. SUMMARIES - a finished call whose RESULT says something better than its arguments do. Only
//      worth an entry where a count or an identifier is the actual news ("Created PROX-412").
//   2. PHRASES - tools whose derived wording reads badly. "Added Jira comment" is not how anyone
//      says it; "Commented on PROX-412" is.
//   3. derive() - conjugates the tool's own camelCase name. This is the layer that matters for
//      maintenance: a tool added to the backend tomorrow narrates itself correctly with no entry
//      here at all, and there are ~60 of them. Only add above when the derived form is wrong.
//
// TRANSLATION, AND WHERE IT STOPS. Layers 1 and 2 are finite hand-written English and are
// translated: PHRASES, CONJUGATIONS and the SUMMARIES lines all go through `t`, keyed on their
// English exactly like every other string in the app.
//
// LAYER 3 CANNOT BE FULLY TRANSLATED, and that is a property of the design rather than an
// oversight. derive() builds a clause out of a tool's own camelCase NAME at runtime - the object
// half of "Searching Jira issues" is the words "jira issues" taken from `searchJiraIssues`, and
// a backend tool added tomorrow produces a phrase no catalog could have an entry for. So the VERB
// is translated (CONJUGATIONS) and the object stays as the tool named it. A reader on a
// Vietnamese interface sees a translated verb and an English object for the ~30 tools with no
// PHRASES entry, which is the same partial-English degradation the whole source-key model is
// built to fail into. The fix, if it ever matters, is a PHRASES entry per tool - which trades
// away the property that makes layer 3 worth having.


// Arguments are quoted after the phrase, and a query can be a paragraph. Long enough to identify
// what was searched for, short enough that the line never wraps and never pushes the chevron off
// the end of the row.
const MAX_DETAIL = 42

// The third slot marks a phrase that takes the detail as its DIRECT OBJECT - "Commenting on
// PROX-412", not "Commenting on - PROX-412". Everything else is a complete clause with the detail
// appended as an aside, which needs the separator to stop the two running together.
type Phrase = readonly [running: string, done: string, takesObject?: true]

const PHRASES: Record<string, Phrase> = {
  // The everyday four. These are most of what any turn does, so they are the four whose wording is
  // worth arguing about - "Searching your sources" rather than "Searching indexed sources" because
  // the reader registered them as sources when they connected them, not as an index.
  searchIndexedSources:   ['Searching your sources',        'Searched your sources'],
  recall:                 ['Recalling memory',              'Recalled memory'],
  remember:               ['Remembering',                   'Remembered',                  true],
  readSkill:              ['Loading skill',                 'Loaded skill'],
  askUser:                ['Asking you',                    'Asked you'],

  // The knowledge graph's two typed answers. "the graph" because that is what the reader turned on
  // per source; the name looked up follows as the detail.
  findGraphPath:          ['Tracing connections',           'Traced connections'],
  findGraphPeople:        ['Finding who works on',          'Found who works on',          true],
  findGraphTimeline:      ['Reading the history of',        'Read the history of',         true],
  findGraphThemes:        ['Finding the main themes',       'Found the main themes'],

  // Delegation. The row's own children narrate themselves, so this one only has to say that a
  // second agent is on it - "Delegating" names the mechanism, which is our business and not the
  // reader's.
  delegate:               ['Working on a sub-task',         'Finished a sub-task'],

  webSearch:              ['Searching the web',             'Searched the web'],
  webFetch:               ['Reading page',                  'Read page'],
  // The distinction that matters to a waiting reader is that this one is slow because it drives a
  // real browser, not that it "renders".
  renderWebFetch:         ['Opening page in a browser',     'Opened page in a browser'],
  read:                   ['Reading file',                  'Read file'],
  grep:                   ['Searching files',               'Searched files'],
  convert:                ['Converting file',               'Converted file'],

  getPlatformInfo:        ['Checking this deployment',      'Checked this deployment'],
  getConnectorStatus:     ['Checking your sources',         'Checked your sources'],
  getJiraCurrentUser:     ['Checking your Jira account',    'Checked your Jira account'],

  // add* derives to "Adding to Jira comment", which is both ungrammatical and about the wrong
  // object - the comment is what is being made, the issue is what it lands on.
  addJiraComment:         ['Commenting on',                 'Commented on',                true],
  addConfluenceComment:   ['Commenting on Confluence page',  'Commented on Confluence page'],
  addBitbucketPrComment:  ['Commenting on pull request',    'Commented on pull request',   true],
  updateJiraComment:      ['Editing a comment on',          'Edited a comment on',         true],
  deleteJiraComment:      ['Deleting a comment on',         'Deleted a comment on',        true],
  transitionJiraIssue:    ['Moving',                        'Moved',                       true],
  logJiraWork:            ['Logging work on',               'Logged work on',              true],
  searchConversations:    ['Searching earlier conversations', 'Searched earlier conversations'],
  attachJiraFile:         ['Attaching a file to',           'Attached a file to',          true],
  attachConfluenceFile:   ['Attaching a file to Confluence page', 'Attached a file to Confluence page'],
  updateConfluenceLabels: ['Labeling Confluence page',      'Labeled Confluence page'],
  moveConfluencePage:     ['Moving Confluence page',        'Moved Confluence page'],
  updateConfluenceComment: ['Editing a Confluence comment', 'Edited a Confluence comment'],
  deleteConfluenceComment: ['Deleting a Confluence comment', 'Deleted a Confluence comment'],
  createBitbucketBranch:  ['Creating a branch',             'Created a branch'],
  deleteBitbucketBranch:  ['Deleting a branch',             'Deleted a branch'],
  listJiraIssueTypes:     ['Checking Jira issue types',     'Checked Jira issue types'],

  createBitbucketPr:      ['Opening a pull request',        'Opened a pull request'],
  mergeBitbucketPr:       ['Merging pull request',          'Merged pull request',         true],
  reviewBitbucketPr:      ['Reviewing pull request',        'Reviewed pull request',       true],
  getBitbucketPr:         ['Opening pull request',          'Opened pull request',         true],
  getBitbucketCloudPr:    ['Opening pull request',          'Opened pull request',         true],
  readBitbucketCloudFile: ['Reading',                       'Read',                        true],
  getBitbucketPrDiff:     ['Reading the diff of',           'Read the diff of',            true],
  getBitbucketCommitDiff: ['Reading the commit diff',       'Read the commit diff'],
  getBitbucketCommits:    ['Reading commit history',        'Read commit history'],
  // Verbs derive() does not conjugate, which left these reading as the bare tool name.
  browseBitbucketPath:    ['Browsing repository',           'Browsed repository'],
  downloadBitbucketRepo:  ['Downloading repository',        'Downloaded repository'],
  deleteBitbucketRepo:    ['Deleting the downloaded copy of', 'Deleted the downloaded copy of', true],
  linkJiraIssues:         ['Linking Jira issues',           'Linked Jira issues'],
  runPython:              ['Working on the file',           'Worked on the file'],
  useServer:              ['Switching server',              'Switched server'],
  openLocalFile:          ['Opening a file from your computer', 'Opened a file from your computer'],
  saveLocalFile:          ['Saving to your computer',       'Saved to your computer',      true],
}

// Keyed by the FIRST word of a camelCase tool name. `get` becomes "Opening" rather than "Getting"
// - every get* tool here fetches one named thing, and that is what opening it means to a reader.
const CONJUGATIONS: Record<string, Phrase> = {
  get:      ['Opening',   'Opened'],
  list:     ['Listing',   'Listed'],
  search:   ['Searching', 'Searched'],
  create:   ['Creating',  'Created'],
  update:   ['Updating',  'Updated'],
  delete:   ['Deleting',  'Deleted'],
  add:      ['Adding to', 'Added to'],
  remove:   ['Removing',  'Removed'],
  // register* means "index this from now on". "Registering a Jira project" is our word for it.
  register: ['Indexing',  'Indexed'],
  // Not the bare word Read: that string is also the connector permission switch, a verb, and one
  // English key carries one Vietnamese translation, so the past tense there made the switch wrong.
  read:     ['Reading',   'Finished reading'],
  merge:    ['Merging',   'Merged'],
  review:   ['Reviewing', 'Reviewed'],
  log:      ['Logging',   'Logged'],
  render:   ['Rendering', 'Rendered'],
  convert:  ['Converting', 'Converted'],
}

// Words the derived form must not lower-case - NOUNS, shared with toolDisplayName.

// Argument names worth quoting after the phrase, most identifying first. The point is the ONE
// thing that tells two otherwise identical calls apart - which issue, which query, which URL -
// so this is a priority order, not a set.
const DETAIL_KEYS = [
  'issueKey', 'pageId', 'prId', 'commitId', 'query', 'jql', 'cql', 'pattern',
  'url', 'fact', 'summary', 'title', 'name', 'skillName', 'task', 'targetStatus',
  'repoSlug', 'projectKey', 'spaceKey', 'branch', 'tag', 'fileName', 'server', 'product',
]

// Finished calls whose result is more informative than their arguments. Each returns the WHOLE
// line, not a fragment, because the interesting phrasings ("Searched 2 sources") do not decompose
// into a fixed verb plus a detail.
const SUMMARIES: Record<string, (result: Record<string, unknown>, t: Translate) => string | undefined> = {
  searchIndexedSources: (r, t) => {
    const rows = Array.isArray(r.results) ? r.results : null
    if (!rows) return undefined
    if (rows.length === 0) return t('Searched your sources, nothing matched')
    // Distinct SOURCES, not chunks. Five hits in one document is one source, and "Searched 5
    // sources" there would be a straightforward lie about how broad the answer's basis is. A
    // document is its item within the source it was indexed from (sourceId): `ticket:1` in a
    // Zendesk source and in a Freshdesk one are two.
    const sources = new Set(
      rows.map(row => {
        if (!row || typeof row !== 'object') return ''
        const r = row as Record<string, unknown>
        const item = String(r.source ?? '')
        return item ? `${String(r.sourceId ?? '')}\u0000${item}` : ''
      }).filter(Boolean))
    const n = sources.size || rows.length
    return t('Searched {count} source|Searched {count} sources', { count: n })
  },
  // The issue key is an identifier the reader would quote back, so it stays verbatim.
  createJiraIssue: (r, t) => (typeof r.issueKey === 'string' && r.issueKey ? t('Created {key}', { key: r.issueKey }) : undefined),
  // The files the run made or changed, which is what the reader is waiting for.
  runPython: (r, t) => {
    const files = Array.isArray(r.files) ? r.files as Array<Record<string, unknown>> : null
    const changed = files?.filter(f => f.changed === true).map(f => String(f.name)) ?? []
    return changed.length > 0 ? t('Wrote {files}', { files: changed.join(', ') }) : undefined
  },
  webSearch: (r, t) => {
    const rows = Array.isArray(r.results) ? r.results : null
    if (!rows) return undefined
    return rows.length === 0
      ? t('Searched the web, nothing matched')
      : t('Searched the web, {count} result|Searched the web, {count} results', { count: rows.length })
  },
}

function capitalize(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s
}

function truncate(s: string): string {
  const flat = s.replace(/\s+/g, ' ').trim()
  return flat.length > MAX_DETAIL ? `${flat.slice(0, MAX_DETAIL - 1)}...` : flat
}

// A model's boolean argument, sent either as JSON true or as the string.
function isTrue(v: unknown): boolean {
  return v === true || v === 'true'
}

// searchJiraIssues -> ["Searching Jira issues", "Searched Jira issues"].
// A name whose first word is not a verb we know falls back to its humanized self in both tenses,
// which is no worse than what the row said before and is never wrong.
// The VERB is translated, the OBJECT is not - see the note at the top of this file. `object` is
// words taken from the tool's own name, so there is nothing for a catalog to key on.
function derive(name: string, t: Translate): Phrase {
  const words = name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').split(/[\s_-]+/).filter(Boolean)
  const conjugation = CONJUGATIONS[words[0].toLowerCase()]
  if (!conjugation || words.length === 1) {
    const plain = capitalize(words.map(w => NOUNS[w.toLowerCase()] ?? w.toLowerCase()).join(' '))
    return [plain, plain]
  }
  const object = words.slice(1).map(w => NOUNS[w.toLowerCase()] ?? w.toLowerCase()).join(' ')
  return [`${t(conjugation[0])} ${object}`, `${t(conjugation[1])} ${object}`]
}

function parse(json?: string): Record<string, unknown> {
  if (!json) return {}
  try {
    const parsed = JSON.parse(json)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : {}
  } catch {
    return {}
  }
}

// The one argument worth naming. Falls back to the first non-empty scalar so a tool with none of
// the known keys still identifies itself rather than going bare.
//
// Note what is NOT here: filesystem paths. redactFilePaths drops every *Path key before this
// looks at anything, so a `read` call narrates itself as "Read file" with no detail rather than
// putting an absolute server path in the thread - see SensitivePaths on the backend for the same
// rule at the boundary. A bare phrase is the correct outcome, not a gap to fill.
function detailFrom(args: Record<string, unknown>): string | undefined {
  const safe = redactFilePaths(args)
  for (const key of DETAIL_KEYS) {
    const v = safe[key]
    if (typeof v === 'string' && v.trim()) return truncate(v)
    // Pull-request and issue numbers read as numbers on their own; "#88" says which kind.
    if (typeof v === 'number') return key === 'prId' ? `#${v}` : String(v)
  }
  for (const v of Object.values(safe)) {
    if (typeof v === 'string' && v.trim()) return truncate(v)
  }
  return undefined
}

/**
 * The line for one tool call, in the tense it is currently in.
 *
 * Running: "Searching your sources - release notes". Finished: "Searched 2 sources", or
 * "Commented on PROX-412". A failed call is NOT narrated here - the row renders the error from the
 * result instead, and a past-tense claim that something was done is exactly the wrong thing to
 * show when it was refused or threw (see ToolCallList's isFailedResult).
 */
export function toolActivity(tc: ToolCallInfo, t: Translate): string {
  const args = parse(tc.arguments)
  // A delegation names itself: the main agent gives every sub-task a short title for exactly this
  // line (see DelegateTool), so the row reads "Review SAI project tasks" rather than a slice of
  // the instruction it wrote for the sub-agent. The row's own spinner and tick say running or done.
  if (tc.toolName === 'delegate' && typeof args.title === 'string' && args.title.trim()) {
    return truncate(args.title.trim())
  }
  // A memory entry corrected or deleted by its id says so: "Remembered" would claim a new one.
  if (tc.toolName === 'remember' && typeof args.id === 'string' && args.id.trim()) {
    if (isTrue(args.forget)) return tc.done ? t('Forgot a memory') : t('Forgetting a memory')
    const detail = detailFrom({ fact: args.fact })
    const p = tc.done ? t('Updated memory') : t('Updating memory')
    return detail ? `${p} - ${detail}` : p
  }
  // The main agent directing a sub-agent it started: what it told it, or that it stopped it.
  if (tc.toolName === 'steerSubAgent') {
    if (isTrue(args.stop)) return tc.done ? t('Stopped a sub-task') : t('Stopping a sub-task')
    const detail = detailFrom({ message: args.message })
    const p = tc.done ? t('Updated a sub-task') : t('Updating a sub-task')
    return detail ? `${p} - ${detail}` : p
  }
  // A sub-agent talking to the agent that started it, in its own panel: a question it waits on, or
  // something it tells it and carries on.
  if (tc.toolName === 'messageMainAgent') {
    const detail = detailFrom({ message: args.message })
    const p = isTrue(args.waitForReply)
      ? (tc.done ? t('Asked the main agent') : t('Asking the main agent'))
      : (tc.done ? t('Told the main agent') : t('Telling the main agent'))
    return detail ? `${p} - ${detail}` : p
  }
  // A path between two things names both: "Traced connections - Ana and Ledger".
  if (tc.toolName === 'findGraphPath' && typeof args.from === 'string' && typeof args.to === 'string') {
    const between = truncate(`${args.from} and ${args.to}`)
    return `${tc.done ? t('Traced connections') : t('Tracing connections')} - ${between}`
  }
  // A REST passthrough says what its method and path do - see restCallActivity.ts.
  const rest = restCallActivity(tc.toolName, args, t)
  if (rest) {
    const detail = rest.detail ? truncate(rest.detail) : undefined
    const p = tc.done ? rest.done : rest.running
    return !detail ? p : rest.takesObject ? `${p} ${detail}` : `${p} - ${detail}`
  }
  // A connected MCP server's tool: its server, then the tool's own name read as a phrase -
  // "fake http: Echo - hi" rather than the advertised mcp__fake_http_812f__echo.
  const mcp = mcpToolParts(tc.toolName)
  if (mcp) {
    const [running, done] = derive(mcp.tool, t)
    const p = tc.done ? done : running
    const detail = detailFrom(args)
    return `${mcp.server}: ${detail ? `${p} - ${detail}` : p}`
  }
  // A PHRASES hit is a hand-written English pair and so a catalog key; a derived one has already
  // had its verb translated inside derive(). Translating here would double-translate the first.
  const phrase = PHRASES[tc.toolName]
  const [running, done, takesObject] = phrase
    ? [t(phrase[0]), t(phrase[1]), phrase[2]] as const
    : derive(tc.toolName, t)
  const detail = tc.toolName === 'runPython' ? undefined : detailFrom(args)
  const join = (p: string) =>
    !detail ? p : takesObject ? `${p} ${detail}` : `${p} - ${detail}`

  if (!tc.done) return join(running)

  const summarize = SUMMARIES[tc.toolName]
  if (summarize) {
    const result = parse(tc.result)
    // Only when the call actually succeeded. A summary built from an error payload reads as a
    // report of work that did not happen.
    if (result.success !== false) {
      const line = summarize(result, t)
      if (line) return line
    }
  }
  return join(done)
}

/**
 * The line for a FAILED call. Normally the tool's own name - see ToolCallList - but a REST
 * passthrough's name is "Call Bitbucket api", which says nothing about what failed, so it keeps
 * its specific wording in the running tense: "Listing pull requests - web" over a red cross claims
 * nothing was done, which the finished tense would.
 */
export function failedToolLabel(tc: ToolCallInfo, t: Translate): string {
  if (isRestPassthrough(tc.toolName)) {
    const rest = restCallActivity(tc.toolName, parse(tc.arguments), t)
    if (rest) return !rest.detail ? rest.running
      : rest.takesObject ? `${rest.running} ${truncate(rest.detail)}` : `${rest.running} - ${truncate(rest.detail)}`
  }
  return toolDisplayName(tc.toolName, t)
}

// Every write-tool guard or denial (approval deny, McpGuardService's per-user and org consent
// checks) returns this exact shape - used to tell a real failure apart from a genuine success so it
// does not render under the same green check as a completed call that actually worked.
export function isFailedResult(result: string): boolean {
  try {
    const obj = JSON.parse(result)
    return !!obj && typeof obj === 'object' && obj.success === false
  } catch {
    return false
  }
}

export function isFailedCall(tc: ToolCallInfo): boolean {
  return tc.done && !!tc.result && isFailedResult(tc.result)
}
