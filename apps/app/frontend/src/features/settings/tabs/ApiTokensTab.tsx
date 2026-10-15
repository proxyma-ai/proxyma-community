import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { Trash2, Plus } from 'lucide-react'
import {
  Button, DialogBody, DialogFooter, DialogHeader, EmptyState, FadeSwap, Field, FormStack, InlineCode,
  Input, List, ListRow, MetaList, OneTimeSecret, Presence, Readout, SettingsSections,
} from '@/components/ui/kit'
import { mcpTokenApi, type McpToken } from '../services/mcpTokenApi'
import { withReason } from '@/services/apiError'
import { ListSkeleton, TitleAction } from '../pages/shared'
import { useToastStore } from '@/store/toastStore'
import { queryClient } from '@/app/QueryClient'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { PagedItems } from '@/components/ui/ListPager'
import { ChoiceField, RemoveConfirm } from '../components/settingsParts'
import { useDates } from '@/hooks/useDates'

// - API Tokens tab -
//
// MCP access tokens double as a bearer token for /api/v1/search: JwtAuthFilter accepts these
// tokens on ANY endpoint (not just /mcp/**), so pasting the same token into an MCP client's
// config AND into a script's Authorization header both execute with this user's own permissions
// and data scope (SearchContext.setUserId + getCollectionIdsForUser), not the shared/anonymous
// view. See DocsPage's "MCP Integration" tab for the client config example.

const TOKEN_EXPIRY_OPTIONS = [30, 60, 90, 180] as const

export function ApiTokensTab() {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const { data: tokens, isLoading } = useQuery({
    queryKey: keys.mcpTokens(),
    queryFn: mcpTokenApi.list,
  })

  const [generateOpen, setGenerateOpen] = useState(false)
  const [newToken, setNewToken] = useState<string | null>(null)
  const [revokeTarget, setRevokeTarget] = useState<McpToken | null>(null)

  const generate = useMutation({
    mutationFn: ({ label, expiresInDays }: { label: string; expiresInDays: number }) =>
      mcpTokenApi.generate(label, expiresInDays),
    onSuccess: (created) => {
      setNewToken(created.token)
      setGenerateOpen(false)
      queryClient.invalidateQueries({ queryKey: keys.mcpTokens() })
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to generate token'), e), 'danger'),
  })

  const revoke = useMutation({
    mutationFn: (id: string) => mcpTokenApi.revoke(id),
    onSuccess: () => {
      setRevokeTarget(null)
      queryClient.invalidateQueries({ queryKey: keys.mcpTokens() })
      addToast(t('Token revoked'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to revoke token'), e), 'danger'),
  })

  return (
    <SettingsSections intro={t('Personal access tokens for MCP clients or direct API calls. A token acts as you and sees only what you can see.')}>
      <div className="space-y-6">
        {/* On the title's row once there is a list; under the empty state's sentence while there is
            none - one Generate on screen either way, and it eases in and out of its place. */}
        <TitleAction>
          <Presence show={!newToken && !isLoading && (tokens?.length ?? 0) > 0} collapse={false}>
            <Button onClick={() => setGenerateOpen(true)}>
              <Plus size={14} />
              {t('Generate token')}
            </Button>
          </Presence>
        </TitleAction>

        {/* Shown once: the kit's OneTimeSecret - the warning, the token in a field with the copy
            button at its end (which falls back to a hidden textarea where the clipboard API is
            refused - an http:// LAN address of a home server), and Done. */}
        <Presence show={!!newToken}>
          <OneTimeSecret
            title={t("Copy this token now - it won't be shown again.")}
            value={newToken ?? ''}
            copyLabel={t('Copy token')}
            copiedLabel={t('Copied')}
            actions={<Button variant="secondary" onClick={() => setNewToken(null)}>{t('Done')}</Button>}
          />
        </Presence>

        <FadeSwap swapKey={isLoading ? 'loading' : (tokens?.length ?? 0) > 0}>
        {isLoading ? (
          <ListSkeleton count={2} />
        ) : tokens && tokens.length > 0 ? (
          <PagedItems items={tokens}>
            {page => (
              <List>
                {page.map(token => (
                  <TokenRow key={token.id} token={token} onRevokeClick={() => setRevokeTarget(token)} />
                ))}
              </List>
            )}
          </PagedItems>
        ) : (
          // EMPTY STATE: the sentence and its one action (F12).
          <EmptyState
            title={t('No tokens yet.')}
            action={
              <Presence show={!newToken} collapse={false}>
                <Button onClick={() => setGenerateOpen(true)}>
                  <Plus size={14} />
                  {t('Generate token')}
                </Button>
              </Presence>
            }
          >
            {t('Generate one for each client, so each can be revoked on its own.')}
          </EmptyState>
        )}
        </FadeSwap>
      </div>

      {generateOpen && (
        <GenerateTokenModal
          isPending={generate.isPending}
          onCancel={() => setGenerateOpen(false)}
          onGenerate={(label, expiresInDays) => generate.mutate({ label, expiresInDays })}
        />
      )}

      {revokeTarget && (
        <RemoveConfirm
          name={revokeTarget.label}
          title={t('Revoke token')}
          verb={t('revoke')}
          confirmLabel={t('Revoke')}
          pendingLabel={t('Revoking...')}
          consequences={[t('Any client currently using this token will immediately lose access.')]}
          pending={revoke.isPending}
          onConfirm={() => revoke.mutate(revokeTarget.id)}
          onCancel={() => setRevokeTarget(null)}
        />
      )}
    </SettingsSections>
  )
}

function GenerateTokenModal({ isPending, onCancel, onGenerate }: {
  isPending: boolean
  onCancel: () => void
  onGenerate: (label: string, expiresInDays: number) => void
}) {
  const { t } = useTranslation()
  const [label, setLabel] = useState('')
  const [expiresInDays, setExpiresInDays] = useState<number>(90)
  const { dirty } = useFormDirty({ label, expiresInDays })
  const generate = () => onGenerate(label.trim() || t('API Token'), expiresInDays)
  // "30 days", in the reader's language - it was "30d", an English abbreviation in every language.
  const expiryOptions = TOKEN_EXPIRY_OPTIONS.map(days => ({ key: String(days), label: t('{count} day|{count} days', { count: days }) }))

  return (
    <ModalShell
      onClose={onCancel}
      closable={!isPending}
      dirty={dirty}
      onSubmit={isPending ? undefined : generate}
      initialFocus="first-field"
      size="md"
      ariaLabel={t('Generate access token')}
    >
      {requestClose => (<>
        <DialogHeader title={t('Generate access token')} />
        <DialogBody>
          <FormStack>
            <Field label={t('Name')}>
              {id => (
                <Input
                  id={id}
                  value={label}
                  onChange={e => setLabel(e.target.value)}
                  placeholder={t('e.g. Claude Code (laptop)')}
                />
              )}
            </Field>
            <ChoiceField
              label={t('Expires in')}
              value={String(expiresInDays)}
              onChange={v => setExpiresInDays(Number(v))}
              options={expiryOptions}
              translated
              horizontal
              figures
            />
          </FormStack>
        </DialogBody>
        <DialogFooter>
          <Button variant="secondary" onClick={requestClose} disabled={isPending}>{t('Cancel')}</Button>
          <Button onClick={generate} disabled={isPending}>
            {isPending ? t('Generating...') : t('Generate')}
          </Button>
        </DialogFooter>
      </>)}
    </ModalShell>
  )
}

function TokenRow({ token, onRevokeClick }: { token: McpToken; onRevokeClick: () => void }) {
  const { t } = useTranslation()
  const { date: formatDate } = useDates()
  const expired = token.expiresAt ? new Date(token.expiresAt).getTime() < Date.now() : false
  return (
    <ListRow
      title={token.label}
      // The prefix is code; the dates are figures.
      caption={<MetaList items={[
        <InlineCode key="prefix">{token.tokenPrefix}...</InlineCode>,
        <Readout key="created">{t('created {date}', { date: formatDate(token.createdAt) })}</Readout>,
        token.lastUsedAt && <Readout key="used">{t('last used {date}', { date: formatDate(token.lastUsedAt) })}</Readout>,
        token.expiresAt && (
          <Readout key="expires" tone={expired ? 'danger' : 'inherit'}>
            {expired ? t('Expired') : t('Expires {date}', { date: formatDate(token.expiresAt) })}
          </Readout>
        ),
      ]} />}
      action={
        <Button variant="danger-quiet" onClick={onRevokeClick}>
          <Trash2 size={14} />
          {t('Revoke')}
        </Button>
      }
    />
  )
}

