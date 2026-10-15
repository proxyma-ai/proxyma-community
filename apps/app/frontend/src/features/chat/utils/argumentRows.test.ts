import { test } from 'node:test'
import assert from 'node:assert/strict'
import { argumentLabel, argumentRows } from './argumentRows.ts'

test('the review: a comment body is a readable row, not an escaped string', () => {
  const rows = argumentRows(JSON.stringify({ issueKey: 'PROJ-341', body: 'Linked to H-14.\nSee 5.3.' }))
  assert.deepEqual(rows, [
    { key: 'issueKey', label: 'Issue key', value: 'PROJ-341', block: false },
    { key: 'body', label: 'Body', value: 'Linked to H-14.\nSee 5.3.', block: true },
  ])
})

test('a server path never becomes a row', () => {
  const rows = argumentRows(JSON.stringify({ filePath: 'C:\\Users\\me\\x.pdf', query: 'x' }))
  assert.deepEqual(rows?.map(r => r.key), ['query'])
})

test('a structure is lines of fields, never JSON; empty values are left out', () => {
  const rows = argumentRows(JSON.stringify({
    fields: { issueKey: 'A-1', labels: ['cloud', 'infra'], parent: { key: 'A-0' }, blank: '' },
    watchers: [{ name: 'An' }, { name: 'Binh', role: 'lead' }],
    nothing: {}, empty: '', none: null, n: 0, flag: false,
  }))
  assert.deepEqual(rows?.map(r => [r.key, r.value, r.block]), [
    ['fields', 'Issue key: A-1\nLabels:\n  cloud\n  infra\nParent:\n  Key: A-0', true],
    ['watchers', '- Name: An\n- Name: Binh\n  Role: lead', true],
    ['n', '0', false],
    ['flag', 'false', false],
  ])
})

test('anything that is not a JSON object is left to the raw view', () => {
  assert.equal(argumentRows('not json'), null)
  assert.equal(argumentRows('[1,2]'), null)
  assert.deepEqual(argumentRows(''), [])
})

test('labels are sentence case, acronyms and product names kept', () => {
  assert.equal(argumentLabel('jql'), 'JQL')
  assert.equal(argumentLabel('repoSlug'), 'Repository slug')
  assert.equal(argumentLabel('pageId'), 'Page ID')
  assert.equal(argumentLabel('jiraProjectKey'), 'Jira project key')
  assert.equal(argumentLabel('max_results'), 'Max results')
})
