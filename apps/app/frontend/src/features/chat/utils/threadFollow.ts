// When the thread follows its newest line, and when it leaves the reader where they are - and the
// same for every box inside it that scrolls on its own: a tool list past five calls, a deployed agent's
// panel (useFollowingBox).
//
// It followed always: every streamed word, every tool row and every deployed agent event scrolled the
// thread to its end, so nobody could read an earlier answer while the agent - or a deployed agent in
// the background - was still working (maintainer's feedback, 2026-10-02). The rule now, the
// maintainer's own: following is the default, a scroll UP switches it off for a while, every
// scroll after that starts the while again, and going back to the end switches it on at once.
//
// When the hold runs out the thread goes to its end again BY ITSELF if anything arrived while it was
// held (catchUpIn) - it waited for the next change until 2026-10-08, which on a tool that runs for
// a minute left the reader stranded above work that had already been written (0.29.2 feedback:
// "after the timer is expired, back to auto scroll down").
//
// Two things still move the thread whatever the reader is doing: a prompt the turn is waiting on
// (see ChatWindow - nothing else moves until it is answered) and the reader's own message.
//
// Plain .ts, so `node --test` reaches it - threadFollow.test.ts.

// How long the thread stays put after the reader's last scroll. Twenty seconds until 2026-10-08,
// when the tool list took the same rule and the maintainer asked for one figure for both - five
// was proposed and judged too short to read a step by.
export const HOLD_MS = 10_000

// Closer to the end than this IS the end: a line or two of slack, so a reader who scrolled back
// down does not have to land on the last pixel to be followed again.
export const END_SLACK_PX = 48

// A scroll DOWN that ends this many slacks from the end resumes following too (2026-10-10,
// maintainer's feedback: scrolling all the way down did not always bring it back). While an answer
// streams, the end runs away from the reader's wheel, so a reader heading for it landed a line or two
// short, read as reading, and was held for another ten seconds. Only downward: a scroll up still
// holds wherever it ends.
export const RESUME_SLACKS = 3

interface Scrolled {
  top: number
  // Where the scroller was at the previous scroll event.
  lastTop: number
  // Distance from the end of the thread.
  fromEnd: number
  now: number
  // The time following resumes; 0 or past when it is on.
  heldUntil: number
  // What counts as the end; END_SLACK_PX unless given. A box five rows tall needs far less - 48px
  // is half of it.
  slack?: number
}

/**
 * `heldUntil` after one scroll event.
 *
 * Only a move UP starts a hold. The thread's own scrolling goes down, and so does everything that
 * grows it, so neither can be mistaken for the reader. A panel folding at the end of the thread
 * does lower `top` - the scroller is clamped to its new end - but then `fromEnd` is zero.
 */
export function holdAfterScroll({ top, lastTop, fromEnd, now, heldUntil, slack = END_SLACK_PX }: Scrolled): number {
  if (fromEnd < slack) return 0
  if (top > lastTop && fromEnd < slack * RESUME_SLACKS) return 0
  if (top < lastTop || heldUntil > now) return now + HOLD_MS
  return heldUntil
}

interface Push {
  // Which way the reader pushed: a wheel's deltaY, or +1/-1 for a key. Positive is towards the end.
  delta: number
  // Distance from the end of the scroller.
  fromEnd: number
  slack?: number
}

/**
 * Whether a push towards the end - a wheel turned down, End, Page Down, the down arrow, Space - while
 * the scroller is already at (or within a few lines of) its end means "follow again".
 *
 * A scroller at its end does not move, so the browser fires no scroll event and holdAfterScroll never
 * hears of it: a reader who had scrolled up, came back down and kept pushing stayed held until the
 * hold ran out (2026-10-10, maintainer's feedback).
 */
export function pushesPastEnd({ delta, fromEnd, slack = END_SLACK_PX }: Push): boolean {
  return delta > 0 && fromEnd < slack * RESUME_SLACKS
}

/** Keys that scroll towards the end. Space only without Shift, which scrolls up. */
export function keyPushesDown(key: string, shift: boolean): boolean {
  return key === 'End' || key === 'PageDown' || key === 'ArrowDown' || (key === ' ' && !shift)
}

interface Change {
  now: number
  heldUntil: number
  // An approval, an enable request or one of the assistant's questions has just appeared.
  promptAppeared: boolean
  // The reader has just sent a message.
  userSent: boolean
}

/** Whether a change to the thread scrolls it to its end. */
export function followsChange({ now, heldUntil, promptAppeared, userSent }: Change): boolean {
  return promptAppeared || userSent || heldUntil <= now
}

interface Missed {
  now: number
  heldUntil: number
  // A change arrived while the hold was on and was not followed.
  missed: boolean
}

/**
 * Milliseconds until the thread should go to its end by itself, or null for never: only when
 * something was left unfollowed, and only once the hold has run out. Asked again whenever the
 * hold moves, since every scroll pushes it later.
 */
export function catchUpIn({ now, heldUntil, missed }: Missed): number | null {
  if (!missed) return null
  return Math.max(0, heldUntil - now)
}

interface PromptBox {
  // The prompt's top edge, measured from the top of the thread's content.
  top: number
  // The scroll position that is the thread's end.
  endTop: number
  // What the top of the thread's window keeps for the pinned question and its fade.
  clearTop: number
}

/**
 * Where the thread goes for a prompt that has just appeared: null for its end, which shows a
 * prompt whole, or the scroll position that puts the prompt's FIRST line in view when it is
 * taller than the room there is.
 *
 * The end was the only answer until 2026-10-02, and a prompt of two questions - one of them a
 * list to tick - is taller than a 720px window leaves: it opened on its last options and its
 * buttons, with the question itself scrolled off the top.
 */
export function promptScrollTop({ top, endTop, clearTop }: PromptBox): number | null {
  // Asked of the end itself rather than of the prompt's height: the margin under the prompt and
  // the thread's own padding are below it too, and a prompt that only just fits lost its first
  // line to them.
  if (top - endTop >= clearTop) return null
  return Math.max(0, top - clearTop)
}
