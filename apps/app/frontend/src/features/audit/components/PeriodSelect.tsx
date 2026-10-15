import { Select } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'

/**
 * THE PERIOD a summary and its log are read over - the same four choices on every tab of Logs, on
 * the LLM call log and on Administration's Usage & cost. Each drew its own Select, and the LLM log's
 * had no accessible name. Its own file so Settings, Administration and Logs can all import it
 * without one feature importing another's page.
 */
export function PeriodSelect({ days, onChange, choices = [7, 30, 90, 365], className }: {
  days: number
  onChange: (days: number) => void
  /** Which periods are offered, in days; 365 reads "Last 12 months". */
  choices?: readonly number[]
  className?: string
}) {
  const { t } = useTranslation()
  const label = (n: number) => n === 7 ? t('Last 7 days')
    : n === 30 ? t('Last 30 days')
    : n === 90 ? t('Last 90 days')
    : t('Last 12 months')
  return (
    <Select
      className={className}
      value={String(days)}
      onChange={v => onChange(Number(v))}
      ariaLabel={t('Period')}
      options={choices.map(n => ({ value: String(n), label: label(n) }))}
    />
  )
}
