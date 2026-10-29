import { useState } from 'react'
import { Link } from 'react-router-dom'
import { AuthLayout, FadeSwap, Button, FormAlert, Input, Presence, textLink } from '@/components/ui/kit'
import { Logo } from '@/components/ui/Logo'
import { authApi } from '../services/authApi'
import { usePageTitle } from '@/hooks/usePageTitle'
import { useShake } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { authErrorText } from '../utils/authErrorText'

export function ForgotPasswordPage() {
  usePageTitle('Forgot password')
  const { t } = useTranslation()

  const [email, setEmail] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [sent, setSent] = useState(false)
  const [emailError, setEmailError] = useState('')
  const emailShake = useShake()

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setEmailError('')

    if (!email.trim()) {
      setEmailError(t('Email is required'))
      emailShake.trigger()
      return
    }

    setLoading(true)

    try {
      await authApi.forgotPassword(email.trim())
      setSent(true)
    } catch (err: unknown) {
      // The one reader of a refused sign-in form (authErrorText).
      setError(authErrorText(err, t, t('Could not send reset email. Please try again.')))
    } finally {
      setLoading(false)
    }
  }

  return (
    // The kit's sign-in card; the lockup ONCE (app UI review, F11).
    <AuthLayout
      logo={<Logo size={22} />}
      title={t('Reset your password')}
      // Starts at the form's left edge on a phone - nothing to tap mid-screen.
      footer={<Link to="/login" className={textLink()}>{t('Back to sign in')}</Link>}
    >
      <Presence show={!!error}><FormAlert>{error}</FormAlert></Presence>

      {/* The form, then what it did - faded across once the link is sent. */}
      <FadeSwap swapKey={sent}>
      {sent ? (
        <FormAlert tone="success">
          {t('If an account exists for that email, a password reset link has been sent.')}
        </FormAlert>
      ) : (
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <Input
            label={t('Email')}
            type="email"
            // An example address, not prose - see the note on LoginPage's own.
            placeholder="you@example.com"
            value={email}
            onChange={e => setEmail(e.target.value)}
            autoFocus
            error={emailError}
            shake={emailShake.shaking}
          />

          <Button type="submit" size="md" className="w-full" loading={loading}>
            {loading ? t('Sending...') : t('Send reset link')}
          </Button>
        </form>
      )}
      </FadeSwap>
    </AuthLayout>
  )
}
