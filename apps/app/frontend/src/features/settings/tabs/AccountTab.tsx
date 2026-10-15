import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { Trash2, LogOut } from 'lucide-react'
import type { ReactNode } from 'react'
import {
  Badge, Button, Caption, FadeSwap, Field, FormStack, List, ListRow, Meter, Presence, SettingsSection,
  SettingsSections, Skeleton,
} from '@/components/ui/kit'
import { http } from '@/services/httpClient'
import { useUserQuota } from '@/features/chat/hooks/useUserQuota'
import { withReason } from '@/services/apiError'
import { useAuthStore } from '@/store/authStore'
import { useToastStore } from '@/store/toastStore'
import { authApi } from '@/features/auth/services/authApi'
import { AccountDeletionModal } from '@/features/auth/components/AccountDeletionModal'
import { formatFileSize } from '@/utils/formatters'
import { formatSessionReset } from '@/features/chat/utils/formatSessionReset'
import { FEATURES } from '@/config/features'
import { queryClient } from '@/app/QueryClient'
import { keys } from '@/services/queryKeys'
import { useTranslation, type Translate } from '@/i18n'
import { useDates } from '@/hooks/useDates'
import { useSignOut } from '@/features/auth/hooks/useSignOut'

// - Account tab -
//
// Server-mode only (see index.server.tsx - desktop has no auth, so this tab never renders
// there). `ssoEnabled` is deployment-wide capability, not "did this specific user sign in via
// SSO" (AuthUser carries no per-user auth-method field) - same signal UserMenu.tsx's existing
// "Delete account" flow already keys off, reused here rather than inventing a second source of
// truth. Sign out and account deletion duplicate what's already reachable from the header's
// UserMenu dropdown - intentional, matching how most apps offer both a quick-access menu and a
// dedicated account settings destination for the same actions.
//
// REWORKED 2026-09-30 (F30 of that day's review): no generic person glyph beside the name - it
// said nothing the name does not; the role is a neutral outline badge rather than an amber fill
// (amber is this product's WARNING color, and being a super admin is not a warning); an unused
// quota is one line rather than an empty bar and a 0%; dates carry no seconds; and deletion is an
// ordinary row whose ACTION is marked destructive, rather than a red "Danger Zone" heading shouting
// over the whole tab.

// Role is only surfaced to the user themselves for the two elevated roles (SUPER_ADMIN/ADMIN) - a
// plain member shows no role indicator at all, since it carries no information a regular user
// needs to see about themselves.
function roleLabel(role: string, t: Translate): string | null {
  if (role === 'SUPER_ADMIN') return t('Super admin')
  if (role === 'ADMIN') return t('Admin')
  return null
}

// One quota. UNUSED IT IS ONE LINE - its name, and on the right what it holds or when it resets -
// because four empty bars at 0% were four identical shapes saying nothing. The meter appears the
// moment anything is used, which is when there is something for it to show.
function QuotaLine({ label, used, children, idle }: {
  label: string
  used: number
  // The meter, once something is used.
  children: ReactNode
  // What the collapsed line says on the right.
  idle: ReactNode
}) {
  if (used > 0) return <Field label={label}>{children}</Field>
  return <ListRow className="py-0" title={label} trailing={<Caption as="span">{idle}</Caption>} />
}

export function AccountTab() {
  const user = useAuthStore(s => s.user)
  const addToast = useToastStore(s => s.addToast)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const { t } = useTranslation()
  // Minutes, never seconds: "Resets 10/7/2026, 6:41:03 AM" put a ticking clock on a weekly reset.
  // In the reader's language, not the browser's.
  const dates = useDates()

  const { data: providers } = useQuery({
    queryKey: ['auth', 'providers'],
    queryFn: authApi.providers,
    staleTime: Infinity,
  })
  const ssoEnabled = (providers?.oauth ?? []).includes('sso')

  // Storage/token quotas are Enterprise Server's (UserQuotaService.effectiveQuotaBytes,
  // UserQuotaController) - gated by EDITION, not tier, since 2026-10-14, so a trial that ends does
  // not switch every cap off. Desktop has nothing meaningful to fetch or show.
  const quotaEnabled = FEATURES.auth

  // Same storage-quota endpoint/query key SourcesPage polls - shares its cache entry rather
  // than duplicating a fetch when both are mounted.
  const { data: storageQuota } = useQuery({
    queryKey: keys.fileQuota(),
    queryFn: () => http.get<{ usedBytes: number; quotaBytes: number }>('/api/v1/files/quota').then(r => r.data),
    refetchInterval: 30_000,
    enabled: quotaEnabled,
  })
  const { data: tokenQuota } = useUserQuota()

  const logout = useSignOut()

  const requestDeletion = useMutation({
    mutationFn: authApi.requestDeletion,
    onSuccess: (res) => {
      setConfirmingDelete(false)
      addToast(res.mode === 'sso-data-only'
        ? t('Your data has been deleted.')
        : t('Deletion requested. Your account will be permanently deleted in {days} days unless you recover it.',
            { days: res.graceDays }),
        'success')
      queryClient.invalidateQueries({ queryKey: ['me'] })
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to request account deletion.'), e), 'danger'),
  })

  if (!user) return null
  const role = roleLabel(user.role, t)

  return (
    <SettingsSections>
      {/* Who you are, and the one thing you do with it here - sign out - on the same row. Under it,
          how you sign in, where SSO is the way. */}
      <List>
        <ListRow
          className="pt-0"
          title={user.displayName}
          // The kit Badge's outline at its small size: the text's own case and no fill - this is a
          // fact about you, not a status.
          meta={role && <Badge variant="outline" size="sm" label={role} />}
          caption={<span className="block truncate">{user.email}</span>}
          action={
            <Button variant="secondary" onClick={() => logout.mutate()} disabled={logout.isPending}>
              <LogOut size={14} />
              {logout.isPending ? t('Signing out...') : t('Sign out')}
            </Button>
          }
        />
        {/* Known once the sign-in methods have answered, so it eases in rather than appearing. */}
        <Presence show={ssoEnabled}>
          <ListRow
            className="pb-0"
            title={t('Sign-in')}
            trailing={<Caption as="span">{providers?.ssoDisplayName || t('Corporate SSO')}</Caption>}
          />
        </Presence>
      </List>

      {quotaEnabled && (
        <SettingsSection title={t('Usage')} gap="list">
          <FormStack>
            {/* Each quota fades in over its skeleton as it arrives (FadeSwap), rather than the
                bone being swapped for the line in one frame. */}
            <FadeSwap swapKey={!!storageQuota}>
            {storageQuota ? (
              <QuotaLine
                label={t('Storage quota')}
                used={storageQuota.usedBytes}
                idle={storageQuota.quotaBytes > 0
                  ? t('{used} of {limit}', { used: formatFileSize(storageQuota.usedBytes), limit: formatFileSize(storageQuota.quotaBytes) })
                  : t('Unlimited')}
              >
                <Meter used={storageQuota.usedBytes} limit={storageQuota.quotaBytes} format={formatFileSize} />
              </QuotaLine>
            ) : (
              <Skeleton className="h-5 w-full" />
            )}
            </FadeSwap>
            {/* A deployment with no per-user quota answers `enabled: false`, which showed a
                skeleton for ever; it shows nothing, and only the wait for the answer is a bone. */}
            <FadeSwap swapKey={tokenQuota ? tokenQuota.enabled : 'loading'}>
            {tokenQuota?.enabled ? (
              <>
                {/* Session is a rolling window, not a fixed cycle - so it states either that it
                    hasn't started yet or how long is left, never an absolute timestamp like
                    Weekly/Monthly below. Shared with ChatPage.tsx's chat-input quota popover so
                    the two can't drift - see formatSessionReset for why spend, not the
                    timestamp, decides which state this is in. */}
                <QuotaLine
                  label={t('Session token quota')}
                  used={tokenQuota.fiveHourSpendUsd}
                  idle={formatSessionReset(tokenQuota.fiveHourSpendUsd, tokenQuota.fiveHourResetsAt, t)}
                >
                  <Meter
                    used={tokenQuota.fiveHourSpendUsd}
                    limit={tokenQuota.fiveHourLimitUsd}
                    percentOnly
                    unlimitedLabel={t('Unlimited')}
                    caption={formatSessionReset(tokenQuota.fiveHourSpendUsd, tokenQuota.fiveHourResetsAt, t)}
                  />
                </QuotaLine>
                <QuotaLine
                  label={t('Weekly token quota')}
                  used={tokenQuota.weeklySpendUsd}
                  idle={t('Resets {at}', { at: dates.dateTime(tokenQuota.weeklyResetsAt) })}
                >
                  <Meter
                    used={tokenQuota.weeklySpendUsd}
                    limit={tokenQuota.weeklyLimitUsd}
                    percentOnly
                    unlimitedLabel={t('Unlimited')}
                    caption={t('Resets {at}', { at: dates.dateTime(tokenQuota.weeklyResetsAt) })}
                  />
                </QuotaLine>
                <QuotaLine
                  label={t('Monthly token quota')}
                  used={tokenQuota.currentCycleSpendUsd}
                  idle={t('Resets {at}', { at: dates.dateTime(tokenQuota.cycleResetsAt) })}
                >
                  <Meter
                    used={tokenQuota.currentCycleSpendUsd}
                    limit={tokenQuota.effectiveQuotaUsd}
                    percentOnly
                    unlimitedLabel={t('Unlimited')}
                    caption={t('Resets {at}', { at: dates.dateTime(tokenQuota.cycleResetsAt) })}
                  />
                </QuotaLine>
              </>
            ) : !tokenQuota ? (
              <Skeleton className="h-5 w-full" />
            ) : null}
            </FadeSwap>
          </FormStack>
        </SettingsSection>
      )}

      {/* Deletion is an ordinary row. Its ACTION carries the danger ink; the confirmation it
          opens (AccountDeletionModal) carries the danger fill, which is the moment it happens. */}
      <List>
        <ListRow
          className="py-0"
          title={ssoEnabled ? t('Delete my data') : t('Delete account')}
          caption={ssoEnabled
            ? t('Permanently delete your data. Your account stays open since it is managed by SSO.')
            : t('Permanently delete your account and all associated data.')}
          action={
            <Button variant="danger-quiet" onClick={() => setConfirmingDelete(true)}>
              <Trash2 size={14} />
              {t('Delete')}
            </Button>
          }
        />
      </List>

      {confirmingDelete && (
        <AccountDeletionModal
          mode="trigger"
          ssoEnabled={ssoEnabled}
          isSubmitting={requestDeletion.isPending}
          onConfirm={() => requestDeletion.mutate()}
          onCancel={() => setConfirmingDelete(false)}
        />
      )}
    </SettingsSections>
  )
}

