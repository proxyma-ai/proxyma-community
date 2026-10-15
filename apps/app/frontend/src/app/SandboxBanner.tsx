import { useIsSandbox } from '@/hooks/useIsSandbox'
import { useTranslation } from '@/i18n'
import { Banner, Presence } from '@/components/ui/kit'

/**
 * The public Enterprise sandbox says what it is, on every screen.
 *
 * <p>What a visitor must know BEFORE they type anything: other visitors see what they add, and it is
 * all deleted every night. The accounts are shared, so a token saved under one of them is usable by
 * the next person who signs in as it - hence "real credentials" by name rather than a general
 * warning about privacy.
 *
 * <p>A courtesy, not the control: SandboxGuardFilter and OutboundAddressPolicy are what hold things
 * shut. The kit's `Banner` in the info tone, mounted under MaintenanceBanner. It eases in once the
 * config answers rather than pushing the page down in one frame.
 */
export function SandboxBanner() {
  const { t } = useTranslation()
  const isSandbox = useIsSandbox()

  return (
    <Presence show={isSandbox}>
      <Banner tone="info" title={t('Public sandbox - other visitors can see what you add, and everything is deleted every night.')}>
        {t('Do not enter real credentials or confidential data.')}
      </Banner>
    </Presence>
  )
}
