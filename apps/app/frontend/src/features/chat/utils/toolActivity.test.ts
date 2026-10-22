import { test } from 'node:test'
import assert from 'node:assert/strict'
import { failedToolLabel, toolActivity } from './toolActivity.ts'
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

test('a delegation is named by the title the agent gave it', () => {
  assert.equal(toolActivity(call('delegate', { title: 'SAI project review' }), t), 'SAI project review')
})

test('a memory entry corrected or deleted by its id is not called remembered', () => {
  assert.equal(toolActivity(call('remember', { fact: 'Ships on Tuesdays' }), t), 'Remembered Ships on Tuesdays')
  assert.equal(toolActivity(call('remember', { id: 'e1', fact: 'Ships on Tuesdays' }), t), 'Updated memory - Ships on Tuesdays')
  assert.equal(toolActivity(call('remember', { id: 'e1', forget: true }), t), 'Forgot a memory')
  assert.equal(toolActivity(call('remember', { id: 'e1', forget: 'true' }), t), 'Forgot a memory')
})

test('directing a sub-agent says what it was told, or that it was stopped', () => {
  assert.equal(toolActivity(call('steerSubAgent', { agentId: 'a1', message: 'Prices in EUR' }), t), 'Updated a sub-task - Prices in EUR')
  assert.equal(toolActivity(call('steerSubAgent', { agentId: 'a1', stop: true }), t), 'Stopped a sub-task')
})

test('a sub-agent asks the main agent, or tells it and carries on', () => {
  assert.equal(toolActivity(call('messageMainAgent', { message: 'EUR or USD?', waitForReply: true }), t), 'Asked the main agent - EUR or USD?')
  assert.equal(toolActivity(call('messageMainAgent', { message: 'EUR or USD?', waitForReply: true }, '{}', false), t), 'Asking the main agent - EUR or USD?')
  assert.equal(toolActivity(call('messageMainAgent', { message: 'Prices are in EUR' }), t), 'Told the main agent - Prices are in EUR')
})

test('a REST passthrough says what its method and path do', () => {
  assert.equal(toolActivity(call('callJiraApi', { method: 'PUT', path: '/rest/api/2/issue/PROJ-1/assignee' }), t), 'Assigned PROJ-1')
})

test('a failed call keeps the tool name and claims nothing was done', () => {
  const failed = call('createJiraIssue', { summary: 'x' }, JSON.stringify({ success: false, error: 'denied' }))
  assert.equal(failedToolLabel(failed, t), 'Create Jira issue')
})

test('display names are sentence case, with product names kept', () => {
  assert.equal(toolDisplayName('searchJiraIssues'), 'Search Jira issues')
  assert.equal(toolDisplayName('searchIndexedSources'), 'Search indexed sources')
  assert.equal(toolDisplayName('read_text_file'), 'Read text file')
  assert.equal(toolDisplayName('getBitbucketPr'), 'Get Bitbucket pull request')
  assert.equal(toolDisplayName('delegate'), 'Agent')
})

test('a connected MCP server tool names its server, then the tool as a phrase', () => {
  assert.equal(toolActivity(call('mcp__fake_http_812f__echo', { text: 'hi' }), t), 'fake http: Echo - hi')
  assert.equal(toolActivity(call('mcp__tickets_ab12__search_issues', { query: 'late' }, '', false), t),
    'tickets: Searching issues - late')
  assert.equal(toolDisplayName('mcp__tickets_ab12__create_ticket'), 'tickets: Create ticket')
  // Not an MCP tool: unchanged.
  assert.equal(toolDisplayName('searchIndexedSources'), 'Search indexed sources')
})

test('the graph tools say what they look up', () => {
  assert.equal(toolActivity(call('findGraphPath', { from: 'Ana', to: 'Ledger' }), t), 'Traced connections - Ana and Ledger')
  assert.equal(toolActivity(call('findGraphPeople', { name: 'Ledger' }, '', false), t), 'Finding who works on Ledger')
})

test('the timeline and themes tools say what they read', () => {
  assert.equal(toolActivity(call('findGraphTimeline', { name: 'PAY-12' }), t), 'Read the history of PAY-12')
  assert.equal(toolActivity(call('findGraphThemes', {}, '', false), t), 'Finding the main themes')
})
