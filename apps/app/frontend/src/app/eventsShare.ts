/**
 * ONE `/api/v1/events` STREAM PER BROWSER, NOT PER TAB - the decisions behind SSEProvider.
 *
 * <p>A browser keeps at most six HTTP/1.1 connections per site, shared by every tab of the profile,
 * and an event stream holds one for as long as the page is open. Desktop is plain
 * http://127.0.0.1, which a browser never speaks HTTP/2 to, and so is a server behind a proxy that
 * does not offer it: six open tabs, or three with a turn running beside their chat stream, used the
 * whole pool, and the next tab's requests - its index.html included - waited for ever.
 *
 * <p>So one tab, the LEADER (whoever holds the Web Lock `LEADER_LOCK`), opens the stream and
 * re-posts every message on the BroadcastChannel `SHARE_CHANNEL`; every tab, the leader included,
 * hands each message to its own listeners exactly as before. The lock is released when its tab
 * closes, and the next tab waiting for it opens the stream.
 *
 * <p>THE STREAM IS FILTERED PER USER (EventStreamController), by the session the request carried,
 * and it names that user in its first event (`stream-identity`). Same-origin tabs share the
 * session, but it can change in one tab - a sign-in, a sign-out, another account - while the
 * leader's stream was opened under the old one, and another tab can still be showing the old one.
 * So:
 * - the leader tags every event it relays with the user its stream named, and a tab hands an
 *   event to its listeners only when that is the user its OWN session probe found (`accepts`);
 * - every tab posts who its probe finds signed in (`identity`), and the leader reopens its stream
 *   when that is not whom the stream was opened for (`StreamSession`).
 * Found on Enterprise Server, 2026-10-16: a tab still showing one account dispatched another's
 * events after a sign-in in a second tab, and a stream opened on the sign-in page stayed anonymous
 * after the sign-in, so no tab had live updates until the leader closed.
 *
 * <p>Plain TypeScript, tested by `node --test` (eventsShare.test.ts); SSEProvider holds the browser
 * objects.
 */

export const LEADER_LOCK = 'proxyma:events'
export const SHARE_CHANNEL = 'proxyma:events'

/**
 * Who someone is: a user's id, null for nobody signed in, undefined for not known (yet).
 */
export type Identity = string | null | undefined

/**
 * What travels on the channel: an event the leader received, with whom its stream was opened for
 * (`id`), or the session a tab found.
 */
export type ShareMessage =
  | { v: 1; kind: 'event'; data: string; id: Identity }
  | { v: 1; kind: 'identity'; id: string | null }

export function eventMessage(data: string, id: Identity): ShareMessage {
  return { v: 1, kind: 'event', data, id }
}

export function identityMessage(id: string | null): ShareMessage {
  return { v: 1, kind: 'identity', id }
}

/** A channel message as one of ours, or null - another build's shape, or anything else. */
export function readMessage(raw: unknown): ShareMessage | null {
  if (!raw || typeof raw !== 'object') return null
  const m = raw as Record<string, unknown>
  if (m.v !== 1) return null
  if (m.kind === 'event' && typeof m.data === 'string') {
    // An id of any other shape is no one's, and an event that is no one's is handed to no one.
    return { v: 1, kind: 'event', data: m.data, id: m.id === null || typeof m.id === 'string' ? m.id : undefined }
  }
  if (m.kind === 'identity' && (m.id === null || typeof m.id === 'string')) return { v: 1, kind: 'identity', id: m.id }
  return null
}

/** The `type` an event's JSON body names; 'message' for one that names none or is not JSON. */
export function eventType(data: string): string {
  try {
    const parsed: unknown = JSON.parse(data)
    if (parsed && typeof parsed === 'object') {
      const type = (parsed as { type?: unknown }).type
      if (typeof type === 'string') return type
    }
  } catch { /* non-JSON SSE */ }
  return 'message'
}

/**
 * Whether this tab can share one stream with the others: it needs both the Web Locks API (absent
 * outside a secure context - a server reached over plain http on a LAN address) and
 * BroadcastChannel. Without them each tab opens its own, as every tab used to.
 */
export function canShare(env: { locks: boolean; broadcast: boolean }): boolean {
  return env.locks && env.broadcast
}

/**
 * Who a settled session probe (`/api/v1/auth/me`) says is signed in: the user's id, null for
 * nobody (a 401), or undefined when it says nothing either way - a 429 or a server restarting is
 * "not now", not "signed out" (sessionProbeRetry).
 */
export function probedIdentity(
  result: { ok: true; user: { id?: unknown } | null | undefined } | { ok: false; status: number | undefined },
): string | null | undefined {
  if (result.ok) return typeof result.user?.id === 'string' ? result.user.id : null
  return result.status === 401 ? null : undefined
}

/** The `type` of the stream's first event, which names whose stream it is. */
export const STREAM_IDENTITY_TYPE = 'stream-identity'

/** The user a `stream-identity` event names (null for nobody), or undefined for any other event. */
export function streamIdentity(data: string): Identity {
  try {
    const parsed: unknown = JSON.parse(data)
    if (parsed && typeof parsed === 'object' && (parsed as { type?: unknown }).type === STREAM_IDENTITY_TYPE) {
      const id = (parsed as { userId?: unknown }).userId
      return typeof id === 'string' ? id : null
    }
  } catch { /* not ours */ }
  return undefined
}

/**
 * Whether a tab hands an event to its listeners: always without accounts (Desktop, Home Server -
 * one user, and no session probe), and otherwise only when the stream it came from was opened for
 * the user this tab's own session probe found. A tab that has not found out yet, or an event from
 * a stream that has not said whose it is, is handed nothing.
 */
export function accepts(auth: boolean, own: Identity, streamId: Identity): boolean {
  if (!auth) return true
  return own !== undefined && streamId !== undefined && own === streamId
}

/**
 * Whose the leader's stream is, and when it has to be opened again. The stream says whose it is
 * in its first event; a tab's session probe says whom the browser's session belongs to NOW. When
 * the two differ the session changed after the stream was opened, and the stream is reopened -
 * once per announcement, never on the stream's own word alone, so a stale answer cannot make the
 * leader reopen for ever.
 */
export interface StreamSession {
  /** Whom the current stream was opened for, or undefined until its first event says. */
  readonly id: Identity
  /** The stream was (re)opened: whose it is is not known until it says. */
  opened: () => void
  /** The stream said whose it is. True when it must be opened again. */
  identified: (id: string | null) => boolean
  /** A tab found `id` signed in. True when the stream must be opened again. */
  announced: (id: string | null) => boolean
  /**
   * An event came before the stream said whose it is - a server older than `stream-identity`. The
   * stream is then taken to be the session last found signed in, which is all such a server lets
   * the page know; until one is found, it stays no one's.
   */
  unnamed: () => void
}

export function createStreamSession(): StreamSession {
  let id: Identity
  // A session found while the stream had not yet said whose it is - compared when it does.
  let pending: Identity
  // The session last found signed in, whatever stream was open.
  let known: Identity
  return {
    get id() { return id },
    opened() {
      id = undefined
      pending = undefined
    },
    identified(named) {
      id = named
      const found = pending
      pending = undefined
      return found !== undefined && found !== named
    },
    announced(found) {
      known = found
      if (id === undefined) {
        pending = found
        return false
      }
      return found !== id
    },
    unnamed() {
      if (id !== undefined) return
      id = known
      pending = undefined
    },
  }
}
