import { test } from 'node:test'
import assert from 'node:assert/strict'
import { balanceColumns, filterGroups, matchesQuery, normalizeSearch, switchedOffTypes } from './connectMenu.ts'

interface T { type: string; noun: string }
const groups = [
  { label: 'Files', types: [{ type: 'files', noun: 'Folder' }, { type: 'webpage', noun: 'Web page' }] },
  { label: 'Code', types: [{ type: 'github', noun: 'GitHub repository' }, { type: 'gitlab', noun: 'GitLab group' }, { type: 'bitbucketcloud', noun: 'Bitbucket Cloud repository' }] },
  { label: 'Tickets and tasks', types: [{ type: 'jira', noun: 'Jira project' }] },
]
const names = (t: T) => [t.noun, t.type]
const types = (gs: { types: T[] }[]) => gs.flatMap(g => g.types.map(t => t.type))

test('an empty query keeps every group as it was', () => {
  assert.deepEqual(types(filterGroups(groups, '  ', names)), ['files', 'webpage', 'github', 'gitlab', 'bitbucketcloud', 'jira'])
})

test('a query finds a type by the start of any word of its names', () => {
  assert.deepEqual(types(filterGroups(groups, 'git', names)), ['github', 'gitlab'])
  assert.deepEqual(types(filterGroups(groups, 'cloud', names)), ['bitbucketcloud'])
  assert.deepEqual(types(filterGroups(groups, 'hub', names)), [])
})

test('every word of the query has to match', () => {
  assert.deepEqual(types(filterGroups(groups, 'bit repo', names)), ['bitbucketcloud'])
  assert.deepEqual(types(filterGroups(groups, 'git cloud', names)), [])
})

test('a match on the category name keeps the whole group', () => {
  assert.deepEqual(types(filterGroups(groups, 'code', names)), ['github', 'gitlab', 'bitbucketcloud'])
  assert.deepEqual(types(filterGroups(groups, 'tick', names)), ['jira'])
})

test('case and accents are ignored, the d-stroke included', () => {
  assert.equal(normalizeSearch('  T\u1EC7p   T\u1EA2I '), 'tep tai')
  assert.equal(normalizeSearch('\u0110\u01B0\u1EDDng d\u1EABn'), 'duong dan')
  assert.equal(matchesQuery(['Th\u01B0 m\u1EE5c'], 'thu muc'), true)
  assert.equal(matchesQuery(['Thu muc'], 'TH\u01AF'), true)
})

test('groups with nothing left are dropped, and the rest keep their order', () => {
  const found = filterGroups(groups, 'p', names)
  assert.deepEqual(found.map(g => g.label), ['Files', 'Tickets and tasks'])
  assert.deepEqual(types(found), ['webpage', 'jira'])
})

test('columns are balanced in order, the tallest as short as it can be', () => {
  // Files 2, Websites 1, Docs 2, Code 5, Tickets 7, Chat 1, Email 2, Calendar 1, Storage 3, CRM 2, Support 2, Meetings 1, Other 1
  const h = [2, 1, 2, 5, 7, 1, 2, 1, 3, 2, 2, 1, 1].map(rows => rows + 1)
  const starts = balanceColumns(h, 4)
  assert.equal(starts.length, 4)
  assert.equal(starts[0], 0)
  const sums = starts.map((s, i) => h.slice(s, starts[i + 1] ?? h.length).reduce((a, b) => a + b, 0))
  // 14 is the least possible: Code (6) and Tickets (8) cannot share a column with much else.
  assert.equal(Math.max(...sums), 14, `columns ${sums}`)
  assert.deepEqual([...starts].sort((a, b) => a - b), starts)
})

test('one column, and never more columns than groups', () => {
  assert.deepEqual(balanceColumns([3, 4, 5], 1), [0])
  assert.deepEqual(balanceColumns([3, 4], 4), [0, 1])
  assert.deepEqual(balanceColumns([], 3), [0])
})

test('the types a deployment switch turned off are named, in the catalog order, and nothing else', () => {
  const type = (t: string, order: number, offered: boolean) => ({ type: t, order, offered })
  const catalog = [type('outlook', 30, false), type('jira', 1, true), type('teamschat', 21, false), type('teams', 20, false), type('files', 0, true)]
  assert.deepEqual(switchedOffTypes(catalog).map(t => t.type), ['teams', 'teamschat', 'outlook'])
  assert.deepEqual(switchedOffTypes([type('jira', 1, true)]), [])
})
