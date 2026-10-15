import { useEffect, useRef, useState } from 'react'
import { prefersReducedMotion } from '@/components/ui/kit'

const SCRAMBLE_CHARSET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
const CHAR_STAGGER_MS = 50
const SCRAMBLE_STEPS = 5
const SCRAMBLE_STEP_MS = 10

function randomChar(): string {
  return SCRAMBLE_CHARSET[Math.floor(Math.random() * SCRAMBLE_CHARSET.length)]
}

// Reveals `text` left-to-right instead of swapping it in all at once: each character position
// starts its own reveal CHAR_STAGGER_MS after the previous one, cycling through SCRAMBLE_STEPS
// random characters (SCRAMBLE_STEP_MS apart) before settling on its real, final character - a
// "decoding" effect rather than an instant text swap. `done` flips true once the last character
// has settled, so a caller can e.g. start a blinking cursor only after the reveal finishes.
export function useScrambleText(text: string): { display: string; done: boolean } {
  const [display, setDisplay] = useState(text)
  const [done, setDone] = useState(true)
  const timeoutIdsRef = useRef<number[]>([])

  useEffect(() => {
    timeoutIdsRef.current.forEach(clearTimeout)
    timeoutIdsRef.current = []

    // Under reduced motion the words are simply there (CHAT-37).
    if (prefersReducedMotion()) {
      setDisplay(text)
      setDone(true)
      return
    }

    const target = [...text]
    const chars = target.map(() => ' ')
    setDisplay(chars.join(''))
    setDone(target.length === 0)

    target.forEach((finalChar, i) => {
      const startDelay = i * CHAR_STAGGER_MS
      for (let step = 0; step < SCRAMBLE_STEPS; step++) {
        const id = window.setTimeout(() => {
          chars[i] = randomChar()
          setDisplay(chars.join(''))
        }, startDelay + step * SCRAMBLE_STEP_MS)
        timeoutIdsRef.current.push(id)
      }
      const finalId = window.setTimeout(() => {
        chars[i] = finalChar
        setDisplay(chars.join(''))
        if (i === target.length - 1) setDone(true)
      }, startDelay + SCRAMBLE_STEPS * SCRAMBLE_STEP_MS)
      timeoutIdsRef.current.push(finalId)
    })

    return () => {
      timeoutIdsRef.current.forEach(clearTimeout)
      timeoutIdsRef.current = []
    }
  }, [text])

  return { display, done }
}
