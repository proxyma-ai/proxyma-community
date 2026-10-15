import { useEffect, useState } from 'react'
import { AuthLayout, Button, Input } from '@/components/ui/kit'
import { Logo } from '@/components/ui/Logo'
import { FEATURES } from '@/config/features'
import { useShake } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { accessApi, isAccessRequired } from '@/services/accessApi'
import { useAccessStore } from '@/store/accessStore'

// Desktop and the Home Server ask for their access code before the app loads (AccessGate on the
// backend): every API call needs it, and a browser that has not presented it is answered 401
// `access_required`. The Desktop window and the tray hand the code over by themselves, so this
// screen is what a Home Server's visitor sees first - and what a desktop user sees only after
// typing the address into a browser by hand.
//
// Enterprise Server has accounts and its own sign-in page; this is a passthrough there.
export function AccessGate({ children }: { children: React.ReactNode }) {
  if (FEATURES.auth) return <>{children}</>
  return <SingleUserAccessGate>{children}</SingleUserAccessGate>
}

function SingleUserAccessGate({ children }: { children: React.ReactNode }) {
  const locked = useAccessStore(s => s.locked)
  const [probe, setProbe] = useState<'checking' | 'in' | 'out'>('checking')

  // One question before anything else asks the API, so a visitor without the code meets this
  // screen rather than a page full of failed requests. A backend that does not answer at all is
  // not a locked one: the app renders and reports that the way it always has.
  useEffect(() => {
    let cancelled = false
    accessApi.check()
      .then(() => { if (!cancelled) setProbe('in') })
      .catch(err => { if (!cancelled) setProbe(isAccessRequired(err) ? 'out' : 'in') })
    return () => { cancelled = true }
  }, [])

  if (probe === 'checking') return null
  if (probe === 'out' || locked) return <AccessCodeScreen />
  return <>{children}</>
}

function AccessCodeScreen() {
  const { t } = useTranslation()
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const shake = useShake()

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    if (!code.trim()) {
      setError(t('Enter the access code'))
      shake.trigger()
      return
    }
    setLoading(true)
    try {
      await accessApi.enter(code.trim())
      // A full load rather than a re-render: every query on the page was refused before the
      // cookie existed, and starting over is simpler than finding each one.
      window.location.replace('/')
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number } })?.response?.status
      setError(status === 401 ? t('That access code is not right.') : t('Proxyma did not answer. Try again.'))
      shake.trigger()
      setLoading(false)
    }
  }

  return (
    <div className="h-[var(--app-height,100dvh)]">
      <AuthLayout
        logo={<Logo />}
        title={t('Enter the access code')}
        description={t('Proxyma prints it in its log when it starts. On the computer Proxyma runs on, its window and tray icon open it without one.')}
      >
        <form onSubmit={submit} className="flex flex-col gap-4">
          <Input
            label={t('Access code')}
            type="password"
            autoComplete="current-password"
            value={code}
            onChange={e => setCode(e.target.value)}
            autoFocus
            error={error}
            shake={shake.shaking}
          />
          <Button type="submit" size="md" className="w-full" loading={loading}>
            {t('Continue')}
          </Button>
        </form>
      </AuthLayout>
    </div>
  )
}
