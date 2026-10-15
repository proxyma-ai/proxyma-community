import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileText } from 'lucide-react'
import { Button, Caption, DialogBody, DialogDescription, DialogFooter, DialogTitle } from '@/components/ui/kit'
import { keys } from '@/services/queryKeys'
import { authApi } from '../services/authApi'
import { ConsentFields } from './ConsentFields'
import { useSignOut } from '../hooks/useSignOut'
import { useDates } from '@/hooks/useDates'
import { apiErrorText } from '@/services/apiError'
import type { AuthUser } from '../types/auth.types'
import { useTranslation } from '@/i18n'
import { ModalShell } from '@/components/ui/ModalShell'

// The re-consent gate. Raised by AppShell whenever the signed-in account's consentStale is true -
// which happens the moment LegalDocuments.CURRENT_VERSION is bumped, for every existing user at
// once. Until this session, bumping that constant only affected NEW signups: nothing ever asked an
// existing user again, while the constant's own comment claimed otherwise.
//
// Blocking, and not dismissable. There is no "later" button on purpose: a legal notice a user can
// postpone indefinitely is one most users never read, and the whole reason to record a version per
// account is to be able to say which text each person actually agreed to. The escape hatch is
// signing out, which is the honest alternative to accepting - not a hidden third state where the
// user keeps working while the record says they agreed to nothing.
//
// It is a UI gate only. The backend does not refuse API calls from a stale user, deliberately -
// see AuthController.renewConsent for why that would be a worse trade than it sounds.
interface Props {
  user: AuthUser
}

export function ReConsentModal({ user }: Props) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const dates = useDates()
  const [accepted, setAccepted] = useState(false)
  const [error, setError] = useState<string | undefined>()

  // Only for the three document URLs. The VERSION echoed back is the one that came with the user
  // row, not this response: the row is what the gate's verdict was computed against, and two
  // reads of a constant that changed between them would otherwise clear a gate the user was
  // never actually shown the current text for.
  const { data: requirements } = useQuery({
    queryKey: keys.consentRequirements(),
    queryFn: authApi.consentRequirements,
    staleTime: Infinity,
  })

  const renew = useMutation({
    mutationFn: () => authApi.renewConsent(user.legalVersionRequired),
    // Seeded rather than invalidated: the response IS the updated user, so writing it straight
    // into the cache closes the gate on the same tick instead of leaving the blocking overlay up
    // for the length of a refetch.
    onSuccess: updated => { qc.setQueryData(keys.me(), updated) },
    // The server's own message where there is one - the case that matters is a version mismatch,
    // which happens when this tab was open across a deploy that bumped the constant. "Reload and
    // accept the current version" is the actionable thing to say there; a generic failure is not.
    onError: err => setError(apiErrorText(err) ?? t('Could not record your acceptance. Please try again.')),
  })

  // The one sign-out (useSignOut), the menu's and Settings' too.
  const signOut = useSignOut()

  const busy = renew.isPending || signOut.isPending

  // An account created before consent was collected has never accepted anything (its row is
  // deliberately not backfilled), so calling that an UPDATE would be false. Same gate, different
  // sentence - and the difference is the one a user would notice if we got it wrong.
  const firstTime = user.legalVersionAccepted === null

  return (
    <ModalShell
      // No onClose, and so no Escape either: a dismissible gate would be a notice with a "not now".
      closeOnBackdrop={false}
      role="alertdialog"
      ariaLabel={firstTime ? t('Please review our terms') : t('Our terms have changed')}
      size="md"
    >
      {/* The kit's centred confirmation body: the glyph, the title, what is asked. */}
      <DialogBody centered>
        <FileText size={32} className="mb-4 text-brand-legible" aria-hidden="true" />
        <DialogTitle>{firstTime ? t('Please review our terms') : t('Our terms have changed')}</DialogTitle>
        <DialogDescription className="mt-2">
          {firstTime
            ? t('Your account was created before we started recording which version of our terms each user accepted. Please review and accept them to continue.')
            : t('We have updated our Terms of Service, Privacy Policy and End User License Agreement. Please review and accept the current version to continue.')}
        </DialogDescription>

        {/* The same component the signup and setup forms use, so the gate cannot end up asking
            about a different set of documents than the ones a new account agrees to. Its
            optional product-improvement checkbox is omitted here - this endpoint does not write
            that preference, and offering a control that is not saved is worse than not
            offering it. It stays in Settings. */}
        <div className="mt-4 self-stretch text-left">
          <ConsentFields
            requirements={requirements ?? null}
            accepted={accepted}
            onAcceptedChange={next => { setAccepted(next); setError(undefined) }}
            disabled={busy}
            error={error}
          />
        </div>

        {!firstTime && user.legalAcceptedAt && (
          <Caption className="mt-4">
            {t('You last accepted version {version} on {date}.', {
              version: user.legalVersionAccepted ?? '',
              date: dates.date(user.legalAcceptedAt),
            })}
          </Caption>
        )}
      </DialogBody>
      {/* Signing out is the honest alternative to accepting, so it sits where Cancel would. */}
      <DialogFooter>
        <Button variant="secondary" onClick={() => signOut.mutate()} disabled={busy} loading={signOut.isPending}>
          {signOut.isPending ? t('Signing out...') : t('Sign out')}
        </Button>
        <Button
          variant="primary"
          onClick={() => {
            if (!accepted) {
              setError(t('You must accept the terms to continue.'))
              return
            }
            renew.mutate()
          }}
          disabled={busy}
          loading={renew.isPending}
        >
          {renew.isPending ? t('Saving...') : t('Accept and continue')}
        </Button>
      </DialogFooter>
    </ModalShell>
  )
}
