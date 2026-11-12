// Explicit .ts extensions, so `node --test` can load this module - toolActivity.test.ts.
import { redactFilePaths } from './redactFilePaths.ts'
import { isRestPassthrough, restCallActivity } from './restCallActivity.ts'
import { NOUNS, mcpToolParts, splitToolName, toolDisplayName } from './toolDisplayName.ts'
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
//   2. PHRASES - EVERY tool the product offers, by hand: "Commented on PROX-412", never the
//      "Added Jira comment" its name would conjugate to.
//   3. derive() - conjugates a camelCase name at runtime. Only for a connected MCP server's tool,
//      whose name is that server's own, and a stored call of a tool that no longer exists.
//
// EVERYTHING A PERSON READS HERE IS TRANSLATED (2026-11-05, the maintainer overruling the earlier
// design). Layer 3 used to narrate any tool without an entry - a backend tool added tomorrow needed
// none - but it builds the object of its clause out of the tool's own NAME ("jira issues" from
// `searchJiraIssues`), and no catalog can hold a key for words that exist only at runtime: a
// Vietnamese reader saw a translated verb and an English object. So every tool has a PHRASES entry,
// each half a catalog key, and toolNames.test.ts holds every name the backend can offer (generated
// from the Java source by tools/i18n/tool-names.mjs) to one - a tool added tomorrow fails the
// pipeline until it is named, rather than narrating itself in English.


// Arguments are quoted after the phrase, and a query can be a paragraph. Long enough to identify
// what was searched for, short enough that the line never wraps and never pushes the chevron off
// the end of the row.
const MAX_DETAIL = 42

// The third slot marks a phrase that takes the detail as its DIRECT OBJECT - "Commenting on
// PROX-412", not "Commenting on - PROX-412". Everything else is a complete clause with the detail
// appended as an aside, which needs the separator to stop the two running together.
export type Phrase = readonly [running: string, done: string, takesObject?: true]

// Every tool the product offers has an entry (toolNames.test.ts), including those whose row a
// branch of toolActivity() writes itself - see the block of them below.
export const PHRASES: Record<string, Phrase> = {
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

  // The tree of agents (since 2026-10-28). A deployAgent call is drawn as the agent's panel, but
  // its detail dialog and a failed row still say what it did.
  deployAgent:            ['Deploying an agent',            'Deployed an agent'],
  tellAgent:              ['Messaging an agent',            'Messaged an agent'],
  // What a Constellations lead tells the person from a run - it lands in the lead chat of the person.
  tellPerson:             ['Messaging you',                 'Messaged you'],
  awaitAgents:            ['Waiting for its agents',        'Heard from its agents'],
  deployAgents:           ['Deploying agents over a list',  'Deployed agents over a list'],
  readAgentReport:        ['Reading a report in full',      'Read a report in full'],
  verifyReport:           ['Having a report checked',       'Had a report checked'],
  deliberate:             ['Putting a question to a panel', 'Put a question to a panel'],
  recordDecision:         ['Recording a decision',          'Recorded a decision'],
  handFile:               ['Handing on a file',             'Handed on a file'],
  // A stored delegation, from before then: one line that a second agent was on it.
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
  // The YouTube channel. A reply is posted publicly as the channel, so its row says so plainly.
  listYouTubeComments:    ['Reading YouTube comments',      'Read YouTube comments'],
  getYouTubeViewerHistory: ['Looking up a YouTube viewer',  'Looked up a YouTube viewer'],
  listYouTubeChannelReplies: ['Reading YouTube replies',    'Read YouTube replies'],
  listWaitingYouTubeReplies: ['Reading YouTube replies waiting for approval', 'Read YouTube replies waiting for approval'],
  getYouTubeVideo:        ['Opening YouTube video',         'Opened YouTube video'],
  replyToYouTubeComment:  ['Replying to a YouTube comment', 'Replied to a YouTube comment'],
  // The numbers of the channel (Part A, 2026-10-31): its stats, its uploads, the named reports of
  // YouTube Analytics, and the impressions and click-through rate of the Reporting API.
  getYouTubeChannelStats: ['Reading YouTube channel stats', 'Read YouTube channel stats'],
  listYouTubeVideos:      ['Listing YouTube videos',        'Listed YouTube videos'],
  getYouTubeAnalytics:    ['Reading YouTube Analytics',     'Read YouTube Analytics'],
  getYouTubeReach:        ['Reading YouTube impressions',   'Read YouTube impressions'],
  // The tools a Constellations agent has of its own (2026-10-31): its teammates, the files of its
  // workspace and the board. Derived, they read as bare names ("Write workspace file") on its card.
  getTeamStatus:          ['Checking on its team',          'Checked on its team'],
  saveMemory:             ['Saving a memory',               'Saved a memory'],
  rememberForTeam:        ['Noting it for the team',        'Noted it for the team'],
  withdrawApproval:       ['Withdrawing writes',            'Withdrew writes'],
  listWaitingApprovals:   ['Reading the writes waiting for approval', 'Read the writes waiting for approval'],
  // The comment queue a YouTube team works from (2026-11-05).
  nextComments:           ['Taking new comments to sort',   'Took new comments to sort'],
  decideComments:         ['Sorting comments',              'Sorted comments'],
  nextAnswers:            ['Taking comments to answer',     'Took comments to answer'],
  raiseOpenPoint:         ['Asking you a question',         'Asked you a question'],
  listOpenPoints:         ['Reading the questions for you', 'Read the questions for you'],
  dropComments:           ['Closing comments that get no reply', 'Closed comments that get no reply'],
  // The lead handling the person's questions as told, from the lead chat (2026-11-07).
  findQuestions:          ['Looking through your questions', 'Looked through your questions'],
  settleQuestions:        ['Settling questions as you said', 'Settled questions as you said'],
  editQuestion:           ['Rewording a question',          'Reworded a question'],
  // The lead building its team from the lead chat (2026-11-02).
  describeTeam:           ['Reading the team',              'Read the team'],
  addTeamAgent:           ['Adding an agent',               'Added an agent'],
  updateTeamAgent:        ['Changing an agent',             'Changed an agent'],
  removeTeamAgent:        ['Removing an agent',             'Removed an agent'],
  linkTeamAgents:         ['Linking agents',                'Linked agents'],
  scheduleTeamAgent:      ['Scheduling an agent',           'Scheduled an agent'],
  listWorkspace:          ['Looking through its files',     'Looked through its files'],
  readWorkspaceFile:      ['Reading a file',                'Read a file'],
  writeWorkspaceFile:     ['Writing a file',                'Wrote a file'],
  editWorkspaceFile:      ['Editing a file',                'Edited a file'],
  deleteWorkspaceFile:    ['Deleting a file',               'Deleted a file'],
  appendJournal:          ['Writing in its journal',        'Wrote in its journal'],
  postClaim:              ['Posting a finding to the board', 'Posted a finding to the board'],
  listClaims:             ['Reading the board',             'Read the board'],
  updateClaim:            ['Updating a finding',            'Updated a finding'],
  runPython:              ['Working on the file',           'Worked on the file'],
  useServer:              ['Switching server',              'Switched server'],
  openLocalFile:          ['Opening a file from your computer', 'Opened a file from your computer'],
  saveLocalFile:          ['Saving to your computer',       'Saved to your computer',      true],
  runCommand:             ['Running a command',             'Ran a command'],
  convertDocument:        ['Converting file',               'Converted file'],

  // Sources.
  listSourceTypes:        ['Checking the types of source',  'Checked the types of source'],
  addSource:              ['Adding a source',               'Added a source'],
  removeSource:           ['Removing a source',             'Removed a source'],
  readSourceItem:         ['Reading an item from a source', 'Read an item from a source'],
  saveToSource:           ['Saving a note to a source',     'Saved a note to a source'],
  searchKnowledgeGraph:   ['Searching the knowledge graph', 'Searched the knowledge graph'],

  // Rows a branch of toolActivity() writes - these are what toolNames.test.ts checks, and what the
  // row falls back to where a branch does not answer (a REST call no rule in restCallActivity matches).
  callJiraApi:            ['Sending a Jira request',        'Sent a Jira request'],
  callConfluenceApi:      ['Sending a Confluence request',  'Sent a Confluence request'],
  callBitbucketApi:       ['Sending a Bitbucket request',   'Sent a Bitbucket request'],
  controlAgent:           ['Checking its agents',           'Checked its agents'],
  alarm:                  ['Setting an alarm',              'Set an alarm'],
  background:             ['Starting background work',      'Started background work'],
  messageAgent:           ['Messaging a teammate',          'Messaged a teammate'],
  messageProjectLead:     ['Asking a project lead',         'Messaged a project lead'],
  steerSubAgent:          ['Updating a sub-task',           'Updated a sub-task'],
  messageMainAgent:       ['Telling the main agent',        'Told the main agent'],

  // Jira, Confluence, Bitbucket and SVN tools whose names would conjugate well enough - written out
  // all the same, since a derived object stays English.
  searchJiraIssues:       ['Searching Jira issues',         'Searched Jira issues'],
  getJiraIssue:           ['Opening Jira issue',            'Opened Jira issue'],
  createJiraIssue:        ['Creating a Jira issue',         'Created a Jira issue'],
  updateJiraIssue:        ['Updating Jira issue',           'Updated Jira issue',          true],
  deleteJiraIssue:        ['Deleting Jira issue',           'Deleted Jira issue',          true],
  updateJiraEstimate:     ['Updating the estimate of',      'Updated the estimate of',     true],
  deleteJiraIssueLink:    ['Removing a link between Jira issues', 'Removed a link between Jira issues'],
  listJiraProjects:       ['Listing Jira projects',         'Listed Jira projects'],
  listJiraFields:         ['Listing Jira fields',           'Listed Jira fields'],
  listJiraVersions:       ['Listing Jira versions',         'Listed Jira versions'],
  createJiraVersion:      ['Creating a Jira version',       'Created a Jira version'],
  searchConfluencePages:  ['Searching Confluence pages',    'Searched Confluence pages'],
  getConfluencePage:      ['Opening Confluence page',       'Opened Confluence page'],
  createConfluencePage:   ['Creating a Confluence page',    'Created a Confluence page'],
  updateConfluencePage:   ['Updating a Confluence page',    'Updated a Confluence page'],
  deleteConfluencePage:   ['Deleting a Confluence page',    'Deleted a Confluence page'],
  listConfluenceSpaces:   ['Listing Confluence spaces',     'Listed Confluence spaces'],
  listBitbucketBranches:  ['Listing Bitbucket branches',    'Listed Bitbucket branches'],
  listBitbucketTags:      ['Listing Bitbucket tags',        'Listed Bitbucket tags'],
  createBitbucketTag:     ['Creating a tag',                'Created a tag'],
  listBitbucketFiles:     ['Listing repository files',      'Listed repository files'],
  listSvnFiles:           ['Listing SVN files',             'Listed SVN files'],
  readSvnFile:            ['Reading an SVN file',           'Read an SVN file'],

  // Key accounts (Enterprise Server). The agent proposes; a person confirms.
  listKeyAccounts:        ['Listing key accounts',          'Listed key accounts'],
  openKeyAccount:         ['Opening a key account',         'Opened a key account'],
  getKeyAccount:          ['Reading the key account',       'Read the key account'],
  listKeyAccountItems:    ['Listing the items of the key account', 'Listed the items of the key account'],
  updateKeyAccountFields: ['Updating the key account details', 'Updated the key account details'],
  proposeFact:            ['Proposing a fact',              'Proposed a fact'],
  proposeRating:          ['Proposing a rating',            'Proposed a rating'],
  proposeMove:            ['Proposing a move',              'Proposed a move'],
  addAction:              ['Adding an action to the plan',  'Added an action to the plan'],
  addEvidence:            ['Adding evidence',               'Added evidence'],
  upsertRisk:             ['Saving a risk',                 'Saved a risk'],
  upsertOpportunity:      ['Saving an opportunity',         'Saved an opportunity'],
  upsertStakeholder:      ['Saving a stakeholder',          'Saved a stakeholder'],
  importRevenue:          ['Importing revenue',             'Imported revenue'],
  draftReport:            ['Drafting the review report',    'Drafted the review report'],
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
  const words = splitToolName(name)
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
    if (typeof v === 'string' && v.trim() && !UUID.test(v.trim())) return truncate(v)
    // Pull-request and issue numbers read as numbers on their own; "#88" says which kind.
    if (typeof v === 'number') return key === 'prId' ? `#${v}` : String(v)
  }
  // Any other words the call was given - but never an id: a channel's, a video's, a comment's or a
  // record's id says nothing to the reader, and a row without a detail is the right outcome (the
  // maintainer, 2026-11-05: "Reviewed a YouTube viewer - UCgd8gFE4mpYTna3hX...").
  for (const [key, v] of Object.entries(safe)) {
    if (typeof v === 'string' && v.trim() && !ID_KEY.test(key) && !isOpaqueId(v.trim())) return truncate(v)
  }
  return undefined
}

// An argument that names an id by its key: id, ids, channelId, video_id, requestIds.
const ID_KEY = /(^ids?$|Ids?$|_ids?$)/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
// One word of letters, digits, "_" and "-", long enough and mixed enough to be a machine's: a
// YouTube channel id (UC + 22), a video id (11), a comment id (Ugx...), a hex or base64 token. A
// Jira-style key (PROJ-318) is a person's way of naming a thing, and stays.
const TOKEN = /^[A-Za-z0-9_-]{10,}$/
const ISSUE_KEY = /^[A-Z][A-Z0-9_]*-\d+$/

/** Whether a value is an id no reader would recognize. Exported for the tests. */
export function isOpaqueId(value: string): boolean {
  if (UUID.test(value)) return true
  if (!TOKEN.test(value) || ISSUE_KEY.test(value)) return false
  return /\d/.test(value) && /[A-Za-z]/.test(value)
}

// A SAVED AGENT'S WORKSPACE FILES (Constellations), told by what they hold and never by name: the
// person is told what a workspace file holds, never its name (the maintainer's rule). Read off the
// layout the server makes (WorkspacePaths, TeamKnowledge, SessionNotes, DashboardRules), in the
// agent's own folder or a teammate's, and in shared/. A file outside that layout is "a file".
type WorkspaceVerb = 'read' | 'write' | 'edit' | 'delete'
const WORKSPACE_VERBS: Record<string, WorkspaceVerb> = {
  readWorkspaceFile: 'read',
  writeWorkspaceFile: 'write',
  editWorkspaceFile: 'edit',
  deleteWorkspaceFile: 'delete',
}

export type WorkspaceFileKind =
  | 'memory' | 'next' | 'journal' | 'team' | 'voice' | 'decisions' | 'dashboard' | 'session' | 'notes' | 'file'

/** What a workspace file holds, from its path in the project. Exported for the tests. */
export function workspaceFileKind(path: string): WorkspaceFileKind {
  const p = path.trim().replace(/^\/+/, '').toLowerCase()
  if (!p) return 'file'
  if (p.startsWith('shared/')) {
    const s = p.slice('shared/'.length)
    if (s === 'knowledge/team.md') return 'team'
    if (s === 'knowledge/voice.md') return 'voice'
    if (s === 'knowledge/decisions.md') return 'decisions'
    if (/^dashboard\/[^/]+\.json$/.test(s)) return 'dashboard'
    return 'file'
  }
  if (p.startsWith('memory/')) return 'memory'
  if (p === 'next.md') return 'next'
  if (p === 'journal.md' || p.startsWith('journal/')) return 'journal'
  if (p.startsWith('sessions/')) return 'session'
  if (p.startsWith('scratchpad/')) return 'notes'
  // A bare name, as an older server's card sent it.
  if (p === 'memory.md') return 'memory'
  if (p === 'team.md') return 'team'
  if (p === 'voice.md') return 'voice'
  if (p === 'decisions.md') return 'decisions'
  return 'file'
}

// One whole phrase per file and tense, so each is translated as a sentence.
function workspaceLine(kind: Exclude<WorkspaceFileKind, 'file'>, verb: WorkspaceVerb, done: boolean, t: Translate): string {
  switch (`${kind}/${verb}`) {
    case 'memory/read': return done ? t('Read its memory') : t('Reading its memory')
    case 'memory/write': return done ? t('Saved to its memory') : t('Saving to its memory')
    case 'memory/edit': return done ? t('Updated its memory') : t('Updating its memory')
    case 'memory/delete': return done ? t('Removed a memory') : t('Removing a memory')
    case 'next/read': return done ? t('Read its next steps') : t('Reading its next steps')
    case 'next/write': return done ? t('Wrote its next steps') : t('Writing its next steps')
    case 'next/edit': return done ? t('Updated its next steps') : t('Updating its next steps')
    case 'next/delete': return done ? t('Cleared its next steps') : t('Clearing its next steps')
    case 'journal/read': return done ? t('Read its journal') : t('Reading its journal')
    case 'journal/write': return done ? t('Wrote its journal') : t('Writing its journal')
    case 'journal/edit': return done ? t('Edited its journal') : t('Editing its journal')
    case 'journal/delete': return done ? t('Deleted part of its journal') : t('Deleting part of its journal')
    case 'team/read': return done ? t("Read the team's knowledge") : t("Reading the team's knowledge")
    case 'team/write': return done ? t("Wrote the team's knowledge") : t("Writing the team's knowledge")
    case 'team/edit': return done ? t("Updated the team's knowledge") : t("Updating the team's knowledge")
    case 'team/delete': return done ? t("Cleared the team's knowledge") : t("Clearing the team's knowledge")
    case 'voice/read': return done ? t("Read the channel's voice") : t("Reading the channel's voice")
    case 'voice/write': return done ? t("Wrote down the channel's voice") : t("Writing down the channel's voice")
    case 'voice/edit': return done ? t("Updated the channel's voice") : t("Updating the channel's voice")
    case 'voice/delete': return done ? t("Cleared the channel's voice") : t("Clearing the channel's voice")
    case 'decisions/read': return done ? t('Read past decisions') : t('Reading past decisions')
    case 'decisions/write': return done ? t('Wrote down past decisions') : t('Writing down past decisions')
    case 'decisions/edit': return done ? t('Updated past decisions') : t('Updating past decisions')
    case 'decisions/delete': return done ? t('Cleared past decisions') : t('Clearing past decisions')
    case 'dashboard/read': return done ? t('Read a dashboard') : t('Reading a dashboard')
    case 'dashboard/write': return done ? t('Wrote a dashboard') : t('Writing a dashboard')
    case 'dashboard/edit': return done ? t('Updated a dashboard') : t('Updating a dashboard')
    case 'dashboard/delete': return done ? t('Deleted a dashboard') : t('Deleting a dashboard')
    case 'session/read': return done ? t('Read a past session') : t('Reading a past session')
    case 'session/write': return done ? t('Wrote down a session') : t('Writing down a session')
    case 'session/edit': return done ? t('Updated a past session') : t('Updating a past session')
    case 'session/delete': return done ? t('Deleted a past session') : t('Deleting a past session')
    case 'notes/read': return done ? t('Read its notes') : t('Reading its notes')
    case 'notes/write': return done ? t('Wrote in its notes') : t('Writing in its notes')
    case 'notes/edit': return done ? t('Edited its notes') : t('Editing its notes')
    default: return done ? t('Deleted a note') : t('Deleting a note')
  }
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
  // A stored delegation names itself by the short title it was given, so the row reads "Review SAI
  // project tasks" rather than a slice of the instruction behind it.
  if (tc.toolName === 'delegate' && typeof args.title === 'string' && args.title.trim()) {
    return truncate(args.title.trim())
  }
  // An agent deployed, or told something, names it: "Deploying an agent - Price check".
  if (tc.toolName === 'deployAgent' || tc.toolName === 'tellAgent') {
    const phrase = PHRASES[tc.toolName]
    const p = tc.done ? t(phrase[1]) : t(phrase[0])
    const detail = detailFrom(tc.toolName === 'deployAgent' ? { title: args.title } : { message: args.message })
    return detail ? `${p} - ${detail}` : p
  }
  // A teammate messaged by name: "Messaging Reply Writer".
  if (tc.toolName === 'messageAgent') {
    const name = typeof args.agent === 'string' ? truncate(args.agent) : ''
    if (!name) return tc.done ? t('Messaged a teammate') : t('Messaging a teammate')
    return tc.done ? t('Messaged {name}', { name }) : t('Messaging {name}', { name })
  }
  // A project's lead asked from the main chat, by the project's name. The call waits for the lead's
  // answer, so while it runs it is "Asking"; once done, the result's `answered` says whether the answer
  // is in it or follows as a message of its own. A result without the flag (written before it) says
  // only that the lead was messaged.
  if (tc.toolName === 'messageProjectLead') {
    const name = typeof args.project === 'string' ? truncate(args.project) : ''
    if (!tc.done) return name ? t('Asking the lead of {name}', { name }) : t('Asking a project lead')
    const answered = parse(tc.result).answered
    if (answered === true) return name ? t('The lead of {name} answered', { name }) : t('A project lead answered')
    if (answered === false) {
      return name ? t('Asked the lead of {name} - the answer follows', { name }) : t('Asked a project lead - the answer follows')
    }
    return name ? t('Messaged the lead of {name}', { name }) : t('Messaged a project lead')
  }
  // What an agent does to the agents it deployed, by the action it took.
  if (tc.toolName === 'controlAgent') {
    const action = typeof args.action === 'string' ? args.action : ''
    if (action === 'pause') return tc.done ? t('Paused an agent') : t('Pausing an agent')
    if (action === 'resume') return tc.done ? t('Resumed an agent') : t('Resuming an agent')
    if (action === 'stop') return tc.done ? t('Stopped an agent') : t('Stopping an agent')
    if (action === 'dismiss') return tc.done ? t('Dismissed an agent') : t('Dismissing an agent')
    return tc.done ? t('Checked its agents') : t('Checking its agents')
  }
  // An alarm set names its note; listing and canceling say so.
  if (tc.toolName === 'alarm') {
    const action = typeof args.action === 'string' ? args.action : 'set'
    if (action === 'list') return tc.done ? t('Checked its alarms') : t('Checking its alarms')
    if (action === 'cancel') return tc.done ? t('Canceled an alarm') : t('Canceling an alarm')
    const detail = detailFrom({ note: args.note })
    const p = tc.done ? t('Set an alarm') : t('Setting an alarm')
    return detail ? `${p} - ${detail}` : p
  }
  // Work left running in the background names the tool it runs.
  if (tc.toolName === 'background') {
    const action = typeof args.action === 'string' ? args.action : 'run'
    if (action === 'list') return tc.done ? t('Checked its background work') : t('Checking its background work')
    if (action === 'cancel') return tc.done ? t('Canceled background work') : t('Canceling background work')
    const p = tc.done ? t('Started background work') : t('Starting background work')
    return typeof args.tool === 'string' && args.tool ? `${p} - ${toolDisplayName(args.tool, t)}` : p
  }
  // A memory entry corrected or deleted by its id says so: "Remembered" would claim a new one.
  if (tc.toolName === 'remember' && typeof args.id === 'string' && args.id.trim()) {
    if (isTrue(args.forget)) return tc.done ? t('Forgot a memory') : t('Forgetting a memory')
    const detail = detailFrom({ fact: args.fact })
    const p = tc.done ? t('Updated memory') : t('Updating memory')
    return detail ? `${p} - ${detail}` : p
  }
  // Stored calls of two tools that no longer exist (before 2026-10-28): the agent directing work it
  // had handed off, and that work talking back.
  if (tc.toolName === 'steerSubAgent') {
    if (isTrue(args.stop)) return tc.done ? t('Stopped a sub-task') : t('Stopping a sub-task')
    const detail = detailFrom({ message: args.message })
    const p = tc.done ? t('Updated a sub-task') : t('Updating a sub-task')
    return detail ? `${p} - ${detail}` : p
  }
  if (tc.toolName === 'messageMainAgent') {
    const detail = detailFrom({ message: args.message })
    const p = isTrue(args.waitForReply)
      ? (tc.done ? t('Asked the main agent') : t('Asking the main agent'))
      : (tc.done ? t('Told the main agent') : t('Telling the main agent'))
    return detail ? `${p} - ${detail}` : p
  }
  // A saved agent's workspace file is described by what it holds - "Reading its memory" - never named:
  // its files are the agent's internals, not the person's (the maintainer, 2026-11-05). The path is
  // an address inside the project, read here and never shown.
  const workspaceVerb = WORKSPACE_VERBS[tc.toolName]
  if (workspaceVerb) {
    const at = typeof args.path === 'string' ? args.path : typeof args.fileName === 'string' ? args.fileName : ''
    const kind = workspaceFileKind(at)
    if (kind !== 'file') return workspaceLine(kind, workspaceVerb, tc.done, t)
    const phrase = PHRASES[tc.toolName]
    return tc.done ? t(phrase[1]) : t(phrase[0])
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
