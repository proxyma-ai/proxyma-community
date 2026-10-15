import { test } from 'node:test'
import assert from 'node:assert/strict'
import { jsonFields } from './jsonFields.ts'

test('the feedback: a failed result is two readable fields, not a JSON object', () => {
  const fields = jsonFields(JSON.stringify({
    success: false,
    error: 'The server refused the connection or could not be reached.',
  }))
  assert.deepEqual(fields, [
    { kind: 'value', label: 'Success', value: 'false', block: false },
    { kind: 'value', label: 'Error', value: 'The server refused the connection or could not be reached.', block: false },
  ])
})

test('a list of objects is a group of numbered items, each with its own fields', () => {
  const fields = jsonFields(JSON.stringify({
    results: [{ source: 'Handbook', score: 0.9 }, { source: 'Wiki', text: 'line one\nline two' }],
  }))
  assert.deepEqual(fields, [{
    kind: 'group', label: 'Results', fields: [
      { kind: 'group', label: '1', fields: [
        { kind: 'value', label: 'Source', value: 'Handbook', block: false },
        { kind: 'value', label: 'Score', value: '0.9', block: false },
      ] },
      { kind: 'group', label: '2', fields: [
        { kind: 'value', label: 'Source', value: 'Wiki', block: false },
        { kind: 'value', label: 'Text', value: 'line one\nline two', block: true },
      ] },
    ],
  }])
})

test('a nested object is a group; a list of plain values is one field, a value to a line', () => {
  const fields = jsonFields(JSON.stringify({ fields: { issueKey: 'SAI-17' }, labels: ['cloud', 'infra'], one: ['a'] }))
  assert.deepEqual(fields, [
    { kind: 'group', label: 'Fields', fields: [{ kind: 'value', label: 'Issue key', value: 'SAI-17', block: false }] },
    { kind: 'value', label: 'Labels', value: 'cloud\ninfra', block: true },
    { kind: 'value', label: 'One', value: 'a', block: false },
  ])
})

test('empty values are left out at every depth; false and 0 are values', () => {
  const fields = jsonFields(JSON.stringify({
    none: null, blank: '', nothing: [], bare: {}, n: 0, flag: false, inner: { gone: '', kept: 1 },
  }))
  assert.deepEqual(fields, [
    { kind: 'value', label: 'N', value: '0', block: false },
    { kind: 'value', label: 'Flag', value: 'false', block: false },
    { kind: 'group', label: 'Inner', fields: [{ kind: 'value', label: 'Kept', value: '1', block: false }] },
  ])
})

test('a server path never becomes a field, however deep', () => {
  const fields = jsonFields(JSON.stringify({ filePath: 'C:\\x', files: [{ name: 'a.pdf', localPath: '/srv/a.pdf' }] }))
  assert.deepEqual(fields, [{
    kind: 'group', label: 'Files', fields: [
      { kind: 'group', label: '1', fields: [{ kind: 'value', label: 'Name', value: 'a.pdf', block: false }] },
    ],
  }])
})

test('a top-level list of objects is its items', () => {
  assert.deepEqual(jsonFields('[{"key":"A-1"},{"key":"A-2"}]'), [
    { kind: 'group', label: '1', fields: [{ kind: 'value', label: 'Key', value: 'A-1', block: false }] },
    { kind: 'group', label: '2', fields: [{ kind: 'value', label: 'Key', value: 'A-2', block: false }] },
  ])
})

test('what is not an object or a list of them is left to the plain view', () => {
  assert.equal(jsonFields('The page was saved.'), null)
  assert.equal(jsonFields('1'), null)
  assert.equal(jsonFields('"text"'), null)
  assert.equal(jsonFields('[1,2]'), null)
  assert.equal(jsonFields(''), null)
  assert.equal(jsonFields(undefined), null)
})

test('very deep structures end in JSON rather than recursing for ever', () => {
  const fields = jsonFields(JSON.stringify({ a: { b: { c: { d: { e: { f: { g: 1 } } } } } } }))
  let node = fields![0]
  let depth = 0
  while (node.kind === 'group') { node = node.fields[0]; depth++ }
  assert.equal(depth, 5)
  assert.equal(node.block, true)
  assert.match(node.value, /"g": 1/)
})
