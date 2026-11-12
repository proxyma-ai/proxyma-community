/**
 * WHAT A PERSON IS TOLD BEFORE THE BROWSER OPENS A SERVICE'S SIGN-IN (2026-11-08). YouTube's API Services
 * policies ask an API client to show, before the person authorizes it, a link to the YouTube Terms of
 * Service, that Google's Privacy Policy applies, and how to remove the client's access
 * (developers.google.com/youtube/terms/developer-policies, III.A.1, III.A.2 and III.D.2). So a YouTube
 * sign-in - with Proxyma's built-in Google client or a person's own, on every edition, since Proxyma is the
 * API client either way - first shows a short dialog saying what Proxyma does with the channel, with those
 * links, and goes on to Google only from its Continue button. Plain .ts so `node --test` reads it.
 */

/** The YouTube Terms of Service, which a person agrees to by continuing. */
export const YOUTUBE_TERMS_URL = 'https://www.youtube.com/t/terms'
/** Google's Privacy Policy, which applies to what Google does with the sign-in. */
export const GOOGLE_PRIVACY_URL = 'https://policies.google.com/privacy'
/** Proxyma's own Privacy Policy, which the person agrees to before the app reaches their channel (III.A.2). */
export const PROXYMA_PRIVACY_URL = 'https://proxyma.ai/privacy'
/** Where a person removes a third-party app's access to their Google account. */
export const GOOGLE_PERMISSIONS_URL = 'https://myaccount.google.com/permissions'

export type SignInDisclosure = 'youtube'

/** The disclosure a connection's sign-in shows first, or null when it goes straight to the service. */
export function disclosureBeforeSignIn(protocol: string | null | undefined): SignInDisclosure | null {
  return protocol === 'YOUTUBE' ? 'youtube' : null
}
