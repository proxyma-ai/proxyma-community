import { createContext, useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ENV } from '@/config/env'
import { FEATURES } from '@/config/features'
import { keys } from '@/services/queryKeys'
import {
  LEADER_LOCK, SHARE_CHANNEL, accepts, canShare, createStreamSession, eventMessage, eventType, identityMessage,
  probedIdentity, readMessage, streamIdentity, type Identity,
} from './eventsShare'

type SSEListener = (type: string, data: string) => void

export const SSEContext = createContext<{ subscribe: (fn: SSEListener) => () => void } | null>(null)

interface EventsStream {
  /** Opens the stream again now - under the session the browser holds now. */
  reopen: () => void
  close: () => void
}

// The page's /api/v1/events stream, reconnected five seconds after it fails. `onConnect` runs at
// every (re)connect, before its first event.
function openEventsStream(onData: (data: string) => void, onConnect: () => void): EventsStream {
  let closed = false
  let es: EventSource | null = null
  let retry: ReturnType<typeof setTimeout> | null = null

  const connect = () => {
    if (closed) return
    onConnect()
    es = new EventSource(`${ENV.apiBaseUrl}/api/v1/events`, { withCredentials: true })
    es.onmessage = e => onData(e.data)
    es.onerror = () => {
      es?.close()
      es = null
      if (!closed) retry = setTimeout(connect, 5000)
    }
  }
  const drop = () => {
    if (retry !== null) clearTimeout(retry)
    retry = null
    es?.close()
    es = null
  }

  connect()
  return {
    reopen() {
      if (closed) return
      drop()
      connect()
    },
    close() {
      closed = true
      drop()
    },
  }
}

/**
 * Source events for every listener on the page (useSSE). ONE stream per browser, not per tab: the
 * tab holding the Web Lock opens it and re-posts every message to the others - see eventsShare.ts
 * for why, and for when the stream is opened again.
 */
export function SSEProvider({ children }: { children: React.ReactNode }) {
  const listenersRef = useRef(new Set<SSEListener>())
  const qc = useQueryClient()

  useEffect(() => {
    const dispatch = (data: string) => {
      const type = eventType(data)
      for (const listener of listenersRef.current) listener(type, data)
    }

    // Who THIS tab's own session probe found - never another tab's word. An event reaches this
    // tab's listeners only when its stream was opened for that user (eventsShare.ts, accepts).
    let own: Identity
    // Whose this tab's stream is, while it holds one.
    const session = createStreamSession()
    let stream: EventsStream | null = null

    // A message on this tab's own stream: the stream saying whose it is, or an event - handed to
    // this tab's listeners if it is this tab's user's, and to `relay` with whose it is.
    const fromStream = (relay?: (data: string, id: Identity) => void) => (data: string) => {
      const named = streamIdentity(data)
      if (named !== undefined) {
        if (session.identified(named)) stream?.reopen()
        return
      }
      session.unnamed()
      if (accepts(FEATURES.auth, own, session.id)) dispatch(data)
      relay?.(data, session.id)
    }
    // The session someone found: when it is not the one this tab's stream was opened for, the
    // stream is opened again, under the session the browser holds now.
    const noteIdentity = (id: string | null) => {
      if (session.announced(id) && stream) stream.reopen()
    }

    const share = canShare({
      locks: typeof navigator !== 'undefined' && !!navigator.locks,
      broadcast: typeof BroadcastChannel !== 'undefined',
    })
    const channel = share ? new BroadcastChannel(SHARE_CHANNEL) : null
    const abort = new AbortController()
    let releaseLock: (() => void) | null = null

    const announce = (id: Identity) => {
      if (id === undefined) return
      own = id
      noteIdentity(id)
      channel?.postMessage(identityMessage(id))
    }

    if (!channel) {
      // Every tab its own stream, as before - no Web Locks outside a secure context.
      stream = openEventsStream(fromStream(), () => session.opened())
    } else {
      // A channel never hands a tab its own messages, so what arrives here is another tab's.
      channel.onmessage = (e: MessageEvent) => {
        const message = readMessage(e.data)
        if (!message) return
        if (message.kind === 'event') {
          if (accepts(FEATURES.auth, own, message.id)) dispatch(message.data)
        } else {
          noteIdentity(message.id)
        }
      }

      // Held for the tab's life, and released by the browser when the tab goes - the next tab
      // waiting for it then opens the stream.
      navigator.locks.request(LEADER_LOCK, { signal: abort.signal }, () => {
        if (abort.signal.aborted) return undefined
        stream = openEventsStream(
          fromStream((data, id) => channel.postMessage(eventMessage(data, id))),
          () => session.opened(),
        )
        return new Promise<void>(resolve => { releaseLock = resolve })
      }).catch(() => { /* aborted while waiting: this tab is going */ })
    }

    // Who this tab finds signed in, whenever its session probe settles. Desktop has no probe and so
    // never says - it has one user, and every event is its.
    const meKey = JSON.stringify(keys.me())
    const settled = (query: { queryKey: readonly unknown[] }) => JSON.stringify(query.queryKey) === meKey
    const statusOf = (error: unknown) => (error as { response?: { status?: number } } | null)?.response?.status
    const current = qc.getQueryCache().find({ queryKey: keys.me(), exact: true })
    if (current?.state.status === 'success') {
      announce(probedIdentity({ ok: true, user: current.state.data as { id?: unknown } | null }))
    } else if (current?.state.status === 'error') {
      announce(probedIdentity({ ok: false, status: statusOf(current.state.error) }))
    }
    const unsubscribe = qc.getQueryCache().subscribe(event => {
      if (event.type !== 'updated' || !settled(event.query)) return
      if (event.action.type === 'success') {
        announce(probedIdentity({ ok: true, user: event.action.data as { id?: unknown } | null }))
      } else if (event.action.type === 'error') {
        announce(probedIdentity({ ok: false, status: statusOf(event.action.error) }))
      }
    })

    return () => {
      unsubscribe()
      abort.abort()
      stream?.close()
      stream = null
      releaseLock?.()
      channel?.close()
    }
  }, [qc])

  const subscribe = (fn: SSEListener) => {
    listenersRef.current.add(fn)
    return () => { listenersRef.current.delete(fn) }
  }

  return <SSEContext.Provider value={{ subscribe }}>{children}</SSEContext.Provider>
}
