import { useEffect, useRef, useState } from 'react'
import { LOADING_MESSAGES, LOADING_MESSAGE_INTERVAL_SECONDS } from '../data/loadingMessages'

// Picks a new random message from the pool, avoiding an immediate repeat of the previously shown
// message (a single reroll is enough - a back-to-back repeat after that is an acceptable
// one-in-a-hundred-and-eighty coincidence, not worth looping over).
function pickMessage(avoidIndex: number | null): { text: string; index: number } {
  let index = Math.floor(Math.random() * LOADING_MESSAGES.length)
  if (LOADING_MESSAGES.length > 1 && index === avoidIndex) index = (index + 1) % LOADING_MESSAGES.length
  return { text: LOADING_MESSAGES[index], index }
}

// Drives the "still working on it" copy shown while something slow is in flight - see
// ../proxyma-docs/architecture/chat-reactivity.md. Below one LOADING_MESSAGE_INTERVAL_SECONDS, returns null
// (a normal fast response never shows anything) unless `immediate` is set, in which case a
// message is picked right away on mount instead of waiting out the first grace period - used by
// ThinkingIndicator, which must reassure the user from the very first chat model call rather than
// only after a stall. From there it picks a new random message from the flat LOADING_MESSAGES
// pool every LOADING_MESSAGE_INTERVAL_SECONDS for as long as the wait lasts, so a stall of any
// length keeps visibly changing.
//
// `startedAt` lets a caller anchor elapsed time to when the underlying wait actually began
// (e.g. a tool call's own start timestamp) rather than this hook's own mount time - matters for
// a row reconstructed mid-flight (see the chat-resume flow). Omit it to just use mount time.
export function useEscalatingMessage(startedAt?: number, immediate = false): string | null {
  const startedAtRef = useRef(startedAt ?? Date.now())
  const [boundary, setBoundary] = useState(0)
  const lastIndexRef = useRef<number | null>(null)
  const [message, setMessage] = useState<string | null>(() => {
    if (!immediate) return null
    const { text, index } = pickMessage(null)
    lastIndexRef.current = index
    return text
  })

  useEffect(() => {
    // A 1s tick (rather than one setTimeout per boundary) keeps this correct even if the tab is
    // backgrounded and throttled - each tick just compares elapsed wall-clock time against the
    // fixed LOADING_MESSAGE_INTERVAL_SECONDS ladder instead of relying on a chain of precisely-
    // timed callbacks.
    const interval = setInterval(() => {
      const elapsedSeconds = (Date.now() - startedAtRef.current) / 1000
      const nextBoundary = Math.floor(elapsedSeconds / LOADING_MESSAGE_INTERVAL_SECONDS)
      setBoundary(prev => (nextBoundary > prev ? nextBoundary : prev))
    }, 1000)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    if (boundary < 1) {
      // The `immediate` initial message (picked synchronously above, boundary still 0) must
      // survive this first run - only a non-immediate mount resets to null here.
      if (!immediate) setMessage(null)
      return
    }
    const { text, index } = pickMessage(lastIndexRef.current)
    lastIndexRef.current = index
    setMessage(text)
  }, [boundary, immediate])

  return message
}
