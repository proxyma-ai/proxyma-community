import { useMemo } from 'react'
import { useTranslation } from '@/i18n'

/**
 * DATES IN THE READER'S LANGUAGE, not the browser's. `toLocaleString(undefined, ...)` formats in
 * the BROWSER's locale, so a reader who switched the app to Vietnamese in an English browser read
 * "Oct 13, 2026" beside Vietnamese text - the defect the app's DateTimePicker wrapper fixed for
 * itself by passing its locale on. Settings, Administration and Logs had eight private formatters
 * between them, half printing seconds; these are the one set, minutes and never seconds ("a ticking
 * clock on a weekly reset").
 */
export interface DateFormats {
  /** "Oct 14, 2026" */
  date: (iso: string | number | Date) => string
  /** "Oct 14, 2026, 7:25 AM" */
  dateTime: (iso: string | number | Date) => string
  /** "7:25 AM" */
  time: (iso: string | number | Date) => string
}

export function useDates(): DateFormats {
  const { locale } = useTranslation()
  return useMemo(() => {
    const date = new Intl.DateTimeFormat(locale, { dateStyle: 'medium' })
    const dateTime = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' })
    const time = new Intl.DateTimeFormat(locale, { timeStyle: 'short' })
    // An unreadable value prints a dash: Intl throws on an invalid date, and a page is not worth
    // losing to one bad timestamp.
    const fmt = (f: Intl.DateTimeFormat) => (v: string | number | Date) => {
      const d = v instanceof Date ? v : new Date(v)
      return Number.isNaN(d.getTime()) ? '-' : f.format(d)
    }
    return { date: fmt(date), dateTime: fmt(dateTime), time: fmt(time) }
  }, [locale])
}
