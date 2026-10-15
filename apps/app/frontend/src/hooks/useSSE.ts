import { useContext, useEffect, useRef } from 'react'
import { SSEContext } from '@/app/SSEProvider'

export function useSSE(onMessage: (type: string, data: string) => void) {
  const ctx = useContext(SSEContext)
  const cbRef = useRef(onMessage)
  cbRef.current = onMessage

  useEffect(() => {
    if (!ctx) return
    return ctx.subscribe((type, data) => cbRef.current(type, data))
  }, [ctx])
}
