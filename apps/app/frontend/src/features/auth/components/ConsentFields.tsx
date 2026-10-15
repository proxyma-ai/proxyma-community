import { useId } from 'react'
import type { ConsentRequirements } from '../services/authApi'
import { Caption, Checkbox, ExternalLink, Presence } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'

// The consent block shown wherever the user is asked to accept the legal documents: both
// account-creation forms (self-registration and the first-run administrator setup) and the
// re-consent gate. One component so those screens cannot drift apart in what they ask for - which
// is exactly the kind of drift that makes a consent record hard to stand behind later.
//
// One checkbox, REQUIRED, unticked by default, blocking the submit button. It is not pre-ticked: a
// pre-ticked consent box is not consent, and under GDPR it is expressly not consent (Article 4(11),
// "a clear affirmative action").
//
// There was a second, optional box until 2026-10-15 - "contribute anonymous usage counts" - whose
// answer nothing read: Enterprise Server sends no diagnostics at all. A consent that controls
// nothing is not one to ask for, so it went (release review 2026-10-15, W26).
interface Props {
  requirements: ConsentRequirements | null
  accepted: boolean
  onAcceptedChange: (accepted: boolean) => void
  disabled?: boolean
  // Set by the parent's click-time validation when submit was attempted without acceptance.
  error?: string
}

export function ConsentFields({
  requirements, accepted, onAcceptedChange, disabled, error,
}: Props) {
  const { t } = useTranslation()
  const errorId = useId()
  // All three documents are published on the marketing site and read there, so all three go through
  // ExternalLink. Each URL comes from the backend's consent-requirements response - the same
  // response that carries the version number recorded against this consent - so the link a user
  // followed and the text their consent is filed against can never come from two different places.
  // The literals are a fallback for the window before that request resolves, not a second source.
  const termsUrl = requirements?.termsUrl ?? 'https://proxyma.ai/terms'
  const privacyUrl = requirements?.privacyUrl ?? 'https://proxyma.ai/privacy'
  const eulaUrl = requirements?.eulaUrl ?? 'https://proxyma.ai/eula'

  // The kit's Checkbox - a drawn box over a real, visually hidden input - not the native boxes with
  // `accent-*` colors these were until the 2026-10 rebuild.
  return (
    <div className="flex flex-col gap-3 mt-1">
      <div>
        {/* The box names its error line, so a screen reader landing on it hears why the form will
            not go on - the native box carried that link and the kit one had no way to until it
            took `describedBy`. */}
        <Checkbox
          checked={accepted}
          disabled={disabled}
          onChange={onAcceptedChange}
          describedBy={error ? errorId : undefined}
          invalid={!!error}
        >
          {/* ONE KEY with the three documents as slots (Rich), so a translation can order the
              sentence its own way - it was three fragments around the links. */}
          <Rich
            text={t('I have read and accept the {terms}, {privacy} and {eula}.')}
            parts={{
              terms: <ExternalLink href={termsUrl}>{t('Terms of Service')}</ExternalLink>,
              privacy: <ExternalLink href={privacyUrl}>{t('Privacy Policy')}</ExternalLink>,
              eula: <ExternalLink href={eulaUrl}>{t('End User License Agreement')}</ExternalLink>,
            }}
          />
        </Checkbox>
        {/* Under the label's text rather than the box: 16px box plus the 10px gap. */}
        <Presence show={!!error}><Caption id={errorId} tone="danger" role="alert" className="mt-1 pl-[26px]">{error}</Caption></Presence>
      </div>
    </div>
  )
}
