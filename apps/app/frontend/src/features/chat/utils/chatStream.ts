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
  /** Its connection was released while the tab was hidden; it is picked up again from `lastSeq`. */
  paused: boolean
  /** When it was paused (ms since the epoch). */
  pausedAt: number
  /**
   * Set on a stream that picked up after a pause: its first new event says whether anything was
   * missed meanwhile (`missed`).
   */
  checkGap: boolean
  /**
   * The server process `lastSeq` was numbered by - what the stream's first event says (`opened`);
   * null until one has. Kept while the same conversation is followed on, so a stream that replaces
   * this one can tell the server it reaches from the one it left.
   */
  boot: string | null
}

export function newChatStream(): ChatStream {
  return { close: null, convId: null, lastSeq: -1, generation: 0, paused: false, pausedAt: 0, checkGap: false, boot: null }
}

/**
 * Starts following `convId` from after `afterSeq`, closing whatever was followed before. Returns
 * the new stream's generation, which its events are checked against (`accept`).
 */
export function follow(s: ChatStream, convId: string, afterSeq: number, options: { resumed?: boolean } = {}): number {
  s.close?.()
  s.close = null
  // Another conversation, or the first since nothing was followed: no server's numbering is held.
  if (s.convId !== convId) s.boot = null
  s.convId = convId
  s.lastSeq = afterSeq
  s.generation += 1
  s.paused = false
  // Only a known position can be checked: -1 also means "from wherever the server's buffer starts".
  s.checkGap = options.resumed === true && afterSeq >= 0
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
  s.paused = false
  s.checkGap = false
  s.boot = null
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

/**
 * RELEASES THE CONNECTION AND KEEPS THE PLACE - for a tab hidden for a while (chatInstance.ts).
 *
 * <p>A browser keeps six HTTP/1.1 connections per site across all its tabs, and a chat stream holds
 * one for as long as a turn or a background sub-agent runs; tabs left open in the background used
 * the pool up, and the next page's requests waited for ever. So a hidden tab closes its stream, but
 * unlike `stop` keeps the conversation and the last event handled, and `resumePoint` says where to
 * pick up.
 *
 * <p>Anything still in flight from the closed stream is ignored, as for a replaced one. Returns
 * whether there was an open stream to pause.
 */
export function pause(s: ChatStream, now: number): boolean {
  if (s.convId === null || s.close === null) return false
  s.close()
  s.close = null
  s.generation += 1
  s.paused = true
  s.pausedAt = now
  s.checkGap = false
  return true
}

/**
 * How long the server is sure to keep what a paused stream has not been sent yet. Its buffer
 * (ChatEventSink) holds the latest turn from its first event, and is dropped only after 45 minutes
 * with no new event and nobody following. A stream open when the tab paused was waiting for an
 * event still to come - so whatever ended it was published after the pause, and the buffer holding
 * it outlives the pause by 45 minutes at least. Five of them are left as a margin.
 */
export const REPLAY_KEPT_MS = 40 * 60_000

/**
 * Where a paused stream picks up - once; null when it is not paused (never was, or was followed or
 * stopped since). `reload` when it was paused longer than the server is sure to keep the events it
 * missed: the conversation is then loaded whole, as a page coming back to it would.
 */
export function resumePoint(s: ChatStream, now: number): { convId: string; afterSeq: number; reload: boolean } | null {
  if (!s.paused || s.convId === null) return null
  s.paused = false
  return { convId: s.convId, afterSeq: s.lastSeq, reload: now - s.pausedAt >= REPLAY_KEPT_MS }
}

/**
 * Whether the first new event of a stream picked up after a pause shows events were MISSED. The
 * server numbers a conversation's events without a gap and replays its buffer from the latest turn's
 * first event, skipping nothing; a stream picking up after `afterSeq` is sent everything above it.
 * Its first new event numbered past the next one means the buffer was replaced by a newer turn
 * while the tab was away, and what came between is in no stream any more - the caller then loads
 * the conversation whole. Asked once per resumed stream; false for anything else.
 */
export function missed(s: ChatStream, generation: number, seq: number | undefined): boolean {
  if (generation !== s.generation || !s.checkGap || typeof seq !== 'number' || seq <= s.lastSeq) return false
  s.checkGap = false
  return seq > s.lastSeq + 1
}

/** What a chat stream's first event says (ChatController.openEvent) - see `opened`. */
export interface StreamOpen {
  /** The server process: a restarted server has another. */
  boot: string
  /** Whether anything is still to publish into the conversation's stream. */
  busy: boolean
  /** The number the conversation's next event will carry. */
  next: number
}

/** The 'open' event's data, or null when it is not one this page can read. */
export function parseOpen(data: string): StreamOpen | null {
  try {
    const d = JSON.parse(data) as Partial<StreamOpen>
    if (typeof d.boot !== 'string' || typeof d.busy !== 'boolean' || typeof d.next !== 'number') return null
    return { boot: d.boot, busy: d.busy, next: d.next }
  } catch {
    return null
  }
}

/**
 * WHETHER WHAT THIS PAGE FOLLOWS IS GONE, from the first event of a stream (since 2026-10-16) - and
 * records which server it reached. The page then loads the conversation again instead of waiting.
 *
 * <p>Gone when the server is another process than the one the page was following: everything held
 * in memory there - the running turn, background sub-agents, the replay, the numbering - died with
 * it. A restart mid-turn (Settings' restart, an update, a crash) left the page showing "Waiting
 * for ..." for ever: the restarted server numbers the conversation from zero and replays nothing,
 * so the reconnected stream said nothing at all, and the next message was sent into the dead turn
 * as an injection, refused with a 409 and left "Queued".
 *
 * <p>Gone too when nothing is running there and the page already holds every event there is: no
 * further event will ever come, whatever the page is waiting for. Only for a page that holds a
 * place (`lastSeq` 0 or more) - a stream followed from the start, for work the server has just
 * accepted, may open before that work has registered.
 */
export function opened(s: ChatStream, generation: number, open: StreamOpen): boolean {
  if (generation !== s.generation) return false
  const restarted = s.boot !== null && s.boot !== open.boot
  s.boot = open.boot
  if (restarted) return true
  return s.lastSeq >= 0 && !open.busy && s.lastSeq >= open.next - 1
}

/**
 * The queued messages a chat sends again once its conversation is loaded whole - a reload throws
 * the thread away, and the queue with it. ALL of them when what the page followed is gone
 * (`opened`): the page holds every event there was, so a message still queued was placed by no
 * turn, whether or not one had accepted it to read at its next step - and a restarted server holds
 * nothing of the dead turn. Otherwise (events were missed) only those no turn accepted, which
 * `accepted` lists: an accepted one may be in the conversation already.
 */
export function carriedOnReload<T extends { clientId?: string }>(queue: T[], accepted: Set<string>, all: boolean): T[] {
  return all ? queue : queue.filter(q => !q.clientId || !accepted.has(q.clientId))
}
