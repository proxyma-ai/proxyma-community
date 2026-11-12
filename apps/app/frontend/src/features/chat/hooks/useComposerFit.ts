import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { composerFit, type ComposerFit } from '../utils/composerFit'

// What the composer's controls row measures to decide how much it says (composerFit.ts):
//  - room   the left side of the row, flex-1 - the composer's width less the right side (the agents,
//           the rings, Send), so a ring or a "tokens freed" note arriving there narrows it
//  - lead   the control before the two that fold (the + menu), as it stands
//  - modeFull, modeIcon, modelFull, modelIcon
//           each folding control in BOTH forms, drawn in an invisible box beside the row, so the
//           words' width is known while the icon is showing - a mode switched, a model changed or
//           the language changed while folded is measured the same as one on screen
const PARTS = ['room', 'lead', 'modeFull', 'modeIcon', 'modelFull', 'modelIcon'] as const
type Part = typeof PARTS[number]
type PartRef = (el: HTMLElement | null) => void

/**
 * THE COMPOSER'S OWN WIDTH decides whether its labels show (the maintainer's feedback, 2026-11-02).
 * A ResizeObserver on the composer's parts, never the viewport: the same composer sits in the chat
 * page, a key account's panel and a Constellations agent's chat. Each part is a ref callback, so a
 * part that comes later (the model, once the agent's settings load) is observed when it arrives.
 * Measured once before the first paint as well, so a narrow panel never flashes its labels.
 */
export function useComposerFit(): { fit: ComposerFit; refs: Record<Part, PartRef> } {
  const els = useRef<Partial<Record<Part, HTMLElement>>>({})
  const observer = useRef<ResizeObserver | null>(null)
  const [fit, setFit] = useState<ComposerFit>('full')

  const measure = useCallback(() => {
    const e = els.current
    if (!e.room) return
    const width = (el?: HTMLElement) => (el ? el.getBoundingClientRect().width : 0)
    const gap = parseFloat(getComputedStyle(e.room).columnGap) || 0
    setFit(composerFit({
      room: width(e.room),
      // composerFit adds the gap between the lead and each folding control.
      others: width(e.lead),
      gap,
      modeFull: width(e.modeFull),
      modeIcon: width(e.modeIcon),
      modelFull: width(e.modelFull),
      modelIcon: width(e.modelIcon),
    }))
  }, [])

  const refs = useMemo(() => {
    const out = {} as Record<Part, PartRef>
    for (const part of PARTS) {
      out[part] = el => {
        const prev = els.current[part]
        if (prev === el) return
        if (prev) observer.current?.unobserve(prev)
        if (el) {
          els.current[part] = el
          // Observing calls back once with the element's size, which measures.
          observer.current?.observe(el)
        } else {
          delete els.current[part]
          // A part that went (the model hidden) changes nothing a ResizeObserver sees.
          requestAnimationFrame(measure)
        }
      }
    }
    return out
  }, [measure])

  useLayoutEffect(() => {
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => measure())
    observer.current = ro
    for (const el of Object.values(els.current)) if (el) ro.observe(el)
    return () => {
      ro.disconnect()
      observer.current = null
    }
  }, [measure])

  return { fit, refs }
}
