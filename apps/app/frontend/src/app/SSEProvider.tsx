import { createContext, useEffect, useRef } from 'react'
import { ENV } from '@/config/env'

type SSEListener = (type: string, data: string) => void

export const SSEContext = createContext<{ subscribe: (fn: SSEListener) => () => void } | null>(null)

export function SSEProvider({ children }: { children: React.ReactNode }) {
  const listenersRef = useRef(new Set<SSEListener>())

  useEffect(() => {
    let closed = false
    let es: EventSource | null = null

    function connect() {
      if (closed) return
      es = new EventSource(`${ENV.apiBaseUrl}/api/v1/events`, { withCredentials: true })

      es.onmessage = e => {
        let type = 'message'
        try {
          type = JSON.parse(e.data).type ?? 'message'
        } catch { /* non-JSON SSE */ }
        for (const listener of listenersRef.current) listener(type, e.data)
      }

      es.onerror = () => {
        es?.close()
        if (!closed) setTimeout(connect, 5000)
      }
    }

    connect()
    return () => { closed = true; es?.close() }
  }, [])

  const subscribe = (fn: SSEListener) => {
    listenersRef.current.add(fn)
    return () => { listenersRef.current.delete(fn) }
  }

  return <SSEContext.Provider value={{ subscribe }}>{children}</SSEContext.Provider>
}
