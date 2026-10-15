import { useState, useEffect } from 'react'
import { useNavigate, useSearchParams, Link } from 'react-router-dom'
import {
  AuthLayout, BackLink, Button, Caption, ExternalLink, FadeSwap, FormAlert, Input, OrDivider,
  PasswordInput, Presence, Skeleton, cn, safeLocalPath, textLink,
} from '@/components/ui/kit'
import { Rich } from '@/i18n/kit'
import { authErrorText } from '../utils/authErrorText'
import { Logo } from '@/components/ui/Logo'
import { PasswordChecklist } from '../components/PasswordChecklist'
import { OAuthButtons } from '../components/OAuthButtons'
import { ConsentFields } from '../components/ConsentFields'
import { SandboxAccountPicker } from '../components/SandboxAccountPicker'
import { usePasswordValidation } from '../hooks/usePasswordValidation'
import { authApi, type ConsentRequirements } from '../services/authApi'
import { usePageTitle } from '@/hooks/usePageTitle'
import { useShake } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'

// One structural template for every tenant: logo -> product name -> (OAuth buttons, if any
// are configured) -> (email/password form, if the deployment allows local accounts). Which
// parts render is driven entirely by what GET /auth/providers reports - never a tenant-name
// check - so a tenant with only SSO configured naturally shows SSO-only, and a tenant with
// both SSO and local accounts (e.g. "email/password and corporate SSO") naturally shows both.
export function LoginPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  // Login is often the first page a fresh page load lands on (e.g. an OAuth2 redirect back to
  // /login?auth=error) - there's no earlier page whose usePageTitle call would have already
  // set the right title, so this page must set it itself rather than relying on index.html's
  // static <title> (which is always "Proxyma", the build-time default for every tenant).
  usePageTitle('Sign in')
  const { t } = useTranslation()

  const [isRegister, setIsRegister] = useState(params.get('mode') === 'register')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  // THE REASON IS HELD, NOT THE MESSAGE, and that is a translation requirement rather than a
  // style preference. A `useState` initializer runs once, so `useState(t(...))` would keep the
  // wording of whatever language was in force then for the life of the page - and the reader can
  // switch language on this very page. (Until 2026-10-14 the first render was also always English,
  // because I18nProvider detected the locale in an effect; it reads it up front now.) The message
  // is built during render below instead.
  const [authErrorReason, setAuthErrorReason] = useState(() => {
    const reason = params.get('auth')
    return reason === 'maintenance' || reason === 'license' || reason === 'linked' || reason === 'closed'
      || reason === 'error' ? reason : ''
  })
  // Submit failures are safe to translate as they are set: nothing can reach them before the
  // reader has interacted with the page, by which time the locale has long settled.
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [oauthProviders, setOauthProviders] = useState<string[]>([])
  const [ssoDisplayName, setSsoDisplayName] = useState('')
  const [providersLoaded, setProvidersLoaded] = useState(false)
  // Off until the server says otherwise: "Create one" is offered only where people may.
  const [selfRegistration, setSelfRegistration] = useState(false)
  const [passwordResetByEmail, setPasswordResetByEmail] = useState(false)
  const [emailError, setEmailError] = useState('')
  const [passwordError, setPasswordError] = useState('')
  const emailShake = useShake()
  const passwordShake = useShake()

  // Consent, collected only when creating an account - signing in to an existing one accepted
  // these already, and re-asking on every sign-in would train people to tick without reading.
  const [consentReqs, setConsentReqs] = useState<ConsentRequirements | null>(null)
  const [accepted, setAccepted] = useState(false)
  const [consentError, setConsentError] = useState('')

  const { results, allPass } = usePasswordValidation(password, confirm, 'register')

  // Where to land after a successful sign-in. Defaults to the app root as it always has; a
  // ?next= set by RequireAuth (e.g. someone opening a /shared/{token} link while signed out)
  // sends them to the page they actually asked for instead of dropping them on chat. Only
  // same-origin relative paths are honored - an absolute URL, a protocol-relative "//host" or a
  // "/\host" (read by the browser as "//host") would turn this into an open redirect.
  const next = safeLocalPath(params.get('next')) ?? '/'

  useEffect(() => {
    authApi.me().then(() => navigate(next, { replace: true })).catch(() => {})

    // No setup check here - SetupGate (app/SetupGate.tsx) guarantees LoginPage only renders once
    // first-run setup is complete, so this page can go straight to loading the auth providers.
    authApi.providers()
      .then(p => {
        const providers = p.oauth || []
        // NOTHING AUTO-REDIRECTS TO SSO any more - the user always picks a provider. The
        // "Redirecting to..." pill that once covered an automatic hop, and the tooltip for a
        // deployment with local sign-in switched off (there is no such config), were constant-false
        // branches until the 2026-10 kit rebuild removed them.
        setSsoDisplayName(p.ssoDisplayName || '')
        setOauthProviders(providers)
        setSelfRegistration(p.selfRegistration === true)
        setPasswordResetByEmail(p.passwordResetByEmail === true)
        // A ?mode=register link to a server that does not take sign-ups lands on sign-in instead.
        if (p.selfRegistration !== true) setIsRegister(false)
      })
      .catch(() => {})
      .finally(() => setProvidersLoaded(true))

    // Fetched unconditionally rather than only in register mode, so the version is already in hand
    // the instant someone toggles to "Create one" - and so a failure to load it is visible as a
    // disabled submit rather than as a rejected signup after the form was filled in.
    authApi.consentRequirements().then(setConsentReqs).catch(() => {})
  }, [navigate, params])

  // The message a `?auth=` bounce asked for, resolved at RENDER so it follows the locale. Empty
  // once the reader has switched mode, matching what clearing `error` used to do for it.
  const authErrorMessage =
    authErrorReason === 'license'
      // Social or SSO sign-in that would have CREATED an account on an Enterprise Server with no
      // license in force - OAuthUserSyncService refuses it, and the handler bounces here with the
      // reason. Accounts that already exist keep signing in, licensed or not.
      ? t('This server cannot add new accounts without an Enterprise license. Existing accounts sign in as before; ask your administrator.')
      : authErrorReason === 'linked'
        // A first sign-in with a provider whose email matches an account that has a password, or
        // that the provider does not vouch for - OAuthUserSyncService.linkable refuses to join them.
        ? t('An account with this email address already exists. Sign in the way you signed in before.')
      : authErrorReason === 'closed'
        // A first sign-in that would have created an account the server does not let people
        // create for themselves - SignUpPolicy on the server.
        ? t('This server does not create an account for this sign-in. Ask an administrator to add you.')
      : authErrorReason === 'maintenance'
        ? t('The system is temporarily unavailable for maintenance. Please try again later.')
        : authErrorReason === 'error' ? t('Sign-in failed. Please try again.') : ''
  const shownError = error || authErrorMessage

  function toggleMode() {
    setIsRegister(m => !m)
    setError('')
    setAuthErrorReason('')
    setPassword('')
    setConfirm('')
    // Consent does not survive a mode switch: it was given for a form the user has now left, and
    // carrying a tick across into a different flow is exactly the kind of implied consent to avoid.
    setAccepted(false)
    setConsentError('')
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    // Cleared here as well, and this is a REGRESSION FIX rather than a tidy-up. The `?auth=`
    // bounce message used to live in `error`, so this one `setError('')` dismissed it; splitting
    // the reason into its own state left the banner standing through a submit that had always
    // cleared it. Found in review.
    setAuthErrorReason('')
    setEmailError('')
    setPasswordError('')

    if (!email.trim()) {
      setEmailError(t('Email is required'))
      emailShake.trigger()
      return
    }

    if (isRegister) {
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
    } else if (!password) {
      setPasswordError(t('Password is required'))
      passwordShake.trigger()
      return
    }

    setLoading(true)

    try {
      if (isRegister) {
        await authApi.register(email.trim(), displayName.trim(), password, {
          acceptedLegalVersion: consentReqs!.version,
        })
      } else {
        await authApi.login(email.trim(), password)
      }
      navigate(next, { replace: true })
    } catch (err: unknown) {
      // The one reader of a refused sign-in (authErrorText): the server's sentence in the reader's
      // language, a proxy page's title, the status - then the form's own words.
      setError(authErrorText(err, t, isRegister ? t('Registration failed.') : t('Invalid credentials.')))
      setLoading(false)
    }
  }

  const orDivider = <OrDivider label={t('or')} />

  return (
    // The kit's sign-in card, which scrolls in its own box when the card is taller than the window -
    // a short laptop screen, or the public sandbox, whose account list makes the card longer -
    // instead of spilling over the banner above it, and stays centred whenever it fits. The lockup
    // ONCE (app UI review, F11): the product name is in the mark.
    <AuthLayout
      logo={<Logo />}
      title={isRegister ? t('Create your account') : t('Sign in to your account')}
      // The kit's BackLink, drawn on the router's Link.
      back={
        <BackLink render={(className, content) => <Link to="/" className={className}>{content}</Link>}>
          {t('Back to home')}
        </BackLink>
      }
      // The links under the form start at its left edge on a phone - nothing to tap mid-screen.
      footer={
        <>
          <Presence show={isRegister || selfRegistration}>
            <p>
              {isRegister ? t('Already have an account?') : t("Don't have an account?")}{' '}
              <button type="button" onClick={toggleMode} disabled={loading} className={textLink()}>
                {isRegister ? t('Sign in') : t('Create one')}
              </button>
            </p>
          </Presence>
          {/* In register mode the checkbox above IS the acceptance, so repeating "by continuing
              you agree" underneath would muddy which action constitutes consent. This notice is
              kept for the sign-in and OAuth paths, where there is no checkbox: signing in with
              Google or Microsoft creates the account without ever passing through the form, and
              this is the notice that makes that click an informed acceptance. */}
          {/* ONE KEY with the three documents as slots (Rich), so a translation can order the
              sentence its own way - it was four fragments around three links. */}
          <Presence show={!isRegister}>
            <Caption className="mt-4">
              <Rich
                text={t('By continuing you agree to the {terms}, {privacy} and {eula}.')}
                parts={{
                  terms: <ExternalLink href="https://proxyma.ai/terms">{t('Terms of Service')}</ExternalLink>,
                  privacy: <ExternalLink href="https://proxyma.ai/privacy">{t('Privacy Policy')}</ExternalLink>,
                  eula: <ExternalLink href="https://proxyma.ai/eula">{t('End User License Agreement')}</ExternalLink>,
                }}
              />
            </Caption>
          </Presence>
        </>
      }
    >
      <Presence show={!!shownError}><FormAlert>{shownError}</FormAlert></Presence>

      {/* Sign-in's own parts ease in and out as the form switches between signing in and
          creating an account (Presence). */}
      <Presence show={!isRegister}>
        <SandboxAccountPicker onPick={(pickedEmail, pickedPassword) => {
          setEmail(pickedEmail)
          setPassword(pickedPassword)
        }} />
      </Presence>

      {/* A single generic placeholder, not a guessed count - most deployments configure either
          zero or one SSO provider (often exactly one), so a hardcoded pair of skeleton pills
          mismatched the one real OAuthButtons button that replaces it once GET /auth/providers
          resolves. This window is same-origin (just this app's own API) so it's normally too
          brief to need a label. */}
      <Presence show={!isRegister && (!providersLoaded || oauthProviders.length > 0)}>
        <FadeSwap swapKey={providersLoaded}>
          {providersLoaded
            ? <OAuthButtons providers={oauthProviders} ssoDisplayName={ssoDisplayName} next={next} />
            : <Skeleton className="h-10 w-full rounded-md" />}
        </FadeSwap>
        {orDivider}
      </Presence>

      <form onSubmit={handleSubmit}>
        <fieldset disabled={!isRegister && !providersLoaded} className="flex flex-col gap-4 border-0 p-0 m-0 min-w-0">
          <Presence show={isRegister}>
            <Input
              label={t('Display name')}
              placeholder={t('Your name (optional)')}
              value={displayName}
              onChange={e => setDisplayName(e.target.value)}
              autoFocus={isRegister}
            />
          </Presence>

          <Input
            label={t('Email')}
            type="email"
            // NOT translated: an example address is a format, and "you@example.com" is the
            // shape every reader recognises. RFC 2606 reserves example.com for exactly this.
            placeholder="you@example.com"
            value={email}
            onChange={e => setEmail(e.target.value)}
            autoFocus={!isRegister}
            error={emailError}
            shake={emailShake.shaking}
          />

          <PasswordInput labels={{ show: t('Show password'), hide: t('Hide password') }}
            label={t('Password')}
            placeholder={t('Enter password')}
            value={password}
            onChange={e => setPassword(e.target.value)}
            error={passwordError}
            shake={passwordShake.shaking}
          />

          <Presence show={isRegister}>
            <div className="flex flex-col gap-4">
              <PasswordInput labels={{ show: t('Show password'), hide: t('Hide password') }}
                label={t('Re-enter password')}
                placeholder={t('Re-enter password')}
                value={confirm}
                onChange={e => setConfirm(e.target.value)}
              />
              <PasswordChecklist results={results} className="mb-1" />
            </div>
          </Presence>

          {/* A server that sends no email cannot mail a link; there, the way back in is a link a
              super admin makes in Administration, Users. */}
          <Presence show={!isRegister && providersLoaded} className="self-end -mt-2">
            {passwordResetByEmail ? (
              <Link to="/forgot-password" className={cn(textLink(), 'text-xs')}>
                {t('Forgot password?')}
              </Link>
            ) : (
              <Caption className="text-xs">
                {t('Forgot your password? Ask an administrator for a reset link.')}
              </Caption>
            )}
          </Presence>

          <Presence show={isRegister}>
            <ConsentFields
              requirements={consentReqs}
              accepted={accepted}
              onAcceptedChange={v => { setAccepted(v); setConsentError('') }}
              disabled={loading}
              error={consentError}
            />
          </Presence>

          <Button
            type="submit"
            size="md"
            className="w-full mt-1"
            loading={loading}
            disabled={isRegister && !accepted}
          >
            {loading
              ? (isRegister ? t('Creating account...') : t('Signing in...'))
              : (isRegister ? t('Create account') : t('Sign in'))}
          </Button>
        </fieldset>
      </form>
    </AuthLayout>
  )
}
