// Gates the anonymous visitor's sign-in banner (see features/auth/components/GuestBanner). Plain
// sessionStorage functions rather than a Zustand store, matching mcpAdStore next door: this is
// read imperatively once when the banner mounts, not shared reactively between components.
//
// sessionStorage, NOT localStorage, and that is the whole decision here. A dismissal should last
// as long as the visitor is looking around - closing it once should not have it reappear on the
// next navigation - but it must not be permanent: a guest who comes back tomorrow with turns left
// has no other standing reminder that an account exists, since a signed-out visitor sees no
// account UI anywhere else except Sign in - a button in the header from `lg` up, a row in the
// header's menu below it.
const KEY_DISMISSED = 'proxyma-guest-banner-dismissed'

export function isGuestBannerDismissed(): boolean {
  try { return sessionStorage.getItem(KEY_DISMISSED) === 'true' } catch { return false }
}

export function markGuestBannerDismissed() {
  try { sessionStorage.setItem(KEY_DISMISSED, 'true') } catch { /* private browsing, ignore */ }
}
