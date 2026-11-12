import type { ReactNode } from 'react'
import { Field, FormColumns, InlineNotice, Input, Presence, SwitchRow, cn } from '@/components/ui/kit'
import { EditableText } from '@/components/ui/EditableText'
import { quietly } from '@/components/ui/AutosaveFields'
import { wholeNumberProblem } from '@/components/ui/autosave'
import { useTranslation } from '@/i18n'
import type { WebCrawlLimits } from '../services/indexingApi'

/**
 * WHAT A SOURCE COVERS, said once for the dialog that adds it and the dialog that changes it.
 *
 * <p>The two were written apart and drifted: a share's subfolders were "Include subfolders" when
 * adding and "Scan subdirectories recursively" when editing; a crawl's depth was "Max depth" and
 * "Crawl depth", its domain rule and its JavaScript switch worded twice, and its page budget
 * defaulted to 100 in one and 200 in the other. One component per setting, so a word changed here
 * changes in both.
 */

/** Whether a folder, share, repository or mail folder is read with everything under it. */
export function SubfoldersSwitch({ checked, onChange, disabled, className }: {
  checked: boolean
  onChange: (on: boolean) => void
  disabled?: boolean
  className?: string
}) {
  const { t } = useTranslation()
  return (
    <SwitchRow
      className={cn('py-0', className)}
      title={t('Include subfolders')}
      checked={checked}
      onChange={onChange}
      disabled={disabled}
    />
  )
}

export interface WebCrawlValues {
  /** As typed - a field may be empty or out of range while it is being edited. */
  maxDepth: string
  maxPages: string
  sameDomainOnly: boolean
  renderJs: boolean
}

/**
 * A crawl's bounds and switches: the depth and page budget side by side against the server's own
 * ceilings, the warning a large budget earns, and the two switches. `children` sits between the
 * budget and the switches (the settings dialog's URL filter).
 *
 * <p>Given `onCommit` it is an EXISTING source's (the maintainer, 2026-11-02): each bound is
 * click-to-edit and saved when it is left, a number outside the server's ceilings refused before it
 * is sent, and each switch saved as it changes. `onCommit` may return a promise; a refusal puts the
 * value back. Without it, a form whose `onChange` hears every keystroke (adding a website).
 */
export function WebCrawlFields({ values, onChange, onCommit, limits, errors = {}, disabled, children }: {
  values: WebCrawlValues
  onChange?: (patch: Partial<WebCrawlValues>) => void
  onCommit?: (patch: Partial<WebCrawlValues>) => void | Promise<unknown>
  limits: WebCrawlLimits
  errors?: { maxDepth?: string; maxPages?: string }
  disabled?: boolean
  children?: ReactNode
}) {
  const { t, locale } = useTranslation()
  const pages = Number(values.maxPages)
  const depthProblem = (text: string) => (wholeNumberProblem(text, 0, limits.maxDepth)
    ? t('Crawl depth must be a whole number between 0 and {max}.', { max: limits.maxDepth }) : undefined)
  const pagesProblem = (text: string) => (wholeNumberProblem(text, 1, limits.maxPages)
    ? t('Page limit must be a whole number between 1 and {max}.', { max: limits.maxPages }) : undefined)
  // A switch: saved as it changes, or a form's change.
  const flip = (patch: Partial<WebCrawlValues>) => (onCommit ? quietly(onCommit(patch)) : onChange?.(patch))
  // A bound, checked before it is sent - a refused one goes back to what is saved.
  const bound = (key: 'maxDepth' | 'maxPages', problem: (text: string) => string | undefined) => (text: string) => {
    const refused = problem(text)
    return refused ? Promise.reject(new Error(refused)) : onCommit?.({ [key]: text.trim() })
  }
  return (
    <>
      {onCommit ? (
        <FormColumns>
          <Field label={t('Crawl depth')} hint={t('Link hops from the starting URL (max {max})', { max: limits.maxDepth })} error={errors.maxDepth}>
            <EditableText
              value={values.maxDepth}
              label={t('Crawl depth')}
              editable={!disabled}
              className="data-readout"
              validate={depthProblem}
              onCommit={bound('maxDepth', depthProblem)}
            />
          </Field>
          <Field label={t('Max pages')} hint={t('Hard ceiling on the crawl (max {max})', { max: limits.maxPages })} error={errors.maxPages}>
            <EditableText
              value={values.maxPages}
              label={t('Max pages')}
              editable={!disabled}
              className="data-readout"
              validate={pagesProblem}
              onCommit={bound('maxPages', pagesProblem)}
            />
          </Field>
        </FormColumns>
      ) : (
      <FormColumns>
        <Input
          label={t('Crawl depth')}
          type="number"
          min={0}
          max={limits.maxDepth}
          value={values.maxDepth}
          error={errors.maxDepth}
          disabled={disabled}
          hint={t('Link hops from the starting URL (max {max})', { max: limits.maxDepth })}
          onChange={e => onChange?.({ maxDepth: e.target.value })}
        />
        <Input
          label={t('Max pages')}
          type="number"
          min={1}
          max={limits.maxPages}
          value={values.maxPages}
          error={errors.maxPages}
          disabled={disabled}
          hint={t('Hard ceiling on the crawl (max {max})', { max: limits.maxPages })}
          onChange={e => onChange?.({ maxPages: e.target.value })}
        />
      </FormColumns>
      )}
      {/* A crawl is rate-limited for politeness and fetches every page one at a time, so a large
          page budget is genuinely hours of work, not minutes. Saying so where the number is set is
          the difference between "it is still going" and "it is broken". */}
      <Presence show={pages > 1000}>
        <InlineNotice tone="warning">
          {t('Crawling {pages} pages takes hours.',
            { pages: pages.toLocaleString(locale) })}
        </InlineNotice>
      </Presence>
      {children}
      <SwitchRow
        className="py-0"
        title={t('Stay on the same domain')}
        checked={values.sameDomainOnly}
        onChange={sameDomainOnly => flip({ sameDomainOnly })}
        disabled={disabled}
      />
      <SwitchRow
        className="py-0"
        title={t('Render JavaScript')}
        caption={t('Slower. Needed for client-rendered sites.')}
        checked={values.renderJs}
        onChange={renderJs => flip({ renderJs })}
        disabled={disabled}
      />
    </>
  )
}
