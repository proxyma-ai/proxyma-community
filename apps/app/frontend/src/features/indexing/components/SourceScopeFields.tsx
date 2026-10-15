import type { ReactNode } from 'react'
import { FormColumns, InlineNotice, Input, Presence, SwitchRow, cn } from '@/components/ui/kit'
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
 */
export function WebCrawlFields({ values, onChange, limits, errors = {}, disabled, children }: {
  values: WebCrawlValues
  onChange: (patch: Partial<WebCrawlValues>) => void
  limits: WebCrawlLimits
  errors?: { maxDepth?: string; maxPages?: string }
  disabled?: boolean
  children?: ReactNode
}) {
  const { t } = useTranslation()
  const pages = Number(values.maxPages)
  return (
    <>
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
          onChange={e => onChange({ maxDepth: e.target.value })}
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
          onChange={e => onChange({ maxPages: e.target.value })}
        />
      </FormColumns>
      {/* A crawl is rate-limited for politeness and fetches every page one at a time, so a large
          page budget is genuinely hours of work, not minutes. Saying so where the number is set is
          the difference between "it is still going" and "it is broken". */}
      <Presence show={pages > 1000}>
        <InlineNotice tone="warning">
          {t('Crawling {pages} pages takes hours, longer with JavaScript rendering. Indexing starts with the first page found.',
            { pages: pages.toLocaleString() })}
        </InlineNotice>
      </Presence>
      {children}
      <SwitchRow
        className="py-0"
        title={t('Stay on the same domain')}
        checked={values.sameDomainOnly}
        onChange={sameDomainOnly => onChange({ sameDomainOnly })}
        disabled={disabled}
      />
      <SwitchRow
        className="py-0"
        title={t('Render JavaScript')}
        caption={t('Slower. Needed for client-rendered sites.')}
        checked={values.renderJs}
        onChange={renderJs => onChange({ renderJs })}
        disabled={disabled}
      />
    </>
  )
}
