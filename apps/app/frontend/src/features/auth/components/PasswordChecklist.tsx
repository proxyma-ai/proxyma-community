import { RuleChecklist } from '@/components/ui/kit'
import type { ValidationRule } from '../hooks/usePasswordValidation'
import { useTranslation } from '@/i18n'

/** The password rules as they are met - the kit `RuleChecklist`, with each rule's label translated
 *  here (the rules are plain data; see usePasswordValidation and its DYNAMIC entry). */
export function PasswordChecklist({ results, className }: {
  results: Array<ValidationRule & { pass: boolean }>
  className?: string
}) {
  const { t } = useTranslation()
  return <RuleChecklist className={className} items={results.map(r => ({ key: r.id, label: t(r.label), pass: r.pass }))} />
}
