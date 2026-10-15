import { test } from 'node:test'
import assert from 'node:assert/strict'
import { OTHER, answerOf, nextUnanswered, parseQuestions, picksOf, stepLimitOf, togglePick } from './askUser.ts'

const several = { question: 'Which sources?', multiSelect: true, options: [{ label: 'Confluence' }, { label: 'Jira' }, { label: 'Files, local' }] }
const one = { question: 'How long?', options: [{ label: 'One page' }, { label: 'Full detail' }] }

test('a question that takes several picks answers with a list, in the order shown', () => {
  // Picked last-to-first, and one label holds a comma: joined text could not say two from three.
  assert.deepEqual(answerOf(several, ['Files, local', 'Confluence'], ''), ['Confluence', 'Files, local'])
  assert.deepEqual(answerOf(several, ['Jira'], ''), ['Jira'])
})

test('a typed answer joins the picks, last, only while its row is picked and holds text', () => {
  assert.deepEqual(answerOf(several, ['Jira', OTHER], '  the wiki '), ['Jira', 'the wiki'])
  assert.deepEqual(answerOf(several, ['Jira'], 'the wiki'), ['Jira'])
  assert.deepEqual(answerOf(several, ['Jira', OTHER], '   '), ['Jira'])
  assert.equal(answerOf(one, [OTHER], 'two pages'), 'two pages')
})

test('a question that takes one pick answers with that text; nothing picked is no answer', () => {
  assert.equal(answerOf(one, ['Full detail'], ''), 'Full detail')
  assert.equal(answerOf(one, [], ''), null)
  assert.equal(answerOf(several, [], ''), null)
  assert.equal(answerOf(several, [OTHER], ''), null)
  assert.deepEqual(picksOf(one, ['gone since'], ''), [])
})

test('a click replaces the pick of one, and toggles among several', () => {
  assert.deepEqual(togglePick(one, ['One page'], 'Full detail'), ['Full detail'])
  assert.deepEqual(togglePick(several, ['Jira'], 'Confluence'), ['Jira', 'Confluence'])
  assert.deepEqual(togglePick(several, ['Jira', 'Confluence'], 'Jira'), ['Confluence'])
})

test('the flag is on only when it says true - a model may write it as text', () => {
  const read = (multiSelect: unknown) =>
    parseQuestions(JSON.stringify({ questions: [{ question: 'Q?', options: [{ label: 'A' }], multiSelect }] }))[0].multiSelect
  assert.equal(read(true), true)
  assert.equal(read('true'), true)
  assert.equal(read('false'), undefined)
  assert.equal(read(false), undefined)
  assert.equal(read(undefined), undefined)
})

test('what cannot be asked is left out: no text, no options list, a blank or repeated label', () => {
  const questions = parseQuestions(JSON.stringify({ questions: [
    { question: '  ', options: [{ label: 'A' }] },
    { question: 'No options?' },
    null,
    { question: ' Which? ', header: ' Format ', options: [{ label: 'PDF', description: ' portable ' }, 'Word', { label: 'PDF' }, { label: ' ' }, {}] },
  ] }))
  assert.deepEqual(questions, [
    { question: 'Which?', header: 'Format', options: [{ label: 'PDF', description: 'portable' }, { label: 'Word' }] },
  ])
})

test('unreadable arguments are no questions', () => {
  assert.deepEqual(parseQuestions('{"questions": [{'), [])
  assert.deepEqual(parseQuestions('null'), [])
  assert.deepEqual(parseQuestions(undefined), [])
  assert.deepEqual(parseQuestions('{"questions": "one"}'), [])
})

test('the turn asking to go on past its step limit is told apart from the assistant asking', () => {
  // What ChatService.continueQuestion sends: ordinary questions, with the numbers beside them.
  const asked = JSON.stringify({
    stepLimit: 50, moreSteps: 50,
    questions: [{ question: 'Let it continue?', header: 'Step limit', options: [{ label: 'Continue' }, { label: 'Stop here' }] }],
  })
  assert.deepEqual(stepLimitOf(asked), { limit: 50, more: 50 })
  // Still a question anything that shows questions can show.
  assert.equal(parseQuestions(asked).length, 1)

  // The assistant's own question says nothing of a limit.
  assert.equal(stepLimitOf(JSON.stringify({ questions: [{ question: 'Which?', options: ['A', 'B'] }] })), null)
})

test('a step limit that is not a number is not one', () => {
  assert.equal(stepLimitOf(JSON.stringify({ stepLimit: '50' })), null)
  assert.equal(stepLimitOf(JSON.stringify({ stepLimit: 0 })), null)
  assert.equal(stepLimitOf('{"stepLimit": 5'), null)
  assert.equal(stepLimitOf(undefined), null)
  // No "more": a Continue gives as many again.
  assert.deepEqual(stepLimitOf(JSON.stringify({ stepLimit: 20 })), { limit: 20, more: 20 })
})

test('several questions: the next tab is the next unanswered one, going round', () => {
  assert.equal(nextUnanswered([true, false, false], 0), 1)
  assert.equal(nextUnanswered([false, true, true], 2), 0)
  // Answering the third of four comes back for a skipped first.
  assert.equal(nextUnanswered([false, true, true, true], 2), 0)
  // The question shown is not its own next, answered or not.
  assert.equal(nextUnanswered([true, false, true], 1), null)
  assert.equal(nextUnanswered([true, true, true], 0), null)
  assert.equal(nextUnanswered([false], 0), null)
})
