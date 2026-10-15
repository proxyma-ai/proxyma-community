'use client'

import type { ReactNode } from 'react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { cn } from './cn'

// A layout effect in the browser, an ordinary one on the server (the landing site renders this).
const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect

export interface SegmentedOption<T extends string> {
  value: T
  label: ReactNode
  /** `warning` for a choice that overwrites or discards (an upload's Overwrite). */
  tone?: 'default' | 'warning'
  disabled?: boolean
  /** Accessible name when `label` is not text. */
  ariaLabel?: string
}

interface Props<T extends string> {
  options: readonly SegmentedOption<T>[]
  /** `null` while nothing is chosen yet - the first enabled option is then the tab stop. */
  value: T | null
  onChange: (value: T) => void
  ariaLabel: string
  size?: 'sm' | 'md'
  /**
   * `boxed` (the default) is a track with the choice drawn in the brand-chip pair. `text` is a row
   * of words with the choice in primary ink - for a chart's period picker, where a box would
   * outweigh the chart it governs.
   */
  variant?: 'boxed' | 'text'
  disabled?: boolean
  className?: string
}

/**
 * ONE OF A FEW, ALL VISIBLE - a `radiogroup` with arrow keys.
 *
 * Seven of these existed and none in the kit: a boxed track with a solid brand selection, two kit
 * Buttons primary/secondary, `bg-brand-solid/20` pills, `bg-warning/20` pills, underlined words,
 * and a radio group. The choice is drawn in the brand-chip pair rather than a solid brand fill,
 * because a solid fill is the primary BUTTON's look and read as "press this", not "this is chosen".
 */
export function SegmentedControl<T extends string>({
  options, value, onChange, ariaLabel, size = 'sm', variant = 'boxed', disabled, className,
}: Props<T>) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  const trackRef = useRef<HTMLDivElement>(null)
  // With no option chosen every option was tabIndex -1, and the group could not be reached from
  // the keyboard at all (the feedback mood picker, an unresolved upload row).
  const chosen = options.findIndex(o => o.value === value)
  const tabStop = chosen >= 0 ? chosen : options.findIndex(o => !o.disabled)

  // THE CHOICE SLIDES (boxed only): one fill under the options, moved to the chosen one, instead of
  // a fill on each button that jumped between them. Measured from the button itself, so labels of
  // any width work; re-measured when the track changes size (a font arriving, a translated label).
  // No transition until the first measurement has landed, so it never slides in from the left edge.
  const [slot, setSlot] = useState<{ left: number; width: number } | null>(null)
  const [placed, setPlaced] = useState(false)
  useIsoLayoutEffect(() => {
    if (variant !== 'boxed') return
    const measure = () => {
      const el = chosen >= 0 ? refs.current[chosen] : null
      setSlot(el ? { left: el.offsetLeft, width: el.offsetWidth } : null)
    }
    measure()
    const track = trackRef.current
    if (!track || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(measure)
    ro.observe(track)
    return () => ro.disconnect()
  }, [chosen, variant, size, options.length])
  useEffect(() => {
    if (!slot || placed) return
    const id = requestAnimationFrame(() => setPlaced(true))
    return () => cancelAnimationFrame(id)
  }, [slot, placed])
  const warning = chosen >= 0 && options[chosen].tone === 'warning'

  const move = (from: number, step: number) => {
    const n = options.length
    for (let i = 1; i <= n; i++) {
      const idx = (from + step * i + n) % n
      if (!options[idx].disabled) {
        onChange(options[idx].value)
        refs.current[idx]?.focus()
        return
      }
    }
  }

  return (
    <div
      ref={trackRef}
      role="radiogroup"
      aria-label={ariaLabel}
      aria-disabled={disabled || undefined}
      className={cn(
        'inline-flex items-center',
        variant === 'boxed' ? 'relative rounded-md border border-line bg-canvas p-0.5 gap-0.5' : 'gap-3',
        disabled && 'opacity-40 pointer-events-none',
        className,
      )}
    >
      {variant === 'boxed' && slot && (
        <span
          aria-hidden="true"
          className={cn(
            'absolute top-0.5 bottom-0.5 left-0 rounded-[5px] pointer-events-none',
            placed && 'transition-[transform,width,background-color] duration-base ease-out',
            warning ? 'bg-warning/15' : 'bg-brand-chip-bg',
          )}
          style={{ width: slot.width, transform: `translateX(${slot.left}px)` }}
        />
      )}
      {options.map((o, i) => {
        const on = o.value === value
        return (
          <button
            key={o.value}
            ref={el => { refs.current[i] = el }}
            type="button"
            role="radio"
            aria-checked={on}
            aria-label={o.ariaLabel}
            disabled={o.disabled}
            tabIndex={i === tabStop ? 0 : -1}
            onClick={() => onChange(o.value)}
            onKeyDown={e => {
              if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); move(i, 1) }
              if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); move(i, -1) }
            }}
            className={cn(
              'inline-flex items-center justify-center gap-1.5 whitespace-nowrap transition-colors focus-ring',
              'disabled:opacity-40 disabled:cursor-not-allowed',
              variant === 'boxed' && cn(
                // Above the sliding fill; the chosen one's fill IS that fill, so only its ink is here.
                'relative rounded-[5px] font-medium touch:min-h-tap-sm',
                size === 'sm' ? 'h-6 px-2 text-xs' : 'h-8 px-3 text-sm',
                on
                  ? cn(o.tone === 'warning' ? 'text-warning' : 'text-brand-chip-text', !slot && (o.tone === 'warning' ? 'bg-warning/15' : 'bg-brand-chip-bg'))
                  : 'text-ink-caption mouse:hover:text-ink-primary',
              ),
              variant === 'text' && cn(
                size === 'sm' ? 'text-xs' : 'text-sm',
                on ? 'text-ink-primary font-semibold' : 'text-ink-caption mouse:hover:text-ink-primary',
              ),
            )}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}
