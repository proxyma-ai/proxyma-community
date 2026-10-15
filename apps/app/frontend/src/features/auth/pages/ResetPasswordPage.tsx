import { useState } from 'react'
import { useNavigate, useSearchParams, Link } from 'react-router-dom'
import { AuthLayout, Button, FormAlert, PasswordInput, Presence, textLink } from '@/components/ui/kit'
import { Logo } from '@/components/ui/Logo'
import { PasswordChecklist } from '../components/PasswordChecklist'
import { usePasswordValidation } from '../hooks/usePasswordValidation'
import { authApi } from '../services/authApi'
import { usePageTitle } from '@/hooks/usePageTitle'
import { useShake } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { authErrorText } from '../utils/authErrorText'

export function ResetPasswordPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  usePageTitle('Reset password')
  const { t } = useTranslation()

  const token = params.get('token')

  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [loading, setLoading] = useState(false)
  const passwordShake = useShake()

  const { results, allPass } = usePasswordValidation(password, confirm, 'register')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')

    if (!allPass) {
      passwordShake.trigger()
      return
    }

    setLoading(true)

    try {
      await authApi.resetPassword(token!, password)
      setSuccess(t('Password reset. Redirecting to login...'))
      setTimeout(() => navigate('/login', { replace: true }), 1500)
    } catch (err: unknown) {
      // The one reader of a refused sign-in form (authErrorText) - it read `error` alone, so a
      // ProblemDetail's reason was lost.
      setError(authErrorText(err, t, t('Reset failed. Please try again.')))
      setLoading(false)
    }
  }

  return (
    // The kit's sign-in card; the lockup ONCE (app UI review, F11).
    <AuthLayout
      logo={<Logo />}
      title={t('Choose a new password')}
      // Starts at the form's left edge on a phone - nothing to tap mid-screen.
      footer={!token && <Link to="/forgot-password" className={textLink()}>{t('Request a new link')}</Link>}
    >
      {!token ? (
        <FormAlert>{t('Invalid or missing reset link.')}</FormAlert>
      ) : (
        <>
          <Presence show={!!error}><FormAlert>{error}</FormAlert></Presence>
          <Presence show={!!success}><FormAlert tone="success">{success}</FormAlert></Presence>

          {/* Eased away once the password is reset, as the confirmation above eases in. */}
          <Presence show={!success}>
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <PasswordInput labels={{ show: t('Show password'), hide: t('Hide password') }}
                label={t('New password')}
                placeholder={t('Enter new password')}
                value={password}
                onChange={e => setPassword(e.target.value)}
                autoFocus
                shake={passwordShake.shaking}
              />

              <PasswordInput labels={{ show: t('Show password'), hide: t('Hide password') }}
                label={t('Re-enter password')}
                placeholder={t('Re-enter new password')}
                value={confirm}
                onChange={e => setConfirm(e.target.value)}
              />

              <PasswordChecklist results={results} className="mb-1" />

              <Button type="submit" size="md" className="w-full" loading={loading}>
                {loading ? t('Resetting...') : t('Reset password')}
              </Button>
            </form>
          </Presence>
        </>
      )}
    </AuthLayout>
  )
}
