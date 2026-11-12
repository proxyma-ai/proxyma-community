/**
 * WHAT THE MODEL THINKS, as it streams (2026-11-11, the maintainer: "can we have its thinking stream and print out thinking
 * tokens count instead of just the timer?"). The server sends the thinking's text in pieces ('thinking-text' events),
 * scrubbed as the answer is and never stored; the row under the thread shows its newest part. Plain .ts so `node --test`
 * reads it.
 */

/** How much of the thinking the page keeps: its newest characters. */
export const THINKING_KEPT = 6000

/** The thinking so far with the next piece, its newest THINKING_KEPT characters, cut at a line or word where it can. */
export function withThinking(so: string, piece: string, kept = THINKING_KEPT): string {
  const all = so + piece
  if (all.length <= kept) return all
  const tail = all.slice(all.length - kept)
  // Start at a line, else a word, so the box does not open mid-word.
  const line = tail.indexOf('\n')
  if (line >= 0 && line < 400) return tail.slice(line + 1)
  const space = tail.indexOf(' ')
  return space >= 0 && space < 80 ? tail.slice(space + 1) : tail
}
