import { useQuery } from '@tanstack/react-query'
import { KeyRound, ShieldCheck, User } from 'lucide-react'
import { useIsSandbox } from '@/hooks/useIsSandbox'
import { useTranslation } from '@/i18n'
import { Caption, Eyebrow, List, ListRow } from '@/components/ui/kit'
import { authApi, type SandboxInfo } from '../services/authApi'

type Role = SandboxInfo['accounts'][number]['role']

const ROLE_ICON: Record<Role, typeof User> = {
  SUPER_ADMIN: ShieldCheck,
  ADMIN: KeyRound,
  VIEWER: User,
}

/**
 * The public sandbox's shared accounts, above the sign-in form: pick one and its address and the
 * published password are filled in. Renders nothing anywhere else.
 *
 * <p>The password is shown on purpose - it is published, and SandboxGuardFilter, not the password,
 * is what limits these accounts. What each one can do is said under its name, so nobody signs in as
 * the Super admin expecting to change settings and meets a refusal instead.
 */
export function SandboxAccountPicker({ onPick }: { onPick: (email: string, password: string) => void }) {
  const { t } = useTranslation()
  const isSandbox = useIsSandbox()
  const { data } = useQuery({
    queryKey: ['auth', 'sandbox'],
    queryFn: authApi.sandbox,
    enabled: isSandbox,
    staleTime: Infinity,
  })

  if (!isSandbox || !data?.password || data.accounts.length === 0) return null
  const password = data.password

  // Labelled here by ROLE rather than with the server's display names, so every word reaches the
  // lookup as a literal the catalog checker can see.
  const name: Record<Role, string> = {
    SUPER_ADMIN: t('Super admin'),
    ADMIN: t('Admin'),
    VIEWER: t('Member'),
  }
  const caption: Record<Role, string> = {
    SUPER_ADMIN: t('Sees every screen, changes nothing'),
    ADMIN: t('Manages users and guardrails'),
    VIEWER: t('Chats, uploads and adds sources'),
  }

  // Kit list rows that are buttons: the role's glyph, its name, and what it can do under it.
  return (
    <div className="mb-6">
      <Eyebrow>{t('Try it as')}</Eyebrow>
      <List className="mt-1">
        {data.accounts.map(account => {
          const Icon = ROLE_ICON[account.role]
          return (
            <ListRow
              key={account.email}
              onClick={() => onPick(account.email, password)}
              leading={<Icon size={16} className="text-ink-caption" aria-hidden="true" />}
              title={name[account.role]}
              caption={caption[account.role]}
            />
          )
        })}
      </List>
      <Caption className="mt-3">
        {t('Password for all three: {password}. Or create your own account.', { password })}
      </Caption>
    </div>
  )
}
