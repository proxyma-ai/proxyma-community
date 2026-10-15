import { test } from 'node:test'
import assert from 'node:assert/strict'
import { restCallActivity, isRestPassthrough } from './restCallActivity.ts'

// English passes through with its {placeholders} filled, which is what the real runtime does for
// a key the catalog lacks - so these assert the English a reader sees.
const t = (s: string, p?: Record<string, string | number>) =>
  s.replace(/\{(\w+)\}/g, (_, k) => String(p?.[k] ?? `{${k}}`))

const line = (tool: string, method: string, path: string, body?: string) => {
  const a = restCallActivity(tool, { method, path, body }, t)
  if (!a) return undefined
  const join = (p: string) => !a.detail ? p : a.takesObject ? `${p} ${a.detail}` : `${p} - ${a.detail}`
  return [join(a.running), join(a.done)]
}

test('the report: a Bitbucket GET names what it lists, not the verb', () => {
  assert.deepEqual(
    line('callBitbucketApi', 'GET', '/rest/api/1.0/projects/KEY/repos/web/pull-requests?state=OPEN'),
    ['Listing pull requests - web', 'Listed pull requests - web'])
})

test('pull-request sub-resources name the pull request', () => {
  assert.equal(line('callBitbucketApi', 'GET', '/rest/api/1.0/projects/K/repos/r/pull-requests/42/activities')?.[0],
    'Reading the activity on pull request #42')
  assert.equal(line('callBitbucketApi', 'POST', '/rest/api/latest/projects/K/repos/r/pull-requests/7/decline')?.[1],
    'Declined pull request #7')
  assert.equal(line('callBitbucketApi', 'GET', '/rest/api/1.0/projects/K/repos/r/pull-requests/7')?.[0],
    'Opening pull request #7')
})

test('a repository file is named by its file name, never its whole path', () => {
  assert.equal(line('callBitbucketApi', 'GET', '/rest/api/1.0/projects/K/repos/r/browse/src/main/App.java?at=dev')?.[0],
    'Reading file - App.java')
})

test('a malformed percent sequence reads back as written instead of throwing', () => {
  assert.equal(line('callBitbucketApi', 'GET', '/rest/api/1.0/projects/P/repos/r/browse/docs/100%.md')?.[0],
    'Reading file - 100%.md')
  assert.equal(line('callBitbucketApi', 'GET', '/rest/api/1.0/projects/P/repos/r%zz/pull-requests')?.[0],
    'Listing pull requests - r%zz')
  assert.equal(line('callBitbucketApi', 'GET', '/rest/api/1.0/projects/P/repos/my%20repo/pull-requests')?.[0],
    'Listing pull requests - my repo')
  assert.equal(line('callJiraApi', 'GET', '/rest/api/2/issue/PROJ-3/odd%E0%A4')?.[0],
    'Reading odd%e0%a4 from Jira - PROJ-3')
})

test('build status lives outside /rest/api and still reads', () => {
  assert.equal(line('callBitbucketApi', 'GET', '/rest/build-status/1.0/commits/0123456789abcdef')?.[0],
    'Checking the build status of commit 01234567')
})

test('Jira: the issue key is the object', () => {
  assert.equal(line('callJiraApi', 'POST', '/rest/api/2/issue/PROJ-12/watchers')?.[0], 'Adding a watcher to PROJ-12')
  assert.equal(line('callJiraApi', 'DELETE', '/rest/api/2/issue/PROJ-12/watchers?username=a')?.[1], 'Removed a watcher from PROJ-12')
  assert.equal(line('callJiraApi', 'GET', '/rest/api/latest/issue/PROJ-12')?.[0], 'Opening PROJ-12')
})

test('Jira search quotes the JQL, from the query or the body', () => {
  assert.equal(line('callJiraApi', 'GET', '/rest/api/2/search?jql=project%3DPROJ')?.[0], 'Searching Jira issues - project=PROJ')
  assert.equal(line('callJiraApi', 'POST', '/rest/api/2/search', '{"jql":"assignee = me"}')?.[0], 'Searching Jira issues - assignee = me')
})

test('Jira Agile endpoints', () => {
  assert.equal(line('callJiraApi', 'GET', '/rest/agile/1.0/board/5/sprint')?.[0], 'Listing the sprints of board 5')
  assert.equal(line('callJiraApi', 'POST', '/rest/agile/1.0/sprint/9/issue')?.[0], 'Moving issues into sprint 9')
})

test('Confluence, including a Cloud /wiki prefix', () => {
  assert.equal(line('callConfluenceApi', 'POST', '/rest/api/content/12345/label')?.[0], 'Labeling page 12345')
  assert.equal(line('callConfluenceApi', 'GET', '/wiki/rest/api/content/12345/child/attachment')?.[0],
    'Listing the attachments of page 12345')
  assert.equal(line('callConfluenceApi', 'GET', '/rest/api/content/search?cql=space%3DDOCS')?.[0], 'Searching Confluence - space=DOCS')
})

test('an endpoint no rule names still reads as its resource, never as the HTTP verb', () => {
  assert.deepEqual(line('callJiraApi', 'GET', '/rest/api/2/issue/PROJ-3/properties'),
    ['Reading properties from Jira - PROJ-3', 'Read properties from Jira - PROJ-3'])
  assert.equal(line('callBitbucketApi', 'PUT', '/rest/default-reviewers/1.0/projects/K/repos/r/condition/4')?.[0],
    'Updating condition in Bitbucket - 4')
  assert.equal(line('callConfluenceApi', 'POST', '/rest/api/audit/retention')?.[0], 'Sending retention to Confluence')
  for (const l of [line('callBitbucketApi', 'DELETE', '/rest/api/1.0/x/y/z')!]) assert.doesNotMatch(l[0], /GET|DELETE/)
})

test('method case does not matter', () => {
  assert.equal(line('callBitbucketApi', 'get', '/rest/api/1.0/projects')?.[0], 'Listing Bitbucket projects')
})

test('not a passthrough, or no method or path: no phrase', () => {
  assert.equal(isRestPassthrough('getJiraIssue'), false)
  assert.equal(isRestPassthrough('callJiraApi'), true)
  assert.equal(line('getJiraIssue', 'GET', '/rest/api/2/issue/A-1'), undefined)
  assert.equal(restCallActivity('callJiraApi', { method: 'GET' }, t), undefined)
})
