/**
 * THE PAGE'S ONE CHAT EVENT STREAM: which conversation it follows, the last event it handled, and
 * the subscription to close when another replaces it.
 *
 * <p>Held by the PAGE, not by a hook instance - since the 0.27.0 feedback, where messages and tool
 * rows showed twice. AppShell remounts the chat page on every pathname change, the first message's
 * `/` to `/chat/{id}` included, and useChat kept all of this in refs of its own. Before sub-agents
 * ran in the background that cost nothing, because a turn's `done` closed the stream. Now a `done`
 * that says more is coming keeps it open, so the stream the old instance opened went on following
 * the conversation after the page remounted - and the next send from the new instance opened a
 * second one beside it, replaying from the start. Both wrote into the one chat store, so every
 * text delta, tool call and report landed twice.
 *
 * <p>Here, whoever follows a conversation closes what was followed before, wherever it was opened,
 * and an event from a stream that is no longer the one followed is not handled.
 *
 * <p>ONE PER CHAT INSTANCE since the key-account panel (docs/architecture.html#spec-11-8): a
 * page can show the chat page's chat and a panel's, and each holds its own stream - in its
 * ChatInstance (features/chat/instance), which outlives a remount as the module-level one did.
 */
export interface ChatStream {
  /** Closes the open subscription; null when nothing is followed. */
  close: (() => void) | null
  /** The conversation followed, or null. */
  convId: string | null
  /** The last event handled from it - where a stream that replaces it starts. */
  lastSeq: number
  /** Changes every time the stream is replaced or stopped; an event carries the one it came from. */
  generation: number
}

export function newChatStream(): ChatStream {
  return { close: null, convId: null, lastSeq: -1, generation: 0 }
}

/**
 * Starts following `convId` from after `afterSeq`, closing whatever was followed before. Returns
 * the new stream's generation, which its events are checked against (`accept`).
 */
export function follow(s: ChatStream, convId: string, afterSeq: number): number {
  s.close?.()
  s.close = null
  s.convId = convId
  s.lastSeq = afterSeq
  s.generation += 1
  return s.generation
}

/** Keeps `close` for the stream of `generation` - or calls it at once, if that stream was already replaced. */
export function keepClose(s: ChatStream, generation: number, close: () => void) {
  if (s.generation === generation) s.close = close
  else close()
}

/** Whether an event from the stream of `generation` is to be handled. Records its sequence number. */
export function accept(s: ChatStream, generation: number, seq: number | undefined): boolean {
  if (generation !== s.generation) return false
  if (typeof seq === 'number') s.lastSeq = seq
  return true
}

/** Where a new stream for `convId` starts: after the last event handled, if it is the one followed. */
export function resumeAfter(s: ChatStream, convId: string): number {
  return s.convId === convId ? s.lastSeq : -1
}

/** Stops following, from anywhere: the subscription is closed and nothing is resumed from it. */
export function stop(s: ChatStream) {
  s.close?.()
  s.close = null
  s.convId = null
  s.lastSeq = -1
  s.generation += 1
}

/**
 * The stream of `generation` closed itself - its turn is over with nothing more coming, or it
 * failed. The last event handled is kept, for a stream that takes over from it.
 */
export function ended(s: ChatStream, generation: number) {
  if (generation !== s.generation) return
  s.close = null
  s.convId = null
}
