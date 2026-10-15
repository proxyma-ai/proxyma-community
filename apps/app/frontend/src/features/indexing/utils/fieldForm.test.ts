import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  fieldProblems, fieldValuesChanged, groupTypesByCategory, initialFieldValues, splitAdvanced, submittedFieldValues,
} from './fieldForm.ts'

/**
 * A descriptor-driven form, under `node --test` (see graph3d.test.ts for the arrangement). What is
 * pinned: the page refuses what the server's ConfigValidator refuses, a secret is never sent blank,
 * and the Connect menu groups what is offered and nothing else.
 */

const field = (over: Record<string, unknown>) => ({
  key: 'k', label: 'K', type: 'TEXT', required: false, placeholder: null, help: null, defaultValue: null,
  options: [], pattern: null, advanced: false, min: null, max: null, ...over,
}) as never

test('a new form starts on the defaults, an edit on what is stored, and a secret always empty', () => {
  const fields = [
    field({ key: 'repo' }),
    field({ key: 'issues', type: 'BOOLEAN', defaultValue: 'true' }),
    field({ key: 'docs', type: 'BOOLEAN' }),
    field({ key: 'token', type: 'SECRET', defaultValue: 'never' }),
  ]
  assert.deepEqual(initialFieldValues(fields), { repo: '', issues: 'true', docs: 'false', token: '' })
  assert.deepEqual(initialFieldValues(fields, { repo: 'a/b', issues: 'false', token: 'x' }),
    { repo: 'a/b', issues: 'false', docs: 'false', token: '' })
})

test('a required field left empty is refused, unless it is a secret already stored', () => {
  const fields = [field({ key: 'repo', required: true }), field({ key: 'token', type: 'SECRET', required: true })]
  assert.deepEqual(Object.keys(fieldProblems(fields, { repo: ' ', token: '' })), ['repo', 'token'])
  assert.deepEqual(Object.keys(fieldProblems(fields, { repo: 'a/b', token: '' }, ['token'])), [])
})

test('numbers, addresses, choices and patterns are checked as the server checks them', () => {
  const days = field({ key: 'days', type: 'NUMBER', min: 0, max: 3650 })
  assert.equal(fieldProblems([days], { days: '12' }).days, undefined)
  assert.deepEqual(fieldProblems([days], { days: '1.5' }).days, { code: 'number', min: 0, max: 3650 })
  assert.equal(fieldProblems([days], { days: '4000' }).days?.code, 'number')

  const url = field({ key: 'u', type: 'URL' })
  assert.equal(fieldProblems([url], { u: 'https://example.com/x' }).u, undefined)
  assert.equal(fieldProblems([url], { u: 'ftp://example.com' }).u?.code, 'url')

  const kind = field({ key: 'kind', type: 'SELECT', options: [{ value: 'mail', label: 'Mail' }] })
  assert.equal(fieldProblems([kind], { kind: 'calendar' }).kind?.code, 'option')

  const repo = field({ key: 'repo', pattern: '[^/\\s]+/[^/\\s]+', placeholder: 'owner/repository' })
  assert.equal(fieldProblems([repo], { repo: 'octo/hello' }).repo, undefined)
  // The WHOLE value, as Java's matches() reads it - not a match somewhere inside it.
  assert.deepEqual(fieldProblems([repo], { repo: 'octo/hello/x' }).repo, { code: 'pattern', example: 'owner/repository' })
  assert.equal(fieldProblems([field({ key: 't' })], { t: 'one\ntwo' }).t?.code, 'oneLine')
})

test('a pattern this engine cannot read refuses nothing', () => {
  assert.deepEqual(fieldProblems([field({ key: 'x', pattern: '(?<' })], { x: 'anything' }), {})
})

test('what is sent: every field trimmed, a blank secret left out, a list one entry a line', () => {
  const fields = [
    field({ key: 'repo' }),
    field({ key: 'branch' }),
    field({ key: 'token', type: 'SECRET' }),
    field({ key: 'paths', type: 'LIST' }),
  ]
  assert.deepEqual(submittedFieldValues(fields, { repo: ' a/b ', branch: '', token: '  ', paths: ' docs \n\n src\n' }),
    { repo: 'a/b', branch: '', paths: 'docs\nsrc' })
  assert.deepEqual(submittedFieldValues(fields, { token: ' t ' }), { repo: '', branch: '', token: 't', paths: '' })
})

test('changed means a value moved, not that a field was touched and put back', () => {
  const fields = [field({ key: 'a' })]
  assert.equal(fieldValuesChanged(fields, { a: 'x' }, { a: ' x ' }), false)
  assert.equal(fieldValuesChanged(fields, { a: 'x' }, { a: 'y' }), true)
})

test('advanced fields go behind More options', () => {
  const { main, advanced } = splitAdvanced([field({ key: 'a' }), field({ key: 'b', advanced: true })])
  assert.deepEqual(main.map((f: { key: string }) => f.key), ['a'])
  assert.deepEqual(advanced.map((f: { key: string }) => f.key), ['b'])
})

test('the Connect menu groups what is offered, by category, each group in order', () => {
  const type = (t: string, category: string, order: number, offered = true) =>
    ({ type: t, category, order, offered }) as never
  const groups = groupTypesByCategory(
    [type('github', 'CODE', 55), type('jira', 'TICKETS', 30), type('bitbucket', 'CODE', 50),
     type('teams', 'CHAT', 90, false), type('odd', 'NEW_KIND', 5)],
    [{ key: 'TICKETS', label: 'Tickets and tasks' }, { key: 'CODE', label: 'Code' }, { key: 'CHAT', label: 'Chat' }],
  )
  assert.deepEqual(groups.map(g => [g.category, g.types.map((t: { type: string }) => t.type)]), [
    ['TICKETS', ['jira']],
    ['CODE', ['bitbucket', 'github']],
    ['OTHER', ['odd']],
  ])
})
