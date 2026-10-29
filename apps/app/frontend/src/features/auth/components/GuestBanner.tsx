import { Link } from 'react-router-dom'
import { useGuestAllowance } from '@/features/chat/hooks/useGuestAllowance'
import { useThemeStore } from '@/store/themeStore'
import { Banner, Presence, textLink } from '@/components/ui/kit'
import { isGuestBannerDismissed, markGuestBannerDismissed } from '@/store/guestBannerStore'
import { useTranslation } from '@/i18n'
import { useState } from 'react'

// The one thing an anonymous visitor is told: they are chatting on a free allowance, and an
// account is what lifts it.
//
// Server mode only, and only while signed out - useGuestAllowance is disabled in every other case,
// so `data` stays undefined and this renders nothing. That gating lives in the hook rather than
// here so the query and the banner can never disagree about who is a guest.
//
// IN NORMAL FLOW, NOT FIXED OR ABSOLUTE, mounted inside AppShell above <main>. Same reasoning as
// MaintenanceBanner: it pushes the page down instead of covering the top of it. AppShell leaves it
// out on /login, /setup and the password pages - a banner urging someone to sign in has nothing to
// say on the sign-in page.
//
// Dismissible, and the dismissal sticks for the session. It is a nudge, not a warning: a visitor
// who has decided not to sign in should not be asked again on every navigation. The remaining
// count still reaches them through the chat error bubble when the allowance actually runs out.
export function GuestBanner() {
  const { data } = useGuestAllowance()
  const productName = useThemeStore(s => s.branding.productName)
  const [dismissed, setDismissed] = useState(isGuestBannerDismissed)
  const { t } = useTranslation()

  // A Presence in both returns, at the same place in the tree, so dismissing it eases it away.
  if (!data || dismissed) return <Presence show={false} />

  // Three states, not two. `allowed` is whether this deployment takes anonymous turns at all and
  // `enabled` is whether it counts them - so a deployment that refuses them must not be told it is
  // on a free preview, and one that accepts them without a meter has no count to quote.
  const exhausted = data.enabled && data.remaining <= 0
  const message = !data.allowed
    ? t('{product} needs an account here.', { product: productName })
    : !data.enabled
      ? t('You are trying {product} as a guest.', { product: productName })
      : exhausted
        ? t('You have used all {limit} free messages.', { limit: data.limit })
        : t('{remaining} of {limit} free messages left.', { remaining: data.remaining, limit: data.limit })

  // The kit's page-top strip. ON A PHONE NOTHING TO TAP SITS IN THE MIDDLE (maintainer's rule,
  // 2026-10-02): the text reads from the left edge, in line with the logo above it, Sign in is
  // under it on that same edge, and the X is at the right - which is how the kit Banner lays itself
  // out below `sm`. From `sm` up it is one centred line. A surface, not an alarm: running out is
  // the one state worth colouring, and even then it is the brand's info tint rather than danger -
  // the visitor has not done anything wrong.
  return (
    <Presence show>
      <Banner
        tone={exhausted ? 'info' : 'neutral'}
        onDismiss={() => { markGuestBannerDismissed(); setDismissed(true) }}
        dismissLabel={t('Dismiss')}
        // A navigation is a LINK - it was a button that navigated, so it could not be opened in a
        // new tab and was announced as a button.
        action={<Link to="/login" className={textLink()}>{t('Sign in')}</Link>}
      >
        {message}{' '}
        <span className="text-ink-caption">
          {data.allowed
            ? t('Sign in for your own conversation history, your own sources, and a far larger allowance.')
            : t('Sign in for your own conversation history and your own sources.')}
        </span>
      </Banner>
    </Presence>
  )
}
