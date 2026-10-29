import { FadeSwap, Field, FormColumns, Input, SettingsSection } from '@/components/ui/kit'
import { useSettings } from '../hooks/useSettings'
import { TabFieldsSkeleton, useResolved, type TabProps } from './shared'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'

// The quota block inside Usage & Cost (called Cost Settings until the two merged on 2026-09-02),
// DESKTOP edition.
//
// WHAT IS GONE, and why. The per-user quotas - default monthly allowance, 5-hour window, weekly
// cap - rendered here, accepted input, saved, and did nothing. Every consumer of those three
// settings (UserTokenQuotaService, RollingWindowChatQuotaGate, UserQuotaController) lives in
// proxyma-core-auth, which only builds under the server Maven profile. Desktop has one user and
// no auth, so there was nobody to hold to an allowance and no code loaded to do the holding.
//
// WHAT STAYS, and why it is not the same mistake. The global monthly ceiling IS enforced here:
// GlobalQuotaService is an unprofiled @Service in proxyma-core-settings, and ChatService calls
// globalQuotaGate.checkOrRefuse() on every message in both editions. Set it to $10 on a desktop
// install and chat is refused past $10. On a single-user machine the deployment ceiling IS the
// user's ceiling, which is exactly the control someone wants when they are spending their own
// money on an API key.
//
// The reset day stays with it, and belongs to it rather than to the per-user block it used to sit
// in: GlobalQuotaService reads quota-reset-day to decide where the ceiling's month starts. It is
// the only other quota setting this edition actually consumes.
export function QuotaSection({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const { isLoading } = useSettings('quota')
  const globalQuotaUsd = useResolved('quota', 'global-monthly-quota-usd', pending, '0')
  const quotaResetDay = useResolved('quota', 'quota-reset-day', pending, '1')

  return (
    // Not called "Global quota" here. "Global" distinguishes a deployment ceiling from a per-user
    // one, and there is no per-user one on this edition - so the word only invites the reader to
    // look for the other kind.
    <SettingsSection
      title={t('Spending limit')}
      caption={<Rich
        text={t('A monthly {hardStop}: chat, search and indexing stop until the reset day.')}
        parts={{ hardStop: <strong className="text-ink-secondary">{t('hard stop')}</strong> }}
      />}
    >
      <FadeSwap swapKey={isLoading}>
      {isLoading ? (
        <TabFieldsSkeleton count={2} />
      ) : (
        <FormColumns>
          <Field label={t('Monthly limit ($)')} hint={t('0 = no limit')}>
            {id => (
              <Input
                id={id}
                type="number"
                min="0"
                step="0.01"
                value={globalQuotaUsd}
                onChange={e => trackChange('quota', 'global-monthly-quota-usd', e.target.value)}
                placeholder="0"
              />
            )}
          </Field>
          <Field label={t('Reset day')} hint={t('Day of month (1-28)')}>
            {id => (
              <Input
                id={id}
                type="number"
                min="1"
                max="28"
                step="1"
                value={quotaResetDay}
                onChange={e => trackChange('quota', 'quota-reset-day', e.target.value)}
              />
            )}
          </Field>
        </FormColumns>
      )}
      </FadeSwap>
    </SettingsSection>
  )
}
