import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Caption, Chip, CodeBlock, cn, Disclosure, Eyebrow, FadeSwap, FieldLabel, Presence, Readout,
  SectionHeading, StatGrid, StatTile,
} from '@/components/ui/kit'
import { PagedItems } from '@/components/ui/ListPager'
import { useTranslation, type Translate } from '@/i18n'
import { formatCompactCount } from '@/utils/formatters'
import { guardrailMetricsApi, type RecentDecision } from '@/features/settings/services/guardrailMetricsApi'
import { AuditTabBody, RankedList } from './AuditShared'
import { words } from '../utils/auditLabels'
import { useDates } from '@/hooks/useDates'

/**
 * Every guardrail check - what was checked, what it decided, what fired and what it cost in time -
 * from the log, over a period. Moved here from Settings > Guardrails on 2026-10-12, where it was an
 * in-memory window of the last 200 decisions that a restart emptied.
 *
 * <p>THE MATCHED TEXT DID NOT MOVE INTO THE LOG, and cannot. With "Keep the matched text" on, a
 * rule records the characters it objected to - a credential, often - and that is promised never to
 * reach disk (GuardrailEvidence). The log is on disk. So the matched text stays where it was, in
 * memory until restart, and is shown in its own section below the summary, said to be exactly that.
 */
export function GuardrailsPanel({ multiUser }: { multiUser: boolean }) {
  const { t, locale } = useTranslation()
  return (
    <AuditTabBody
      tab="GUARDRAIL"
      title={t('Guardrails')}
      caption={t('Every check, including the ones that let the text through. Rule names and categories only - never the text.')}
      multiUser={multiUser}
      logCaption={t('Each check in the period, newest first. Open a row to see which rules fired.')}
      extra={<MatchedTextSection />}
      summary={d => {
        const observed = d.byOutcome.OBSERVED ?? 0
        const classifierFailures = d.byAction
          .filter(a => a.action === 'guardrail.classifier-failed')
          .reduce((n, a) => n + a.count, 0)
        const checked = d.total - classifierFailures
        return (
          <>
            {/* Two by two on a phone, four across from sm - see the note this replaced in
                Settings: four tiles in a 375px modal left "Redacted" wrapping under its number. */}
            <StatGrid>
              <StatTile label={t('Checked')} value={formatCompactCount(checked)} />
              <StatTile label={t('Allowed')} value={formatCompactCount(d.byOutcome.ALLOWED ?? 0)} />
              <StatTile label={t('Redacted')} value={formatCompactCount(d.byOutcome.REDACTED ?? 0)} />
              <StatTile label={t('Blocked')} value={formatCompactCount(d.byOutcome.BLOCKED ?? 0)} />
            </StatGrid>
            <Presence show={observed > 0}>
              <Caption>
                {t('{count} of those would have been withheld, but monitor mode let them through.', { count: observed })}
              </Caption>
            </Presence>
            <Presence show={classifierFailures > 0}>
              <Caption tone="warning">
                {t('The classifier failed and let 1 turn through. Check that its provider is reachable.|The classifier failed and let {count} turns through. Check that its provider is reachable.', { count: classifierFailures })}
              </Caption>
            </Presence>
            {/* A name over a group of tiles, not a control's label - a <label> here pointed at
                nothing. */}
            <div>
              <FieldLabel as="p">{t('Added per check')}</FieldLabel>
              <FadeSwap swapKey={d.latency.samples > 0}>
                {d.latency.samples > 0 ? (
                  <StatGrid columns={3}>
                    <StatTile label={t('Median')} value={`${d.latency.p50} ms`} />
                    <StatTile label={t('95th pct')} value={`${d.latency.p95} ms`} />
                    <StatTile label={t('Slowest')} value={`${d.latency.max} ms`} />
                  </StatGrid>
                ) : (
                  <Caption>{t('Nothing measured yet.')}</Caption>
                )}
              </FadeSwap>
            </div>
            <RankedList
              title={t('What fired')}
              rows={d.byTarget.slice(0, 6).map(g => ({
                key: g.target, label: words(g.target), value: g.count, figures: [g.count.toLocaleString(locale)],
              }))}
            />
          </>
        )
      }}
    />
  )
}

// The text each rule matched, from the in-memory window - shown only once there is some, which
// means "Keep the matched text" is on and a rule has fired since the app started.
function MatchedTextSection() {
  const { t } = useTranslation()
  const [open, setOpen] = useState<string | null>(null)
  const { data } = useQuery({
    queryKey: ['guardrail-metrics'],
    queryFn: guardrailMetricsApi.get,
    refetchInterval: 5000,
  })
  const withEvidence = (data?.recent ?? []).filter(r => r.evidence.length > 0)
  const dates = useDates()
  // In once a rule has matched something, as a section of the page.
  return (
    <Presence show={withEvidence.length > 0}>
    <section>
      <SectionHeading
        caption={t('The text a rule objected to, kept in memory until restart and never written to the log. Turn off "Keep the matched text" in Guardrails to stop recording it.')}
      >
        {t('Matched text')}
      </SectionHeading>
      {/* Up to 200 checks are kept, so twenty a page (LIST_PAGE_SIZE) - the newest first, the page
          kept while the five-second refresh brings more. */}
      <PagedItems items={withEvidence}>
        {page => (
          <div className="space-y-1">
            {page.map((r, i) => {
              const id = `${r.at}-${i}`
              return (
                <Disclosure
                  key={id}
                  boxed
                  open={open === id}
                  onToggle={o => setOpen(o ? id : null)}
                  summaryClassName="px-3 py-2 touch:min-h-tap text-xs"
                  meta={<Readout>{r.millis} ms</Readout>}
                  summary={
                    <span className="flex items-center gap-3 min-w-0">
                      <Readout tone="caption" className="shrink-0 min-w-20 whitespace-nowrap">
                        {dates.time(r.at)}
                      </Readout>
                      <span className="text-ink-caption shrink-0 min-w-16 whitespace-nowrap">{decisionStage(r.stage, t)}</span>
                      <span className={cn('shrink-0 min-w-24 whitespace-nowrap font-semibold', r.enforced ? 'text-danger' : 'text-ink-caption')}>
                        {decisionVerdict(r, t)}
                      </span>
                      <span className="text-ink-secondary truncate flex-1">{r.categories.join(', ') || '-'}</span>
                    </span>
                  }
                >
                  <div className="space-y-3 border-t border-line/40 pt-3">
                    <div>
                      <Eyebrow className="mb-1">{t('Rules that fired')}</Eyebrow>
                      <div className="flex flex-wrap gap-2">
                        {r.rules.map(rule => <Chip key={rule} label={rule} />)}
                      </div>
                    </div>
                    <div className="space-y-2">
                      {r.evidence.map((e, j) => (
                        // A matched span is frequently one long unbroken token, so it wraps.
                        <div key={`${e.rule}-${j}`}>
                          <Caption className="mb-0.5">{e.rule}</Caption>
                          <CodeBlock wrap>
                            {e.match}
                            {e.truncated && <span className="text-ink-disabled">{t(' ... (truncated)')}</span>}
                          </CodeBlock>
                        </div>
                      ))}
                    </div>
                  </div>
                </Disclosure>
              )
            })}
          </div>
        )}
      </PagedItems>
    </section>
    </Presence>
  )
}

function decisionStage(stage: RecentDecision['stage'], t: Translate): string {
  if (stage === 'INPUT') return t('Input')
  if (stage === 'OUTPUT') return t('Output')
  return t('Retrieval')
}

function decisionVerdict(r: RecentDecision, t: Translate): string {
  if (r.verdict === 'block') return r.enforced ? t('Blocked') : t('Would block')
  if (r.verdict === 'redact') return r.enforced ? t('Redacted') : t('Would redact')
  return r.enforced ? t('Allowed') : t('Would allow')
}
