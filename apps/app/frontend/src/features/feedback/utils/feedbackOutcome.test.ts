import { test } from 'node:test'
import assert from 'node:assert/strict'
import { MAX_COMMENT_CHARS, commentCount, commentOverBy, submittedOutcome } from './feedbackOutcome.ts'

test('the count shows from 90% of the limit up to it, and gives way to the error past it', () => {
  assert.equal(commentCount(''), null)
  assert.equal(commentCount('a'.repeat(17_999)), null)
  assert.equal(commentCount('a'.repeat(18_000)), 18_000)
  assert.equal(commentCount('  ' + 'a'.repeat(18_000) + '\n'), 18_000)
  assert.equal(commentCount('a'.repeat(MAX_COMMENT_CHARS)), MAX_COMMENT_CHARS)
  assert.equal(commentCount('a'.repeat(MAX_COMMENT_CHARS + 1)), null)
})

test('a report the telemetry service did not take is not thanked for', () => {
  assert.equal(submittedOutcome('NOT_SENT'), 'notSent')
})

test('a sent report, one kept here on purpose and an older server answer are thanked', () => {
  for (const delivery of ['SENT', 'KEPT_HERE', undefined] as const) {
    assert.equal(submittedOutcome(delivery), 'thanks', String(delivery))
  }
})

test('a comment is held to the telemetry service limit, counted after trimming', () => {
  assert.equal(MAX_COMMENT_CHARS, 20_000)
  assert.equal(commentOverBy('a'.repeat(MAX_COMMENT_CHARS)), 0)
  assert.equal(commentOverBy('  ' + 'a'.repeat(MAX_COMMENT_CHARS) + '\n'), 0)
  assert.equal(commentOverBy('a'.repeat(MAX_COMMENT_CHARS + 7)), 7)
  // Vietnamese letters are one UTF-16 unit each, as Java counts them; an emoji is two.
  assert.equal(commentOverBy('\u0103'.repeat(MAX_COMMENT_CHARS + 1)), 1)
  assert.equal(commentOverBy('\u{1F600}'.repeat(MAX_COMMENT_CHARS / 2 + 1)), 2)
})
