import { useEffect, useRef, type DependencyList, type UIEvent } from 'react'
import { catchUpIn, followsChange, holdAfterScroll, pushesPastEnd } from '../utils/threadFollow'

// What counts as the end of a box five rows tall: a few pixels, where the thread allows 48.
const BOX_SLACK_PX = 8

/**
 * A box inside the thread that scrolls on its own - a tool list past five calls, a deployed agent's
 * panel - following its newest line by the THREAD'S rule (threadFollow.ts): a scroll away from the
 * end holds it for a while, every scroll restarts the while, back at the end follows at once, and
 * once the while is over it goes to the end by itself if anything arrived meanwhile.
 *
 * `deps` change whenever something was added to the box. Returns the box's ref and scroll handler.
 * The ref also listens for the wheel: one turned down at the end follows again though nothing
 * scrolls (pushesPastEnd), and the kit panel the box lives in takes only a scroll handler.
 */
export function useFollowingBox(deps: DependencyList) {
  const box = useRef<HTMLDivElement | null>(null)
  const heldUntil = useRef(0)
  const lastTop = useRef(0)
  const missed = useRef(false)
  const timer = useRef(0)
  // One stable function for add/removeEventListener, calling whatever onWheel is current.
  const wheelImpl = useRef<(e: WheelEvent) => void>(() => {})
  const onWheelRef = useRef((e: WheelEvent) => wheelImpl.current(e))

  // ONCE A FRAME, in the frame (2026-10-17). Every running deployed agent's panel follows its newest line,
  // and set at each change, reading scrollHeight forced a layout per panel per event - with eight at
  // work, the most of what the page spent. Read in the frame, the first read lays the page out and
  // the rest find it laid out: setting scrollTop changes no layout.
  const frame = useRef(0)
  const toEnd = () => {
    missed.current = false
    if (frame.current) return
    frame.current = requestAnimationFrame(() => {
      frame.current = 0
      const el = box.current
      if (el) el.scrollTop = el.scrollHeight
    })
  }

  const catchUp = () => {
    window.clearTimeout(timer.current)
    const wait = catchUpIn({ now: Date.now(), heldUntil: heldUntil.current, missed: missed.current })
    if (wait === null) return
    timer.current = window.setTimeout(() => {
      // A scroll since pushed the hold on; wait for the new end of it.
      if (heldUntil.current > Date.now()) { catchUp(); return }
      toEnd()
    }, wait + 20)
  }

  useEffect(() => {
    if (followsChange({ now: Date.now(), heldUntil: heldUntil.current, promptAppeared: false, userSent: false })) {
      toEnd()
      return
    }
    missed.current = true
    catchUp()
  }, deps)

  useEffect(() => () => {
    window.clearTimeout(timer.current)
    cancelAnimationFrame(frame.current)
  }, [])

  // Following again, with anything that arrived during the hold brought into view at once.
  const resume = () => {
    const caughtUp = heldUntil.current > 0 && missed.current
    heldUntil.current = 0
    missed.current = false
    if (caughtUp) toEnd()
  }

  wheelImpl.current = (e: WheelEvent) => {
    const el = box.current
    if (el && pushesPastEnd({ delta: e.deltaY, fromEnd: el.scrollHeight - el.scrollTop - el.clientHeight, slack: BOX_SLACK_PX })) resume()
  }

  return {
    ref: (el: HTMLDivElement | null) => {
      box.current?.removeEventListener('wheel', onWheelRef.current)
      box.current = el
      el?.addEventListener('wheel', onWheelRef.current, { passive: true })
    },
    onScroll: (e: UIEvent<HTMLDivElement>) => {
      const el = e.currentTarget
      const top = el.scrollTop
      const next = holdAfterScroll({
        top, lastTop: lastTop.current, fromEnd: el.scrollHeight - top - el.clientHeight,
        now: Date.now(), heldUntil: heldUntil.current, slack: BOX_SLACK_PX,
      })
      if (next === 0) resume()
      else heldUntil.current = next
      lastTop.current = top
    },
  }
}
