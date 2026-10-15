import { DateTimePicker as KitDateTimePicker } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'

interface DateTimePickerProps {
  // Same "YYYY-MM-DDTHH:mm" unzoned local-string contract as native <input type="datetime-local">
  // (empty string = unset), so this drops straight into existing value/onChange wiring.
  value: string
  onChange: (value: string) => void
  placeholder?: string
  /** A calendar day, "YYYY-MM-DD", with no time row - see the kit's `dateOnly`. */
  dateOnly?: boolean
  id?: string
  className?: string
}

/**
 * The kit's date picker, wired to this app's catalog.
 *
 * <p>THE MONTH AND WEEKDAY NAMES ARE THE REASON THIS WRAPPER EXISTS. They were hardcoded English
 * arrays inside the component, so they reached no `t()` and were in NEITHER of the checker's
 * directions - not orphaned, because the text was in the source; not untranslated, because nothing
 * asked for them. A Vietnamese admin opening the maintenance scheduler got "January" and "Mo" on a
 * screen that was otherwise fully translated, and every run said "all in use".
 *
 * <p>Written out one `t()` call at a time rather than built from a loop over an array of English
 * names: a string reaching `t()` as a VARIABLE is invisible to the untranslated direction too, so
 * a loop would have moved the hole rather than closed it. Twelve literals and seven literals is
 * what the checker can actually see.
 */
export function DateTimePicker({ value, onChange, placeholder, dateOnly, id, className }: DateTimePickerProps) {
  const { t, locale } = useTranslation()

  return (
    <KitDateTimePicker
      value={value}
      onChange={onChange}
      dateOnly={dateOnly}
      id={id}
      className={className}
      // THE APP'S LOCALE, not the browser's. The trigger's date is formatted by Intl, which
      // defaults to the browser - so a reader who switched this app to Vietnamese in an English
      // browser got "Sep 8, 2026" above a calendar reading "Thang 9".
      locale={locale}
      labels={{
        placeholder: placeholder ?? (dateOnly ? t('Select a date') : t('Select date & time')),
        clear: t('Clear'),
        now: t('Now'),
        today: t('Today'),
        done: t('Done'),
        previousMonth: t('Previous month'),
        nextMonth: t('Next month'),
        hour: t('Hour'),
        minute: t('Minute'),
        period: t('AM or PM'),
        months: [
          t('January'), t('February'), t('March'), t('April'), t('May'), t('June'),
          t('July'), t('August'), t('September'), t('October'), t('November'), t('December'),
        ],
        // Monday first, and deliberately the two-letter forms: seven columns inside a 288px
        // popover leave about 40px each.
        weekdays: [t('Mo'), t('Tu'), t('We'), t('Th'), t('Fr'), t('Sa'), t('Su')],
      }}
    />
  )
}
