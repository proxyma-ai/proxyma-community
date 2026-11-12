import { test } from 'node:test'
import assert from 'node:assert/strict'
import { caretAt, editorRows, keyAction, settle, settleChecked } from './clickToEdit.ts'

test('the caret lands where the text was clicked, else at its end', () => {
  assert.equal(caretAt(10, 4), 4)
  assert.equal(caretAt(10, null), 10)
  assert.equal(caretAt(10, undefined), 10)
  assert.equal(caretAt(10, 25), 10)
  assert.equal(caretAt(10, -3), 0)
  assert.equal(caretAt(10, Number.NaN), 10)
  assert.equal(caretAt(0, 3), 0)
})

test('a multi-line editor opens as tall as its text, one line at least, within its bounds', () => {
  assert.equal(editorRows(''), 1)
  assert.equal(editorRows('Thanks'), 1)
  assert.equal(editorRows('a\nb\nc'), 3)
  assert.equal(editorRows('x\n'.repeat(20)), 8)
  assert.equal(editorRows('x\n'.repeat(20), 1, 6), 6)
  assert.equal(editorRows('', 2, 6), 2)
})

test('Escape cancels; Enter commits a single line and is a new line in several, where Ctrl+Enter commits', () => {
  assert.equal(keyAction({ key: 'Escape' }, false), 'cancel')
  assert.equal(keyAction({ key: 'Escape' }, true), 'cancel')
  assert.equal(keyAction({ key: 'Enter' }, false), 'commit')
  assert.equal(keyAction({ key: 'Enter', shiftKey: true }, false), null)
  assert.equal(keyAction({ key: 'Enter' }, true), null)
  assert.equal(keyAction({ key: 'Enter', ctrlKey: true }, true), 'commit')
  assert.equal(keyAction({ key: 'Enter', metaKey: true }, true), 'commit')
  assert.equal(keyAction({ key: 'a' }, false), null)
  assert.equal(keyAction({ key: ' ' }, false), null)
})

test('a key pressed while an input method composes a word is the word\'s, never a commit or a cancel', () => {
  assert.equal(keyAction({ key: 'Enter', isComposing: true }, false), null)
  assert.equal(keyAction({ key: 'Escape', isComposing: true }, false), null)
  assert.equal(keyAction({ key: 'Enter', ctrlKey: true, isComposing: true }, true), null)
})

test('leaving the editor commits only a change, trimmed', () => {
  assert.deepEqual(settle('Release', 'Release'), { kind: 'unchanged' })
  assert.deepEqual(settle('Release', '  Release  '), { kind: 'unchanged' })
  assert.deepEqual(settle('Release', ' Release 0.39 '), { kind: 'commit', text: 'Release 0.39' })
  assert.deepEqual(settle('', 'A new purpose\n'), { kind: 'commit', text: 'A new purpose' })
})

test('untrimmed, the text is committed exactly as typed', () => {
  assert.deepEqual(settle('Thanks!', 'Thanks! ', { trim: false }), { kind: 'commit', text: 'Thanks! ' })
  assert.deepEqual(settle('Thanks!', 'Thanks!', { trim: false }), { kind: 'unchanged' })
  assert.deepEqual(settle('Thanks!', '   ', { trim: false }), { kind: 'commit', text: '' })
})

test('an emptied text takes its fallback, is refused when required, else is committed empty', () => {
  assert.deepEqual(settle('Release', '   ', { fallback: 'Untitled Project' }), { kind: 'commit', text: 'Untitled Project' })
  assert.deepEqual(settle('Untitled Project', '', { fallback: 'Untitled Project' }), { kind: 'unchanged' })
  assert.deepEqual(settle('Scout', '', { required: true }), { kind: 'missing' })
  assert.deepEqual(settle('Scout', ' \n ', { required: true }), { kind: 'missing' })
  // A fallback wins over required: there is always something to show.
  assert.deepEqual(settle('Scout', '', { required: true, fallback: 'Agent' }), { kind: 'commit', text: 'Agent' })
  assert.deepEqual(settle('A purpose', ''), { kind: 'commit', text: '' })
  assert.deepEqual(settle('', ''), { kind: 'unchanged' })
  // A blank fallback is no fallback.
  assert.deepEqual(settle('Scout', '', { required: true, fallback: ' ' }), { kind: 'missing' })
})

test('a changed text the check refuses is not committed, and says why', () => {
  const steps = (text: string) => (/^\d+$/.test(text) && Number(text) <= 200 ? null : 'A number from 1 to 200.')
  assert.deepEqual(settle('20', '250', { check: steps }), { kind: 'invalid', reason: 'A number from 1 to 200.' })
  assert.deepEqual(settle('20', ' 40 ', { check: steps }), { kind: 'commit', text: '40' })
  // Only a change is checked: what is stored stands.
  assert.deepEqual(settle('abc', 'abc', { check: steps }), { kind: 'unchanged' })
  // An emptied text is the check's to judge too, after required and a fallback have had their say.
  assert.deepEqual(settle('20', '', { check: t => (t === '' ? null : steps(t)) }), { kind: 'commit', text: '' })
  assert.deepEqual(settle('20', '', { check: steps, required: true }), { kind: 'missing' })
  assert.deepEqual(settle('20', '', { check: () => 'never', fallback: '10' }), { kind: 'invalid', reason: 'never' })
})

test('a commit the check refuses is held back with its sentence; anything else settles as settle does', () => {
  const check = (text: string) => (/^\d+$/.test(text) ? null : 'Enter a whole number.')
  assert.deepEqual(settleChecked('7', 'seven', {}, check), { kind: 'invalid', reason: 'Enter a whole number.' })
  assert.deepEqual(settleChecked('7', ' 30 ', {}, check), { kind: 'commit', text: '30' })
  assert.deepEqual(settleChecked('7', '7', {}, check), { kind: 'unchanged' })
  assert.deepEqual(settleChecked('7', '', { required: true }, check), { kind: 'missing' })
  // The check reads what would be committed: a fallback, not the emptied draft.
  assert.deepEqual(settleChecked('7', '', { fallback: '0' }, check), { kind: 'commit', text: '0' })
  assert.deepEqual(settleChecked('7', 'x', {}), { kind: 'commit', text: 'x' })
})
