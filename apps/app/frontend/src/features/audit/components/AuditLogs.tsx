import { useEffect, useState } from 'react'
import {
  Caption, FadeSwap, Presence, SettingsSections, StatGrid, StatTile, SubNavTabs,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { FEATURES } from '@/config/features'
import { formatCompactCount } from '@/utils/formatters'
import { LlmUsage, CostEstimateNote } from '@/features/settings/components/LlmCallLog'
import { toolDisplayName } from '@/features/chat/utils/toolDisplayName'
import { AuditTabBody, RankedList } from './AuditShared'
import { GuardrailsPanel } from './GuardrailsPanel'
import { DiagnosticsPanel } from './DiagnosticsPanel'
import { durationText } from '../utils/auditLabels'

export type AuditPageTab = 'llm' | 'tools' | 'guardrails' | 'diagnostics' | 'activity'

/**
 * LOGS - every log the product keeps, in one place: Settings > Logs on Desktop and Home Server,
 * Administration > Logs on Enterprise Server (the page was named Audit Logs until 2026-10-14). Each tab was a section somewhere else
 * in Settings until 2026-10-12 (the call log under Usage & Cost, the guardrail decisions under
 * Guardrails, the diagnostics preview under About), or had no log at all.
 *
 * <p>ONE COMPONENT FOR BOTH PLACES, because the scope is the SERVER's to decide: an administrator
 * on Enterprise Server reads everyone's entries, the local user everything on their machine. What
 * differs by edition is which tabs exist - diagnostics are never collected on Enterprise Server, so
 * it has no Diagnostics tab - and whether "who" is worth a column.
 *
 * <p>`initialTab` is how another page links here - the About tab's "See what is sent" opens
 * Diagnostics.
 */
export function AuditLogs({ initialTab }: { initialTab?: string | null }) {
  const { t } = useTranslation()
  const multiUser = FEATURES.auth
  const tabs = [
    { key: 'llm' as const, label: t('LLM calls') },
    { key: 'tools' as const, label: t('Tool calls') },
    { key: 'guardrails' as const, label: t('Guardrails') },
    ...(multiUser ? [] : [{ key: 'diagnostics' as const, label: t('Diagnostics') }]),
    { key: 'activity' as const, label: t('Activity') },
  ]
  const [tab, setTab] = useState<AuditPageTab>(() =>
    tabs.some(x => x.key === initialTab) ? initialTab as AuditPageTab : 'llm')
  // A NEW link while the page is already open moves the tab too - it was read only on mount, so
  // "Open Logs" from Settings over Administration > Logs changed nothing. Keyed on the link alone,
  // so choosing a tab by hand afterwards is never undone.
  useEffect(() => {
    const known = ['llm', 'tools', 'guardrails', 'activity', ...(multiUser ? [] : ['diagnostics'])]
    if (initialTab && known.includes(initialTab)) setTab(initialTab as AuditPageTab)
  }, [initialTab, multiUser])

  return (
    <div className="space-y-6">
      <SubNavTabs tabs={tabs} active={tab} onChange={setTab} ariaLabel={t('Logs')} />
      {/* Each tab's sections are ruled off as every Settings tab's are (SettingsSections), and the
          tab fades in where the last one was (FadeSwap). */}
      <FadeSwap swapKey={tab}>
      <SettingsSections>
        {tab === 'llm' && <LlmUsage showSubjects={multiUser} intro={<CostEstimateNote />} />}
        {tab === 'tools' && <ToolCallsPanel multiUser={multiUser} />}
        {tab === 'guardrails' && <GuardrailsPanel multiUser={multiUser} />}
        {tab === 'diagnostics' && <DiagnosticsPanel />}
        {tab === 'activity' && (
          <AuditTabBody
            tab="ACTIVITY"
            title={t('Activity')}
            caption={multiUser
              ? t('Sign-ins, account changes, settings, sources, access, limits, updates and maintenance.')
              : t('Settings, sources, access, limits, updates, and anything cleared or deleted.')}
            multiUser={multiUser}
            logCaption={t('Each event in the period, newest first. Open a row to see what was recorded with it.')}
          />
        )}
      </SettingsSections>
      </FadeSwap>
    </div>
  )
}

/**
 * Every tool the agent, a deployed agent, a workflow or an MCP client called: what with, what came back,
 * how long it took, and - for a write - who let it through. The result is the one the MODEL was
 * given, after the guardrails, cut to its first few thousand characters.
 */
function ToolCallsPanel({ multiUser }: { multiUser: boolean }) {
  const { t } = useTranslation()
  return (
    <AuditTabBody
      tab="TOOL"
      title={t('Tool calls')}
      caption={t('What each call was given and what it returned, as the model saw it. Long results are cut short.')}
      multiUser={multiUser}
      logCaption={t('Each tool call in the period, newest first. Open a row to see its input and result.')}
      summary={d => {
        const failed = d.byOutcome.FAILED ?? 0
        const refused = d.byOutcome.DENIED ?? 0
        return (
          <>
            <StatGrid columns={4}>
              <StatTile label={t('Calls')} value={formatCompactCount(d.total)} />
              <StatTile label={t('Failed')} value={formatCompactCount(failed)} />
              <StatTile label={t('Refused')} value={formatCompactCount(refused)} sub={refused > 0 ? t('Writes denied or not allowed.') : undefined} />
              <StatTile label={t('Median time')} value={d.latency.p50 != null ? durationText(d.latency.p50) : '-'} />
            </StatGrid>
            <Presence show={d.total === 0}>
              <Caption>{t('No tool was called in this period.')}</Caption>
            </Presence>
            <RankedList
              title={t('Most used')}
              rows={d.byTarget.slice(0, 8).map(g => ({
                key: g.target,
                label: toolDisplayName(g.target, t),
                value: g.count,
                figures: [
                  t('1 call|{count} calls', { count: g.count }),
                  ...(g.failed > 0 ? [t('{count} failed', { count: g.failed.toLocaleString() })] : []),
                  durationText(g.avgMs),
                ],
              }))}
            />
          </>
        )
      }}
    />
  )
}
