import { useState, useEffect } from 'react'
import { AuthLayout, Button, FormAlert, Input, PasswordInput, Presence } from '@/components/ui/kit'
import { Logo } from '@/components/ui/Logo'
import { PasswordChecklist } from '../components/PasswordChecklist'
import { ConsentFields } from '../components/ConsentFields'
import { usePasswordValidation } from '../hooks/usePasswordValidation'
import { authApi, type ConsentRequirements } from '../services/authApi'
import { usePageTitle } from '@/hooks/usePageTitle'
import { useShake } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { authErrorText } from '../utils/authErrorText'

export function SetupPage() {
  const { t } = useTranslation()
  // Setup is often the very first page loaded on a fresh deployment - same reasoning as
  // LoginPage.tsx: nothing else has set the title yet, so it can't rely on index.html's
  // static "Proxyma" default or another page's usePageTitle call.
  usePageTitle('Setup')

  // The one-time token the server printed in its log at startup. It is what makes whoever fills in
  // this form the person who runs the server, rather than the first visitor to reach a fresh install.
  const [setupToken, setSetupToken] = useState('')
  const [setupTokenError, setSetupTokenError] = useState('')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [loading, setLoading] = useState(false)
  const [emailError, setEmailError] = useState('')
  const tokenShake = useShake()
  const emailShake = useShake()
  const passwordShake = useShake()

  // The first administrator accepts the same documents, with the same required checkbox, as any
  // other account. They are also the one person whose acceptance the deployment most depends on.
  const [consentReqs, setConsentReqs] = useState<ConsentRequirements | null>(null)
  const [accepted, setAccepted] = useState(false)
  const [consentError, setConsentError] = useState('')

  useEffect(() => {
    authApi.consentRequirements().then(setConsentReqs).catch(() => {})
  }, [])

  const { results, allPass } = usePasswordValidation(password, confirm, 'setup')

  // No mount-time setup check / redirect here - SetupGate (app/SetupGate.tsx) is the single
  // authority on when /setup may render and when to leave it. A page-local redirect that read an
  // ambiguous setup-status body as "not needed" is exactly what drove the earlier redirect loop.

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setSetupTokenError('')
    setEmailError('')

    if (!setupToken.trim()) {
      setSetupTokenError(t('Enter the setup token from the server log'))
      tokenShake.trigger()
      return
    }

    if (!email.trim()) {
      setEmailError(t('Email is required'))
      emailShake.trigger()
      return
    }

    if (!allPass) {
      passwordShake.trigger()
      return
    }

    if (!accepted) {
      setConsentError(t('You must accept the Terms of Service, Privacy Policy and EULA to create an account.'))
      return
    }
    if (!consentReqs) {
      setError(t('Could not load the current terms. Reload the page and try again.'))
      return
    }

    setLoading(true)

    try {
      await authApi.setup(setupToken.trim(), email.trim(), displayName.trim(), password, {
        acceptedLegalVersion: consentReqs.version,
      })
      setSuccess(t('Super admin account created. Redirecting to login...'))
      // Full reload (not client-side navigate): SetupGate caches setup-status for the session, so
      // a fresh document load is the clean way to make it re-check, observe the now-created super
      // admin (needed:false), and hand off to /login. A super admin now exists, so nothing loops.
      setTimeout(() => window.location.assign('/login'), 1500)
    } catch (err: unknown) {
      // The one reader of a refused sign-in form (authErrorText).
      setError(authErrorText(err, t, t('Setup failed. Please try again.')))
      setLoading(false)
    }
  }

  return (
    // The kit's sign-in card, as every auth page is: the lockup ONCE (app UI review, F11), and a
    // card that scrolls in its own box - this is the longest of the four forms.
    <AuthLayout
      logo={<Logo size={22} />}
      title={t('Create the super admin account')}
      subtitle={t('Initial server setup')}
      // The card's own note under its subtitle (AuthLayout's `description`), not a Caption in the form.
      description={t('This account has full control over the server. Choose a strong password you will remember.')}
    >
      <Presence show={!!error}><FormAlert>{error}</FormAlert></Presence>
      <Presence show={!!success}><FormAlert tone="success">{success}</FormAlert></Presence>

      {/* Eased away once the account is made, as the confirmation above eases in. */}
      <Presence show={!success}>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4" autoComplete="off">
          <Input
            label={t('Setup token')}
            hint={t('The server prints it in its log when it starts. Set PROXYMA_SETUP_TOKEN to choose it.')}
            value={setupToken}
            onChange={e => setSetupToken(e.target.value)}
            autoFocus
            spellCheck={false}
            error={setupTokenError}
            shake={tokenShake.shaking}
          />

          <Input
            label={t('Email')}
            type="email"
            // An example address, not prose - see the note on LoginPage's own.
            placeholder="admin@example.com"
            value={email}
            onChange={e => setEmail(e.target.value)}
            error={emailError}
            shake={emailShake.shaking}
          />

          <Input
            label={t('Display name')}
            placeholder={t('Admin (optional)')}
            value={displayName}
            onChange={e => setDisplayName(e.target.value)}
          />

          <PasswordInput labels={{ show: t('Show password'), hide: t('Hide password') }}
            label={t('Password')}
            placeholder={t('Enter password')}
            value={password}
            onChange={e => setPassword(e.target.value)}
            shake={passwordShake.shaking}
          />

          <PasswordInput labels={{ show: t('Show password'), hide: t('Hide password') }}
            label={t('Re-enter password')}
            placeholder={t('Re-enter password')}
            value={confirm}
            onChange={e => setConfirm(e.target.value)}
          />

          <PasswordChecklist results={results} className="mb-1" />

          <ConsentFields
            requirements={consentReqs}
            accepted={accepted}
            onAcceptedChange={v => { setAccepted(v); setConsentError('') }}
            disabled={loading}
            error={consentError}
          />

          <Button type="submit" size="md" className="w-full" loading={loading} disabled={!accepted}>
            {loading ? t('Creating...') : t('Create account')}
          </Button>
        </form>
      </Presence>
    </AuthLayout>
  )
}
