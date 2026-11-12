import { test } from 'node:test'
import assert from 'node:assert/strict'
import { failedToolLabel, isOpaqueId, toolActivity, workspaceFileKind } from './toolActivity.ts'
import { toolDisplayName } from './toolDisplayName.ts'

// What a tool call's row says. These cases lived in toolListSummary.test.ts, which read them
// through the folded list's summary line until the 0.29.2 feedback opened the list and the line
// went; they are asked of the row directly now.

// English passes through with its {placeholders} filled and its plural form picked, which is what
// the real runtime does for a key the catalog lacks - so these assert the English a reader sees.
const t = (s: string, p?: Record<string, string | number>) => {
  const [one, other] = s.split('|')
  const form = other !== undefined && typeof p?.count === 'number' && p.count !== 1 ? other : one
  return form.replace(/\{(\w+)\}/g, (_, k) => String(p?.[k] ?? `{${k}}`))
}

const call = (toolName: string, args: Record<string, unknown>, result = '{}', done = true) =>
  ({ callId: toolName, toolName, arguments: JSON.stringify(args), result, done })

test('a search counts the distinct sources it found once done, and names its query while it runs', () => {
  const results = JSON.stringify({ results: [{ source: 'a.pdf' }, { source: 'b.pdf' }, { source: 'a.pdf' }] })
  assert.equal(toolActivity(call('searchIndexedSources', { query: 'trap handler open bugs' }, results), t), 'Searched 2 sources')
  assert.equal(toolActivity(call('searchIndexedSources', { query: 'trap handler open bugs' }, '', false), t),
    'Searching your sources - trap handler open bugs')
})

test('one item id in two sources is two documents found', () => {
  const results = JSON.stringify({ results: [
    { source: 'ticket:1', sourceId: 'zendesk' }, { source: 'ticket:1', sourceId: 'freshdesk' },
    { source: 'ticket:1', sourceId: 'zendesk' }] })
  assert.equal(toolActivity(call('searchIndexedSources', { query: 'refund' }, results), t), 'Searched 2 sources')
})

test('an aside follows a dash; a direct object follows the verb', () => {
  assert.equal(toolActivity(call('getJiraIssue', { issueKey: 'PROJ-318' }), t), 'Opened Jira issue - PROJ-318')
  assert.equal(toolActivity(call('addJiraComment', { issueKey: 'PROJ-9', body: 'x' }), t), 'Commented on PROJ-9')
})

test('the YouTube tools say what they do to the channel, and spell YouTube', () => {
  assert.equal(toolActivity(call('listYouTubeComments', {}, '{}', false), t), 'Reading YouTube comments')
  // The comment's id says nothing to the reader; the reply's own words do.
  assert.equal(toolActivity(call('replyToYouTubeComment', { commentId: 'Ugx123', text: 'A D5 dizi.' }), t),
    'Replied to a YouTube comment - A D5 dizi.')
  assert.equal(toolDisplayName('replyToYouTubeComment', t), 'Reply to a YouTube comment')
  // The channel's own replies, read to learn the owner's voice.
  assert.equal(toolActivity(call('listYouTubeChannelReplies', {}, '{}', false), t), 'Reading YouTube replies')
  assert.equal(toolActivity(call('listYouTubeChannelReplies', {}), t), 'Read YouTube replies')
})

test('a row never names an opaque id: a channel, a video, a comment, a request or a record', () => {
  // Compared with the row a call with no arguments gets, so the tool's own phrase is not what is tested.
  const bare = (toolName: string) => toolActivity(call(toolName, {}), t)
  // The maintainer's screenshot, 2026-11-05: a viewer looked up by channel id read "... - UCgd8gFE4mpYTna3hX...".
  assert.equal(toolActivity(call('getYouTubeViewer', { channelId: 'UCgd8gFE4mpYTna3hXq1abcd' }), t), bare('getYouTubeViewer'))
  assert.equal(toolActivity(call('getYouTubeViewer', { channel: 'UCgd8gFE4mpYTna3hXq1abcd' }), t), bare('getYouTubeViewer'))
  assert.equal(toolActivity(call('withdrawApproval', { requestId: '3f2a9c1e-8b7d-4e6f-9a0b-1c2d3e4f5a6b' }), t),
    bare('withdrawApproval'))
  assert.equal(toolActivity(call('getYouTubeVideo', { video: 'dQw4w9WgXcQ' }), t), bare('getYouTubeVideo'))
  assert.equal(toolActivity(call('useServer', { server: '3f2a9c1e-8b7d-4e6f-9a0b-1c2d3e4f5a6b' }), t), bare('useServer'))
  // What a person reads stays: a name, words, a Jira-style key.
  assert.equal(toolActivity(call('getYouTubeViewer', { channelId: 'UCgd8gFE4mpYTna3hXq1abcd', author: 'Minh Anh' }), t),
    `${bare('getYouTubeViewer')} - Minh Anh`)
  assert.equal(toolActivity(call('lookUpThing', { ref: 'PROJ-318' }), t), `${bare('lookUpThing')} - PROJ-318`)
  assert.equal(isOpaqueId('UCgd8gFE4mpYTna3hXq1abcd'), true)
  assert.equal(isOpaqueId('Ugx4k2Lq9ZaB3dE7fGhIjK4AaABAg'), true)
  assert.equal(isOpaqueId('3F2A9C1E-8B7D-4E6F-9A0B-1C2D3E4F5A6B'), true)
  assert.equal(isOpaqueId('PROJECTX-1234'), false)
  assert.equal(isOpaqueId('camamtieudao'), false)
  assert.equal(isOpaqueId('D5 dizi 2026'), false)
})

test('the YouTube numbers tools have their own rows, running and done', () => {
  assert.equal(toolActivity(call('getYouTubeChannelStats', {}, '{}', false), t), 'Reading YouTube channel stats')
  assert.equal(toolActivity(call('getYouTubeChannelStats', {}), t), 'Read YouTube channel stats')
  assert.equal(toolActivity(call('listYouTubeVideos', {}), t), 'Listed YouTube videos')
  assert.equal(toolActivity(call('getYouTubeAnalytics', {}, '{}', false), t), 'Reading YouTube Analytics')
  assert.equal(toolActivity(call('getYouTubeReach', {}), t), 'Read YouTube impressions')
})

test('an agent card reads the subject the server sends: short, and named by what it works on', () => {
  // The card passes the server's subject (TurnActivity.subjectOf) as the arguments of a running call.
  const running = (toolName: string, subject: Record<string, unknown>) => toolActivity(call(toolName, subject, '', false), t)
  assert.equal(running('searchIndexedSources', { query: 'dizi keys' }), 'Searching your sources - dizi keys')
  assert.equal(running('webSearch', { query: 'D5 dizi price' }), 'Searching the web - D5 dizi price')
  assert.equal(running('read', { fileName: 'Bay loi hay gap.md' }), 'Reading file - Bay loi hay gap.md')
  assert.equal(running('replyToYouTubeComment', {}), 'Replying to a YouTube comment')
  assert.equal(running('listYouTubeComments', {}), 'Reading YouTube comments')
  assert.equal(running('remember', { fact: 'Calls viewers ban' }), 'Remembering Calls viewers ban')
})

test('the tools a Constellations agent has of its own read as sentences, not bare names', () => {
  assert.equal(toolActivity(call('messageAgent', { agent: 'Reply Writer', message: 'x' }, '', false), t), 'Messaging Reply Writer')
  assert.equal(toolActivity(call('messageAgent', { agent: 'Reply Writer' }), t), 'Messaged Reply Writer')
  assert.equal(toolActivity(call('messageAgent', {}, '', false), t), 'Messaging a teammate')
  // A lead's words to the person from a run, which land in the person's lead chat.
  assert.equal(toolActivity(call('tellPerson', { text: 'Needs your decision.' }, '', false), t).startsWith('Messaging you'), true)
  assert.equal(toolActivity(call('tellPerson', { text: 'Needs your decision.' }), t).startsWith('Messaged you'), true)
  // The call waits for the lead's answer: "Asking" while it runs, then whether the answer came in it.
  assert.equal(toolActivity(call('messageProjectLead', { project: 'Channel care', message: 'x' }, '', false), t), 'Asking the lead of Channel care')
  assert.equal(toolActivity(call('messageProjectLead', { project: 'Channel care' },
    JSON.stringify({ success: true, answered: true, result: 'x' })), t), 'The lead of Channel care answered')
  assert.equal(toolActivity(call('messageProjectLead', { project: 'Channel care' },
    JSON.stringify({ success: true, answered: false, result: 'x' })), t), 'Asked the lead of Channel care - the answer follows')
  assert.equal(toolActivity(call('messageProjectLead', { project: 'Channel care' }), t), 'Messaged the lead of Channel care')
  assert.equal(toolActivity(call('messageProjectLead', {}, '', false), t), 'Asking a project lead')
  assert.equal(toolActivity(call('messageProjectLead', {}, JSON.stringify({ answered: false })), t), 'Asked a project lead - the answer follows')
  // A card's subject from an older server named the file alone: still said by what it holds.
  assert.equal(toolActivity(call('readWorkspaceFile', { fileName: 'MEMORY.md' }, '', false), t), 'Reading its memory')
  assert.equal(toolActivity(call('writeWorkspaceFile', { fileName: 'NEXT.md' }, '', false), t), 'Writing its next steps')
  assert.equal(toolActivity(call('getTeamStatus', {}, '', false), t), 'Checking on its team')
  assert.equal(toolActivity(call('linkTeamAgents', {}, '', true), t), 'Linked agents')
  assert.equal(toolActivity(call('appendJournal', {}, '', false), t), 'Writing in its journal')
  assert.equal(toolActivity(call('postClaim', {}, '', false), t), 'Posting a finding to the board')
})

test('a stored delegation is named by the title the agent gave it', () => {
  assert.equal(toolActivity(call('delegate', { title: 'SAI project review' }), t), 'SAI project review')
})

test('deploying an agent and telling it something name it', () => {
  assert.equal(toolActivity(call('deployAgent', { title: 'Price check', task: 'x', lifetime: 'SHORT' }, '{}', false), t), 'Deploying an agent - Price check')
  assert.equal(toolActivity(call('deployAgent', { title: 'Price check' }), t), 'Deployed an agent - Price check')
  assert.equal(toolActivity(call('tellAgent', { to: 'a1', message: 'Prices in EUR' }), t), 'Messaged an agent - Prices in EUR')
  assert.equal(toolActivity(call('awaitAgents', { agentIds: ['a1'] }, '{}', false), t), 'Waiting for its agents')
})

test('controlling an agent says what was done to it', () => {
  assert.equal(toolActivity(call('controlAgent', { action: 'pause', agentId: 'a1' }), t), 'Paused an agent')
  assert.equal(toolActivity(call('controlAgent', { action: 'stop', agentId: 'a1' }, '{}', false), t), 'Stopping an agent')
  assert.equal(toolActivity(call('controlAgent', { action: 'list' }), t), 'Checked its agents')
})

test('alarms and background work say what they did', () => {
  assert.equal(toolActivity(call('alarm', { action: 'set', inMinutes: 30, note: 'Check the build' }), t), 'Set an alarm - Check the build')
  assert.equal(toolActivity(call('alarm', { action: 'cancel', alarmId: 'x' }), t), 'Canceled an alarm')
  assert.equal(toolActivity(call('background', { action: 'run', tool: 'searchJiraIssues', arguments: '{}' }), t), 'Started background work - Search Jira issues')
  assert.equal(toolActivity(call('background', { action: 'list' }, '{}', false), t), 'Checking its background work')
})

test('a memory entry corrected or deleted by its id is not called remembered', () => {
  assert.equal(toolActivity(call('remember', { fact: 'Ships on Tuesdays' }), t), 'Remembered Ships on Tuesdays')
  assert.equal(toolActivity(call('remember', { id: 'e1', fact: 'Ships on Tuesdays' }), t), 'Updated memory - Ships on Tuesdays')
  assert.equal(toolActivity(call('remember', { id: 'e1', forget: true }), t), 'Forgot a memory')
  assert.equal(toolActivity(call('remember', { id: 'e1', forget: 'true' }), t), 'Forgot a memory')
})

test('a stored steer of handed-off work says what it was told, or that it was stopped', () => {
  assert.equal(toolActivity(call('steerSubAgent', { agentId: 'a1', message: 'Prices in EUR' }), t), 'Updated a sub-task - Prices in EUR')
  assert.equal(toolActivity(call('steerSubAgent', { agentId: 'a1', stop: true }), t), 'Stopped a sub-task')
})

test('stored handed-off work asks the main agent, or tells it and carries on', () => {
  assert.equal(toolActivity(call('messageMainAgent', { message: 'EUR or USD?', waitForReply: true }), t), 'Asked the main agent - EUR or USD?')
  assert.equal(toolActivity(call('messageMainAgent', { message: 'EUR or USD?', waitForReply: true }, '{}', false), t), 'Asking the main agent - EUR or USD?')
  assert.equal(toolActivity(call('messageMainAgent', { message: 'Prices are in EUR' }), t), 'Told the main agent - Prices are in EUR')
})

test('a REST passthrough says what its method and path do', () => {
  assert.equal(toolActivity(call('callJiraApi', { method: 'PUT', path: '/rest/api/2/issue/PROJ-1/assignee' }), t), 'Assigned PROJ-1')
})

test('a failed call keeps the tool name and claims nothing was done', () => {
  const failed = call('createJiraIssue', { summary: 'x' }, JSON.stringify({ success: false, error: 'denied' }))
  assert.equal(failedToolLabel(failed, t), 'Create a Jira issue')
})

test('display names are written, sentence case, with product names kept', () => {
  assert.equal(toolDisplayName('searchJiraIssues', t), 'Search Jira issues')
  assert.equal(toolDisplayName('searchIndexedSources', t), 'Search your sources')
  assert.equal(toolDisplayName('getBitbucketPr', t), 'Open a Bitbucket pull request')
  assert.equal(toolDisplayName('delegate', t), 'Agent')
  assert.equal(toolDisplayName('awaitAgents', t), 'Wait for agents')
  assert.equal(toolDisplayName('deployAgent', t), 'Deploy an agent')
  // A name no entry has - none the backend offers (toolNames.test.ts) - is still words, never code.
  assert.equal(toolDisplayName('read_text_file', t), 'Read text file')
})

test('a connected MCP server tool names its server, then the tool as a phrase', () => {
  assert.equal(toolActivity(call('mcp__fake_http_812f__echo', { text: 'hi' }), t), 'fake http: Echo - hi')
  assert.equal(toolActivity(call('mcp__tickets_ab12__search_issues', { query: 'late' }, '', false), t),
    'tickets: Searching issues - late')
  assert.equal(toolDisplayName('mcp__tickets_ab12__create_ticket', t), 'tickets: Create ticket')
  // Not an MCP tool: its own title.
  assert.equal(toolDisplayName('searchIndexedSources', t), 'Search your sources')
})

test('the graph tools say what they look up', () => {
  assert.equal(toolActivity(call('findGraphPath', { from: 'Ana', to: 'Ledger' }), t), 'Traced connections - Ana and Ledger')
  assert.equal(toolActivity(call('findGraphPeople', { name: 'Ledger' }, '', false), t), 'Finding who works on Ledger')
})

test('the timeline and themes tools say what they read', () => {
  assert.equal(toolActivity(call('findGraphTimeline', { name: 'PAY-12' }), t), 'Read the history of PAY-12')
  assert.equal(toolActivity(call('findGraphThemes', {}, '', false), t), 'Finding the main themes')
})

test('a saved agent\'s workspace file is told by what it holds, never by its name', () => {
  const row = (tool: string, path: string, done = true) => toolActivity(call(tool, { path, content: 'x' }, '{}', done), t)
  assert.equal(row('readWorkspaceFile', 'memory/channel-rules.md'), 'Read its memory')
  assert.equal(row('readWorkspaceFile', 'memory/channel-rules.md', false), 'Reading its memory')
  assert.equal(row('writeWorkspaceFile', 'memory/viewers.md'), 'Saved to its memory')
  assert.equal(row('deleteWorkspaceFile', 'memory/old.md'), 'Removed a memory')
  assert.equal(row('editWorkspaceFile', 'NEXT.md'), 'Updated its next steps')
  assert.equal(row('readWorkspaceFile', 'JOURNAL.md'), 'Read its journal')
  assert.equal(row('readWorkspaceFile', 'journal/2026-10.md'), 'Read its journal')
  assert.equal(row('readWorkspaceFile', 'shared/knowledge/TEAM.md'), "Read the team's knowledge")
  assert.equal(row('editWorkspaceFile', 'shared/knowledge/VOICE.md', false), "Updating the channel's voice")
  assert.equal(row('readWorkspaceFile', 'shared/knowledge/decisions.md'), 'Read past decisions')
  assert.equal(row('writeWorkspaceFile', 'shared/dashboard/viewers.json'), 'Wrote a dashboard')
  assert.equal(row('readWorkspaceFile', 'sessions/2026-11-04-1.md'), 'Read a past session')
  assert.equal(row('writeWorkspaceFile', 'scratchpad/draft.md'), 'Wrote in its notes')
  assert.equal(row('deleteWorkspaceFile', 'scratchpad/draft.md'), 'Deleted a note')
  // Anything else is "a file" - still never its name.
  assert.equal(row('readWorkspaceFile', 'shared/for-the-person.md'), 'Read a file')
  assert.equal(row('writeWorkspaceFile', 'plans.md', false), 'Writing a file')
  assert.equal(toolActivity(call('readWorkspaceFile', {}, '{}'), t), 'Read a file')
  for (const path of ['memory/x.md', 'NEXT.md', 'shared/knowledge/TEAM.md', 'plans.md']) {
    assert.ok(!row('readWorkspaceFile', path).includes(path.split('/').pop()!), path)
  }
})

test('which workspace file is which, by its place in the layout', () => {
  assert.equal(workspaceFileKind('memory/a.md'), 'memory')
  assert.equal(workspaceFileKind('/memory/a.md'), 'memory')
  assert.equal(workspaceFileKind('knowledge/TEAM.md'), 'file')
  assert.equal(workspaceFileKind('shared/memory/a.md'), 'file')
  assert.equal(workspaceFileKind('shared/dashboard/a/b.json'), 'file')
  assert.equal(workspaceFileKind(''), 'file')
})

test('the person\'s own files keep their names', () => {
  // read and grep work in the person's conversation folder; a card sends the file's name.
  assert.equal(toolActivity(call('read', { fileName: 'Bay loi hay gap.md' }, '', false), t), 'Reading file - Bay loi hay gap.md')
})
