'use client'

import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { cn } from './cn'
import { textLink } from './typeScale'
import { usePhone } from './usePhone'

/**
 * LONG WORDS CUT SHORT ON A PHONE, WITH THE WHOLE A TAP AWAY (2026-11-16, the mobile audit: one long YouTube comment
 * made an approval card several screens tall, its Approve and Deny at the foot). Below `sm` the text is held to `lines`
 * and, only when it runs longer, "Show more" opens it whole ("Show less" closes it again). From `sm` - or with
 * `always` - nothing is cut. The words come in translated.
 */
const CLAMP = { 3: 'line-clamp-3', 4: 'line-clamp-4', 6: 'line-clamp-6' } as const

export function ShowMore({ children, lines = 6, more, less, always = false, className }: {
  children: ReactNode
  lines?: 3 | 4 | 6
  more: string
  less: string
  /** Cut at every width, not on a phone only. */
  always?: boolean
  className?: string
}) {
  const phone = usePhone()
  const cut = always || phone
  const [open, setOpen] = useState(false)
  const [long, setLong] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const el = box.current
    if (!el || !cut || open) return
    const measure = () => setLong(el.scrollHeight > el.clientHeight + 1)
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const watch = new ResizeObserver(measure)
    watch.observe(el)
    return () => watch.disconnect()
  }, [cut, open, children])
  const clamped = cut && !open
  return (
    <div className={className}>
      <div ref={box} className={cn(clamped && CLAMP[lines])}>
        {children}
      </div>
      {cut && (long || open) && (
        <button type="button" onClick={() => setOpen(o => !o)} className={cn(textLink('quiet'), 'mt-1 text-xs tap-area')}>
          {open ? less : more}
        </button>
      )}
    </div>
  )
}
