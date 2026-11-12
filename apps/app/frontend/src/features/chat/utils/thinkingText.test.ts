import { test } from 'node:test'
import assert from 'node:assert/strict'
import { withThinking } from './thinkingText.ts'

test('the thinking is kept whole until it passes the bound, then its newest part from a line or a word', () => {
  assert.equal(withThinking('Looking at ', 'the comment.'), 'Looking at the comment.')
  assert.equal(withThinking('first line\nsecond', ' line', 20), 'second line')
  assert.equal(withThinking('aaaa bbbb cccc', ' dddd', 12), 'cccc dddd')
  const long = 'x'.repeat(50)
  assert.equal(withThinking(long, 'y'.repeat(50), 30).length, 30, 'no line or word to cut at: the newest characters')
})
