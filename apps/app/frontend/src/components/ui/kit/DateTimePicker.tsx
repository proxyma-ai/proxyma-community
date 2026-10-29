/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/DateTimePicker.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Calendar, ChevronLeft, ChevronRight, Clock } from 'lucide-react'
import { cn } from './cn'
import { FIELD_BASE, fieldBorder } from './field'
import { useFloating } from './floating'
import { iconButton } from './iconButton'
import { exitingProps, usePresence } from './motion'
import { Select } from './Select'
import { textLink } from './typeScale'

/**
 * A brand-styled replacement for `<input type="datetime-local">`.
 *
 * <p>The native control's calendar popup is entirely OS-rendered and can carry no brand styling -
 * flat white and gray on every platform, against a dark glowing panel. The trigger and panel speak
 * the same visual language as `Select`.
 *
 * <p>IT LIVED IN THE APP UNTIL 2026-09-08 WHILE `.claude/rules/frontend.md` CALLED IT SHARED.
 * That sentence - "use the shared `Select` / `DateTimePicker` instead" - is read by anyone working
 * in any of the four frontends, and `Select` was in the kit while this was one app's private file.
 * A console developer following the rule would have found nothing to import and built a second
 * one, which is the shape this project keeps paying for: a documented property with nothing
 * behind it.
 *
 * <p>THE LABELS ARE PROPS, AND THAT IS THE POINT OF THE MOVE rather than a cost of it. The month
 * and weekday names were hardcoded English arrays that never reached `t()`, so they were in
 * NEITHER of `check-catalogs.mjs`'s directions - not orphaned, because the text was in the source;
 * not untranslated, because nothing asked for them - and a Vietnamese admin got "January" and "Mo"
 * from a screen that was otherwise entirely translated. A kit component cannot call `t()` at all,
 * so the app has to hand them over, which is what makes them visible to the checker.
 */
export interface DateTimePickerLabels {
  /** Shown when no value is set. */
  placeholder: string
  clear: string
  now: string
  done: string
  /** Twelve, JANUARY FIRST. */
  months: readonly string[]
  /** Seven, MONDAY FIRST - the grid is built Monday-first, see buildGridDays. */
  weekdays: readonly string[]
  /** `dateOnly`'s word for the quick pick ("Today"); `now` is used when absent. */
  today?: string
  /** The month arrows' names ("Previous month", "Next month"). */
  previousMonth: string
  nextMonth: string
  /** The time row's three selects ("Hour", "Minute", "AM or PM"). */
  hour: string
  minute: string
  period: string
}

export interface DateTimePickerProps {
  // Same "YYYY-MM-DDTHH:mm" unzoned local-string contract as native <input type="datetime-local">
  // (empty string = unset), so this drops straight into existing value/onChange wiring.
  value: string
  onChange: (value: string) => void
  labels: DateTimePickerLabels
  /**
   * BCP 47 tag for the trigger's formatted date. Defaults to the BROWSER's locale, which is the
   * wrong answer in this product: the UI language is client state a reader picks, not a header
   * the browser sent. Found in a screenshot on 2026-09-08 - a fully translated Vietnamese
   * calendar sitting behind a trigger that read "Sep 8, 2026, 2:30 PM", because the browser
   * running it was English.
   */
  locale?: string
  /**
   * A DATE, not a moment - a renewal date, a due date. The value is "YYYY-MM-DD" (the contract of
   * `<input type="date">`), the time row is gone, and picking a day commits it and closes. Added for
   * the key-account page, whose dates are calendar days the server stores as `LocalDate`: a time of
   * day there would be a field nobody can explain.
   */
  dateOnly?: boolean
  id?: string
  className?: string
}

const HOUR_OPTIONS = Array.from({ length: 12 }, (_, i) => i + 1).map(h => ({ value: String(h), label: String(h) }))
const MINUTE_OPTIONS = Array.from({ length: 60 }, (_, i) => i).map(m => ({ value: String(m), label: String(m).padStart(2, '0') }))
// AM and PM are not translated anywhere in this product - they are the clock's own notation, like
// the colon between the hours and the minutes.
const AMPM_OPTIONS = [{ value: 'AM', label: 'AM' }, { value: 'PM', label: 'PM' }]

const pad = (n: number) => String(n).padStart(2, '0')

function toValue(year: number, month: number, day: number, hour24: number, minute: number): string {
  return `${year}-${pad(month)}-${pad(day)}T${pad(hour24)}:${pad(minute)}`
}

function toDateValue(year: number, month: number, day: number): string {
  return `${year}-${pad(month)}-${pad(day)}`
}

// Read by hand rather than through `new Date(v)`: a bare "YYYY-MM-DD" is parsed as UTC midnight,
// which is the previous day anywhere west of Greenwich.
function parseValue(v: string) {
  if (!v) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/.exec(v)
  if (!m) return null
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])]
  if (month < 1 || month > 12 || day < 1 || day > 31) return null
  return { year, month, day, hour24: m[4] ? Number(m[4]) : 0, minute: m[5] ? Number(m[5]) : 0 }
}

function to12Hour(hour24: number): { hour12: number; ampm: 'AM' | 'PM' } {
  const ampm: 'AM' | 'PM' = hour24 >= 12 ? 'PM' : 'AM'
  const hour12 = hour24 % 12 === 0 ? 12 : hour24 % 12
  return { hour12, ampm }
}

function to24Hour(hour12: number, ampm: 'AM' | 'PM'): number {
  if (ampm === 'AM') return hour12 === 12 ? 0 : hour12
  return hour12 === 12 ? 12 : hour12 + 12
}

// Days-in-grid: Monday-first 6x7 grid, including the leading and trailing days of adjacent months
// needed to fill it. Monday-first is this product's day-of-week convention throughout.
function buildGridDays(viewYear: number, viewMonth: number): Array<{ year: number; month: number; day: number; inMonth: boolean }> {
  const firstOfMonth = new Date(viewYear, viewMonth - 1, 1)
  const mondayIndex = (firstOfMonth.getDay() + 6) % 7
  const gridStart = new Date(viewYear, viewMonth - 1, 1 - mondayIndex)
  return Array.from({ length: 42 }, (_, i) => {
    const d = new Date(gridStart.getFullYear(), gridStart.getMonth(), gridStart.getDate() + i)
    return { year: d.getFullYear(), month: d.getMonth() + 1, day: d.getDate(), inMonth: d.getMonth() + 1 === viewMonth }
  })
}

/**
 * Since 2026-10-14: the trigger is a FIELD (`FIELD_BASE`, so it matches the inputs beside it), the
 * calendar is a PORTAL on the floating layer (it was `absolute z-20` inside the field and clipped
 * by any dialog), the month arrows are `iconButton`s with names, every day says its whole date to
 * a screen reader, and the keyboard walks the grid - arrows by a day or a week, Home/End to the
 * week's ends, PageUp/PageDown by a month - with Tab kept inside the calendar and Escape closing it
 * back onto the field.
 */
export function DateTimePicker({ value, onChange, labels, locale, dateOnly, id, className }: DateTimePickerProps) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  // Set by a press inside the calendar - its own time Selects are portals of their own, outside it
  // in the DOM but inside it in the React tree, and a press there must not close it.
  const pressedInside = useRef(false)
  const { mounted, exiting } = usePresence(open)
  const floating = useFloating(triggerRef, panelRef, { open, mounted, placement: 'bottom-start' })
  const parsed = parseValue(value)
  const today = new Date()

  const [viewYear, setViewYear] = useState(parsed?.year ?? today.getFullYear())
  const [viewMonth, setViewMonth] = useState(parsed?.month ?? today.getMonth() + 1)
  // The day the keyboard is on, and whether it should take focus after the next render.
  const [cursor, setCursor] = useState<{ year: number; month: number; day: number } | null>(null)
  const focusCursor = useRef(false)

  useEffect(() => {
    if (!open) { setCursor(null); return }
    const start = parsed ?? { year: today.getFullYear(), month: today.getMonth() + 1, day: today.getDate() }
    setViewYear(start.year)
    setViewMonth(start.month)
    setCursor({ year: start.year, month: start.month, day: start.day })
    // Opened from the keyboard, focus goes onto the chosen day (or today).
    let keyboard = false
    try { keyboard = !!triggerRef.current?.matches(':focus-visible') } catch { keyboard = false }
    focusCursor.current = keyboard
    // Only re-sync the visible month when the popover opens, not on every keystroke/value
    // change - otherwise picking a time would snap the calendar back to the selected day's month
    // mid-interaction.
  }, [open])

  useEffect(() => {
    if (!focusCursor.current || !cursor || !floating.style || floating.style.visibility === 'hidden') return
    focusCursor.current = false
    panelRef.current?.querySelector<HTMLElement>(`[data-date="${toDateValue(cursor.year, cursor.month, cursor.day)}"]`)?.focus()
  })

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent) => {
      if (pressedInside.current) { pressedInside.current = false; return }
      if (containerRef.current?.contains(e.target as Node)) return
      setOpen(false)
    }
    const onEscape = (e: KeyboardEvent) => {
      // preventDefault marks the key as handled, so a dialog beneath this closes on the NEXT Escape
      // rather than on this one (see useModalLayer). A Select inside that took the key keeps it.
      if (e.key === 'Escape' && !e.defaultPrevented) {
        e.preventDefault()
        setOpen(false)
        triggerRef.current?.focus({ preventScroll: true })
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onEscape)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onEscape)
    }
  }, [open])

  const { hour12, ampm } = to12Hour(parsed?.hour24 ?? 12)
  const minute = parsed?.minute ?? 0

  const commitDay = (year: number, month: number, day: number) => {
    if (dateOnly) {
      onChange(toDateValue(year, month, day))
      setOpen(false)
      triggerRef.current?.focus({ preventScroll: true })
      return
    }
    onChange(toValue(year, month, day, parsed?.hour24 ?? 12, minute))
  }
  const commitHour12 = (h: string) => {
    const hour24 = to24Hour(Number(h), ampm)
    const base = parsed ?? { year: today.getFullYear(), month: today.getMonth() + 1, day: today.getDate() }
    onChange(toValue(base.year, base.month, base.day, hour24, minute))
  }
  const commitMinute = (m: string) => {
    const base = parsed ?? { year: today.getFullYear(), month: today.getMonth() + 1, day: today.getDate(), hour24: 12 }
    onChange(toValue(base.year, base.month, base.day, base.hour24 ?? 12, Number(m)))
  }
  const commitAmpm = (a: string) => {
    const hour24 = to24Hour(hour12, a as 'AM' | 'PM')
    const base = parsed ?? { year: today.getFullYear(), month: today.getMonth() + 1, day: today.getDate() }
    onChange(toValue(base.year, base.month, base.day, hour24, minute))
  }

  const close = () => {
    setOpen(false)
    triggerRef.current?.focus({ preventScroll: true })
  }
  const setNow = () => {
    const n = new Date()
    onChange(dateOnly
      ? toDateValue(n.getFullYear(), n.getMonth() + 1, n.getDate())
      : toValue(n.getFullYear(), n.getMonth() + 1, n.getDate(), n.getHours(), n.getMinutes()))
    close()
  }
  const clear = () => {
    onChange('')
    close()
  }

  const goPrevMonth = () => {
    if (viewMonth === 1) { setViewYear(y => y - 1); setViewMonth(12) } else { setViewMonth(m => m - 1) }
  }
  const goNextMonth = () => {
    if (viewMonth === 12) { setViewYear(y => y + 1); setViewMonth(1) } else { setViewMonth(m => m + 1) }
  }

  // The keyboard on a day: a day or a week with the arrows, the week's ends with Home/End, a month
  // with PageUp/PageDown. The calendar follows the cursor into the next or previous month.
  const onDayKey = (e: React.KeyboardEvent<HTMLButtonElement>, year: number, month: number, day: number) => {
    const d = new Date(year, month - 1, day)
    const weekday = (d.getDay() + 6) % 7
    const moves: Record<string, () => void> = {
      ArrowLeft: () => d.setDate(d.getDate() - 1),
      ArrowRight: () => d.setDate(d.getDate() + 1),
      ArrowUp: () => d.setDate(d.getDate() - 7),
      ArrowDown: () => d.setDate(d.getDate() + 7),
      Home: () => d.setDate(d.getDate() - weekday),
      End: () => d.setDate(d.getDate() + (6 - weekday)),
      PageUp: () => d.setMonth(d.getMonth() - 1),
      PageDown: () => d.setMonth(d.getMonth() + 1),
    }
    const move = moves[e.key]
    if (!move) return
    e.preventDefault()
    move()
    setViewYear(d.getFullYear())
    setViewMonth(d.getMonth() + 1)
    setCursor({ year: d.getFullYear(), month: d.getMonth() + 1, day: d.getDate() })
    focusCursor.current = true
  }

  // Tab stays inside the calendar while it is open - it is a portal at the end of the page, and the
  // next stop after it would be nothing at all.
  const onPanelKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Tab' || e.defaultPrevented) return
    const panel = panelRef.current
    if (!panel) return
    const stops = Array.from(panel.querySelectorAll<HTMLElement>('button:not([disabled])')).filter(el => el.tabIndex >= 0)
    if (stops.length === 0) return
    const at = stops.indexOf(document.activeElement as HTMLElement)
    const next = e.shiftKey ? (at <= 0 ? stops.length - 1 : at - 1) : (at === stops.length - 1 ? 0 : at + 1)
    e.preventDefault()
    stops[next].focus()
  }

  const displayText = !parsed ? ''
    : dateOnly
      ? new Date(parsed.year, parsed.month - 1, parsed.day).toLocaleDateString(locale, { year: 'numeric', month: 'short', day: 'numeric' })
      : new Date(parsed.year, parsed.month - 1, parsed.day, parsed.hour24, parsed.minute)
        .toLocaleString(locale, { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })

  const gridDays = buildGridDays(viewYear, viewMonth)
  const cursorKey = cursor ? toDateValue(cursor.year, cursor.month, cursor.day) : null
  const anyInGrid = gridDays.some(g => toDateValue(g.year, g.month, g.day) === cursorKey)

  return (
    <div className={cn('relative', className)} ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        id={id}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen(o => !o)}
        className={cn(
          FIELD_BASE, fieldBorder(undefined),
          'flex items-center gap-2 pl-3 pr-3 text-left',
          open && 'border-brand-solid',
        )}
      >
        <Calendar size={14} className="shrink-0 text-ink-caption" aria-hidden="true" />
        <span className={cn('truncate text-left flex-1', !displayText && 'text-ink-disabled')}>
          {displayText || labels.placeholder}
        </span>
      </button>

      {mounted && createPortal(
        <div
          ref={panelRef}
          role="dialog"
          aria-label={displayText || labels.placeholder}
          {...exitingProps(exiting)}
          data-side={floating.side}
          style={floating.style}
          onPointerDownCapture={() => { pressedInside.current = true }}
          onKeyDown={onPanelKey}
          className={cn('fixed z-float w-72 surface-float border rounded-lg shadow-float p-3 animate-dropdown', exiting && 'animate-pop-out')}
        >
          <div className="flex items-center justify-between mb-2">
            <button type="button" onClick={goPrevMonth} aria-label={labels.previousMonth} className={iconButton('sm')}>
              <ChevronLeft size={16} aria-hidden="true" />
            </button>
            <span className="text-sm font-medium text-ink-primary" aria-live="polite">
              {labels.months[viewMonth - 1]} {viewYear}
            </span>
            <button type="button" onClick={goNextMonth} aria-label={labels.nextMonth} className={iconButton('sm')}>
              <ChevronRight size={16} aria-hidden="true" />
            </button>
          </div>

          <div className="grid grid-cols-7 gap-0.5 mb-1" aria-hidden="true">
            {labels.weekdays.map(w => (
              <span key={w} className="text-xs text-ink-caption text-center py-1">{w}</span>
            ))}
          </div>
          <div className="grid grid-cols-7 gap-0.5 justify-items-center">
            {gridDays.map(({ year, month, day, inMonth }, i) => {
              const key = toDateValue(year, month, day)
              const isSelected = !!parsed && parsed.year === year && parsed.month === month && parsed.day === day
              const isToday = today.getFullYear() === year && today.getMonth() + 1 === month && today.getDate() === day
              // One tab stop in the grid: the cursor's day, or the first of the month.
              const isStop = cursorKey && anyInGrid ? key === cursorKey : inMonth && day === 1 && i < 7
              return (
                <button
                  key={key}
                  type="button"
                  data-date={key}
                  tabIndex={isStop ? 0 : -1}
                  aria-label={new Date(year, month - 1, day).toLocaleDateString(locale, { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })}
                  aria-pressed={isSelected}
                  aria-current={isToday ? 'date' : undefined}
                  onClick={() => commitDay(year, month, day)}
                  onKeyDown={e => onDayKey(e, year, month, day)}
                  className={cn(
                    'h-7 w-7 touch:h-9 touch:w-9 flex items-center justify-center rounded-md text-xs data-readout transition-colors focus-ring',
                    !inMonth && 'text-ink-disabled',
                    inMonth && !isSelected && 'text-ink-secondary mouse:hover:bg-ink-caption/10 mouse:hover:text-ink-primary',
                    isSelected && 'bg-brand-solid text-brand-on-solid font-medium',
                    !isSelected && isToday && 'ring-1 ring-inset ring-brand-accent',
                  )}
                >
                  {day}
                </button>
              )
            })}
          </div>

          {!dateOnly && (
            <div className="flex items-center gap-1 mt-3 pt-3 border-t border-line/30">
              <Clock size={16} className="shrink-0 text-ink-caption mr-1" aria-hidden="true" />
              <Select className="w-16" value={String(hour12)} onChange={commitHour12} options={HOUR_OPTIONS} ariaLabel={labels.hour} />
              <span className="text-ink-caption" aria-hidden="true">:</span>
              <Select className="w-16" value={String(minute)} onChange={commitMinute} options={MINUTE_OPTIONS} ariaLabel={labels.minute} />
              <Select className="w-20" value={ampm} onChange={commitAmpm} options={AMPM_OPTIONS} ariaLabel={labels.period} />
            </div>
          )}

          <div className="flex items-center justify-between mt-3 pt-3 border-t border-line/30">
            <button type="button" onClick={clear} className={cn(textLink('quiet'), 'text-xs')}>
              {labels.clear}
            </button>
            <div className="flex items-center gap-3">
              <button type="button" onClick={setNow} className={cn(textLink('brand'), 'text-xs')}>
                {dateOnly ? labels.today ?? labels.now : labels.now}
              </button>
              <button type="button" onClick={close} className={cn(textLink('quiet'), 'text-xs text-ink-primary')}>
                {labels.done}
              </button>
            </div>
          </div>
        </div>,
        document.body,
      )}
    </div>
  )
}
