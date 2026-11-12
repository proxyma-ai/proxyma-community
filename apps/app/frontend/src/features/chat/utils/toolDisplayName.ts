// How a tool's backend name is rendered anywhere the user sees it: the tool row in the thread,
// the detail modal's title, the enable/consent prompt, an approval, the logs. Extracted here because
// the identical toPascalCase helper was copy-pasted into all three - which meant a display name
// added to one of them would silently disagree with the other two (a row saying one thing while the
// modal it opens said another).

// Every tool the product offers, by the name a person reads (2026-11-05, the maintainer: every word
// a person reads is in their language). Keyed by the exact backend tool name, sentence case, said
// the way a person would say it - each one a catalog key, translated in catalogs/vi.ts.
//
// EVERY TOOL HAS ONE. toolNames.test.ts holds every name the backend can offer - the generated
// i18n/toolNames.generated.ts lists, read out of the Java source by tools/i18n/tool-names.mjs - to an
// entry here and in toolActivity's PHRASES, so a tool added on the server fails the pipeline until it
// is named. What humanize() derives from a code name can never be translated, so it is left to the
// tools of a connected MCP server, whose names are that server's own.
//
// A title is never spelled like one of PHRASES' phrases: one English key carries one translation,
// and "Read a file" there is a finished call where a title is an instruction. That is why several
// titles below say "Open" or name their object more fully.
export const TOOL_TITLES: Record<string, string> = {
  // Searching, reading and remembering.
  searchIndexedSources:   'Search your sources',
  searchConversations:    'Search earlier conversations',
  searchKnowledgeGraph:   'Search the knowledge graph',
  findGraphPath:          'Trace connections',
  findGraphPeople:        'Find who works on something',
  findGraphTimeline:      'Find the history of something',
  findGraphThemes:        'Find the main themes',
  recall:                 'Recall memory',
  remember:               'Remember a fact',
  readSkill:              'Load a skill',
  askUser:                'Ask you a question',
  webSearch:              'Search the web',
  webFetch:               'Read a web page',
  renderWebFetch:         'Open a page in a browser',
  // An offloaded tool result, read back or searched line by line.
  read:                   'Read a saved result',
  grep:                   'Search a saved result',
  convertDocument:        'Convert a document',
  runPython:              'Run Python on a file',
  runCommand:             'Run a command',
  openLocalFile:          'Open a file from your computer',
  saveLocalFile:          'Save a file to your computer',
  getPlatformInfo:        'Check this deployment',
  useServer:              'Switch server',

  // Sources.
  getConnectorStatus:     'Check your sources',
  listSourceTypes:        'List the types of source',
  addSource:              'Add a source',
  removeSource:           'Remove a source',
  readSourceItem:         'Open an item of a source',
  saveToSource:           'Save a note to a source',

  // The tree of agents. "Await" is the mechanism; waiting is what the reader sees.
  deployAgent:            'Deploy an agent',
  deployAgents:           'Agents over a list',
  tellAgent:              'Message an agent',
  awaitAgents:            'Wait for agents',
  controlAgent:           'Direct its agents',
  readAgentReport:        'Read the report of an agent',
  verifyReport:           'Have a report checked',
  deliberate:             'Panel',
  recordDecision:         'Record a decision',
  handFile:               'Hand on a file',
  // Not "Set an alarm": that is the finished row of a call, and one key has one translation.
  alarm:                  'Alarm',
  background:             'Work in the background',
  // Tools that no longer exist, in conversations stored before 2026-10-28.
  delegate:               'Agent',
  steerSubAgent:          'Direct agent',
  messageMainAgent:       'Message main agent',
  convert:                'Convert a file',

  // Jira. The REST passthroughs: "Call Jira API" names the mechanism; the modal it titles shows the
  // method and path underneath, so the title only has to say whose API it was.
  callJiraApi:            'Jira request',
  searchJiraIssues:       'Search Jira issues',
  getJiraIssue:           'Open a Jira issue',
  createJiraIssue:        'Create a Jira issue',
  updateJiraIssue:        'Update a Jira issue',
  deleteJiraIssue:        'Delete a Jira issue',
  transitionJiraIssue:    'Change the status of a Jira issue',
  addJiraComment:         'Comment on a Jira issue',
  updateJiraComment:      'Edit a Jira comment',
  deleteJiraComment:      'Delete a Jira comment',
  attachJiraFile:         'Attach a file to a Jira issue',
  logJiraWork:            'Log work on a Jira issue',
  updateJiraEstimate:     'Update a Jira estimate',
  linkJiraIssues:         'Link Jira issues',
  deleteJiraIssueLink:    'Remove a link between Jira issues',
  getJiraCurrentUser:     'Check your Jira account',
  listJiraProjects:       'List Jira projects',
  listJiraIssueTypes:     'List Jira issue types',
  listJiraFields:         'List Jira fields',
  listJiraVersions:       'List Jira versions',
  createJiraVersion:      'Create a Jira version',

  // Confluence.
  callConfluenceApi:      'Confluence request',
  searchConfluencePages:  'Search Confluence pages',
  getConfluencePage:      'Open a Confluence page',
  createConfluencePage:   'Create a Confluence page',
  updateConfluencePage:   'Update a Confluence page',
  deleteConfluencePage:   'Delete a Confluence page',
  moveConfluencePage:     'Move a Confluence page',
  addConfluenceComment:   'Comment on a Confluence page',
  updateConfluenceComment: 'Edit a Confluence comment',
  deleteConfluenceComment: 'Delete a Confluence comment',
  attachConfluenceFile:   'Attach a file to a Confluence page',
  updateConfluenceLabels: 'Label a Confluence page',
  listConfluenceSpaces:   'List Confluence spaces',

  // Bitbucket.
  callBitbucketApi:       'Bitbucket request',
  getBitbucketPr:         'Open a Bitbucket pull request',
  getBitbucketCloudPr:    'Open a Bitbucket Cloud pull request',
  getBitbucketPrDiff:     'Read the diff of a pull request',
  createBitbucketPr:      'Create a pull request',
  addBitbucketPrComment:  'Comment on a pull request',
  reviewBitbucketPr:      'Review a pull request',
  mergeBitbucketPr:       'Merge a pull request',
  getBitbucketCommits:    'Read the commit history',
  getBitbucketCommitDiff: 'Read the diff of a commit',
  listBitbucketBranches:  'List Bitbucket branches',
  createBitbucketBranch:  'Create a Bitbucket branch',
  deleteBitbucketBranch:  'Delete a Bitbucket branch',
  listBitbucketTags:      'List Bitbucket tags',
  createBitbucketTag:     'Create a Bitbucket tag',
  listBitbucketFiles:     'List the files of a repository',
  browseBitbucketPath:    'Browse a Bitbucket repository',
  readBitbucketCloudFile: 'Open a Bitbucket Cloud file',
  downloadBitbucketRepo:  'Download a Bitbucket repository',
  deleteBitbucketRepo:    'Delete a downloaded repository',

  // SVN.
  listSvnFiles:           'List SVN files',
  readSvnFile:            'Open an SVN file',

  // YouTube.
  listYouTubeComments:    'List YouTube comments',
  replyToYouTubeComment:  'Reply to a YouTube comment',
  listWaitingYouTubeReplies: 'List YouTube replies waiting for approval',
  listYouTubeChannelReplies: 'List the YouTube replies of the channel',
  getYouTubeViewerHistory: 'Look up a YouTube viewer',
  getYouTubeVideo:        'Open a YouTube video',
  listYouTubeVideos:      'List YouTube videos',
  getYouTubeChannelStats: 'Get YouTube channel stats',
  getYouTubeAnalytics:    'Get a YouTube Analytics report',
  getYouTubeReach:        'Get YouTube impressions',

  // Key accounts (Enterprise Server).
  listKeyAccounts:        'List key accounts',
  openKeyAccount:         'Choose a key account',
  getKeyAccount:          'Open the key account',
  listKeyAccountItems:    'List the items of a key account',
  updateKeyAccountFields: 'Update the key account details',
  proposeFact:            'Propose a fact',
  proposeRating:          'Propose a rating',
  proposeMove:            'Propose a move',
  addAction:              'Add an action to the plan',
  addEvidence:            'Add evidence',
  upsertRisk:             'Save a risk',
  upsertOpportunity:      'Save an opportunity',
  upsertStakeholder:      'Save a stakeholder',
  importRevenue:          'Import revenue',
  draftReport:            'Draft the review report',

  // Constellations: the team, its workspace, the board and the approvals.
  getTeamStatus:          'Check on the team',
  messageAgent:           'Message a teammate',
  messageProjectLead:     'Ask a project lead',
  tellPerson:             'Message you',
  describeTeam:           'Describe the team',
  addTeamAgent:           'Add an agent to the team',
  updateTeamAgent:        'Change an agent',
  removeTeamAgent:        'Remove an agent from the team',
  linkTeamAgents:         'Link agents',
  scheduleTeamAgent:      'Schedule an agent',
  saveMemory:             'Save a memory',
  rememberForTeam:        'Note for the team',
  listWorkspace:          'List workspace files',
  readWorkspaceFile:      'Read a workspace file',
  writeWorkspaceFile:     'Write a workspace file',
  editWorkspaceFile:      'Edit a workspace file',
  deleteWorkspaceFile:    'Delete a workspace file',
  appendJournal:          'Write in the journal',
  postClaim:              'Post a finding to the board',
  listClaims:             'List the findings on the board',
  updateClaim:            'Update a finding',
  listWaitingApprovals:   'List writes waiting for approval',
  withdrawApproval:       'Withdraw writes',
  // The comment queue a YouTube team works from (2026-11-05).
  nextComments:           'Take new comments to sort',
  decideComments:         'Sort comments',
  nextAnswers:            'Take comments to answer',
  raiseOpenPoint:         'Ask you a question',
  listOpenPoints:         'List the questions for you',
  dropComments:           'Close comments that get no reply',
  // The lead handling the person's questions as told, from the lead chat (2026-11-07).
  findQuestions:          'Find your questions',
  settleQuestions:        'Answer or withdraw questions',
  editQuestion:           'Reword or merge a question',
}

// Words a derived name must not lower-case, or abbreviations nobody says out loud. Shared with
// toolActivity's derive(), so a row and the title of the modal it opens spell a product the same way.
export const NOUNS: Record<string, string> = {
  jira: 'Jira',
  confluence: 'Confluence',
  bitbucket: 'Bitbucket',
  sharepoint: 'SharePoint',
  svn: 'SVN',
  teams: 'Teams',
  youtube: 'YouTube',
  url: 'URL',
  pr: 'pull request',
  prs: 'pull requests',
  repo: 'repository',
  repos: 'repositories',
}

// A tool's camelCase name as words. A brand with a capital inside it (YouTube) is one word, or the split
// would make it two ("you tube") that NOUNS can never spell back. Shared with toolActivity's derive().
export function splitToolName(name: string): string[] {
  return name.replace(/YouTube/g, 'Youtube').replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/[\s_-]+/).filter(Boolean)
}

// search_issues -> "Search issues". SENTENCE CASE since 2026-09-30, like every other title in the
// app. Only for what TOOL_TITLES cannot name: a connected MCP server's tool, whose name is that
// server's own and reads as the server wrote it, and a tool the backend no longer offers.
function humanize(name: string): string {
  const words = splitToolName(name)
  const text = words.map(w => NOUNS[w.toLowerCase()] ?? w.toLowerCase()).join(' ')
  return text.charAt(0).toUpperCase() + text.slice(1)
}

// Never a tool's code name: a product tool reads its translated title, an MCP server's tool its
// server and its own name as words.
export function toolDisplayName(name: string, t: (s: string) => string): string {
  const title = TOOL_TITLES[name]
  if (title) return t(title)
  const mcp = mcpToolParts(name)
  if (mcp) return `${mcp.server}: ${humanize(mcp.tool)}`
  return humanize(name)
}

/**
 * A tool of a connected MCP server, as the backend advertises it (McpToolNames):
 * `mcp__<server slug>_<4 hex of the connection id>__<tool>`. The server is given back as the words
 * of its slug - the connection's name, lower-cased - and the tool as the server named it. Null for
 * any other tool.
 */
export function mcpToolParts(name: string): { server: string; tool: string } | null {
  const m = /^mcp__(.+?)__(.+)$/.exec(name)
  if (!m) return null
  const server = m[1].replace(/_[0-9a-f]{4}$/, '').split(/[_-]+/).filter(Boolean).join(' ')
  return { server: server || 'MCP', tool: m[2] }
}
