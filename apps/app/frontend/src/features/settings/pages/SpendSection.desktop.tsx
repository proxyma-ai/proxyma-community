import { SignpostSection } from './shared'
import { useTranslation } from '@/i18n'
import { AuditLogsLink } from './AuditLogsLink.desktop'
import type { OpenTab } from './types'

// Where Usage & Cost's spending went, DESKTOP edition.
//
// The figures, the chart, the breakdowns and every model call moved to Settings > Logs on
// 2026-10-12, beside the tool calls, guardrail checks and the rest of what this installation logs.
// This tab keeps what GOVERNS the spend - the limit - and says where the readout is now, since a
// section that silently vanished reads as a regression.
export function SpendSection({ onOpenTab }: { onOpenTab: OpenTab }) {
  const { t } = useTranslation()
  return (
    <SignpostSection
      title={t('Spending')}
      caption={t('What has been spent, by provider, model and service, and every model call, is in Logs.')}
      action={<AuditLogsLink log="llm" onOpenTab={onOpenTab}>{t('Open Logs')}</AuditLogsLink>}
    />
  )
}
