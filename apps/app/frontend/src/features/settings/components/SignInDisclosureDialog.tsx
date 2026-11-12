import {
  BrandMark, Button, DialogBody, DialogDescription, DialogFooter, DialogShell, DialogTitle, ExternalLink,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { useSignInDisclosureStore } from '../hooks/useConnectionSignIn'
import { GOOGLE_PERMISSIONS_URL, GOOGLE_PRIVACY_URL, PROXYMA_PRIVACY_URL, YOUTUBE_TERMS_URL } from '../utils/signInDisclosure'

/**
 * BEFORE THE BROWSER OPENS GOOGLE'S SIGN-IN FOR YOUTUBE (2026-11-08): what Proxyma does with the channel,
 * the YouTube Terms of Service, Proxyma's Privacy Policy and Google's, and how to take the access back - YouTube's
 * API Services policies ask an API client to show these before the person authorizes it
 * (signInDisclosure.ts). Continue goes on to the sign-in; Cancel, Escape or the scrim stops there.
 * Mounted once in the app shell and drawn from the one store every sign-in button goes through
 * (useStartConnectionSignIn), so no caller can open Google without it. It fades and rises in, and out,
 * as every dialog does (DialogShell).
 */
export function SignInDisclosureDialog() {
  const { t } = useTranslation()
  const pending = useSignInDisclosureStore(s => s.pending)
  if (!pending) return null
  return (
    <DialogShell
      onClose={pending.cancel}
      onSubmit={pending.proceed}
      size="sm"
      ariaLabel={t('Before you sign in to YouTube')}
      // Above the Settings window and the connection dialog it is usually opened from.
      overlayClassName="fixed inset-0 z-alert flex justify-center overflow-y-auto px-0 py-4 sm:px-4 short:p-2"
    >
      <DialogBody>
        {/* The kit's own play glyph, never YouTube's logo (BrandMark records why). */}
        <span className="mb-3 block text-ink-secondary" aria-hidden="true"><BrandMark id="youtube" size={28} /></span>
        <DialogTitle>{t('Before you sign in to YouTube')}</DialogTitle>
        <ul className="mt-3 space-y-2 text-sm text-ink-secondary">
          <li>{t('Proxyma reads your channel\'s comments, videos and numbers, and posts a reply after you approve it, or on its own where you let it, as with Approve automatically.')}</li>
          <li>{t('What it reads stays in Proxyma, except the comments the assistant works on, which go to the AI model you chose. A public fact a reply needs may be looked up with a web search.')}</li>
          <li>
            <Rich
              text={t("By continuing, you agree to the {terms} and Proxyma's {ours}. The {privacy} applies to your Google account.")}
              parts={{
                terms: <ExternalLink href={YOUTUBE_TERMS_URL}>{t('YouTube Terms of Service')}</ExternalLink>,
                ours: <ExternalLink href={PROXYMA_PRIVACY_URL}>{t('Privacy Policy')}</ExternalLink>,
                privacy: <ExternalLink href={GOOGLE_PRIVACY_URL}>{t('Google Privacy Policy')}</ExternalLink>,
              }}
            />
          </li>
          <li>
            <Rich
              text={t('You can remove Proxyma\'s access at any time in your {permissions}, or by deleting the connection here.')}
              parts={{ permissions: <ExternalLink href={GOOGLE_PERMISSIONS_URL}>{t('Google account settings')}</ExternalLink> }}
            />
          </li>
        </ul>
        <DialogDescription tone="caption" className="mt-3">
          {t('Google asks which permissions to allow. Leave them all ticked, or the assistant cannot do its work.')}
        </DialogDescription>
      </DialogBody>
      <DialogFooter>
        <Button variant="secondary" onClick={pending.cancel}>{t('Cancel')}</Button>
        <Button variant="primary" onClick={pending.proceed} data-autofocus>{t('Continue to Google')}</Button>
      </DialogFooter>
    </DialogShell>
  )
}
