// What the feedback dialogs say once a report is submitted, and the one limit they hold a message
// to. Plain .ts with no JSX, so `node --test` reaches it (feedbackOutcome.test.ts).

import type { FeedbackDelivery } from '../types/feedback.types.ts'

// The longest comment the telemetry service takes - FeedbackSubmission.MAX_COMMENT_CHARS in
// libs/shared-contracts. A longer one was stored on this computer, refused there, and the dialog
// still thanked the person; it is now held in the dialog before anything is sent.
export const MAX_COMMENT_CHARS = 20_000

/** Characters past the limit, 0 when the comment fits. Counted as Java's String.length() counts. */
export function commentOverBy(comment: string): number {
  return Math.max(0, comment.trim().length - MAX_COMMENT_CHARS)
}

/**
 * The count under the box, from 90% of the limit up to it - so the limit is seen coming. Past it the
 * box says by how much instead. Never a hard maxLength: the browser cuts a paste at it without a
 * word, and the end of a pasted log or stack trace is usually the part that matters.
 */
export function commentCount(comment: string): number | null {
  const length = comment.trim().length
  return length >= MAX_COMMENT_CHARS * 0.9 && length <= MAX_COMMENT_CHARS ? length : null
}

/**
 * 'notSent' when the telemetry service refused the report or could not be reached: it is saved on
 * this computer and the person is told it did not go, rather than thanked. A report kept here on
 * purpose (no telemetry address, a rating whose sharing is off) is what was asked for, and an older
 * server's answer says nothing - both are 'thanks'. The dialogs word each kind themselves, so the
 * catalog checker sees the sentences.
 */
export function submittedOutcome(delivery: FeedbackDelivery | undefined): 'thanks' | 'notSent' {
  return delivery === 'NOT_SENT' ? 'notSent' : 'thanks'
}
