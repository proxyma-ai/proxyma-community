import { useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Button, Caption, CopyField, ExternalLink, FieldLabel, FieldSkeleton, InlineCode, InlineConfirm, Input, List, ListRow,
  ListRowSkeleton, MetaList, OneTimeSecret, Presence, Readout,
} from '@/components/ui/kit'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { withReason } from '@/services/apiError'
import { useTranslation } from '@/i18n'
import { ingestApi, type IngestToken } from '../services/ingestApi'

/**
 * A CUSTOM SOURCE'S INGESTION API, on the source's own dialog: where scripts push to, the tokens
 * they push with, and a new one. Its documents, their state and why one failed are the dialog's item
 * list below it - the same list every source has, because they are the same tracked items.
 *
 * <p>The token is shown ONCE, in the kit's OneTimeSecret, as Settings shows an MCP token; the list
 * shows its first characters only. A token opens this source's ingestion endpoints and nothing
 * else, so a script carries no more than it needs.
 */
export function IngestApiPanel({ sourceId }: { sourceId: string }) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const { data, isLoading } = useQuery({
    queryKey: keys.ingest(sourceId),
    queryFn: () => ingestApi.get(sourceId),
  })
  const [label, setLabel] = useState('')
  const [created, setCreated] = useState<string | null>(null)
  const [revoking, setRevoking] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () => ingestApi.createToken(sourceId, label.trim()),
    onSuccess: token => {
      setCreated(token.token)
      setLabel('')
      qc.invalidateQueries({ queryKey: keys.ingest(sourceId) })
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to create the token'), e), 'danger'),
  })

  const revoke = useMutation({
    mutationFn: (tokenId: string) => ingestApi.revokeToken(sourceId, tokenId),
    onSuccess: () => {
      setRevoking(null)
      qc.invalidateQueries({ queryKey: keys.ingest(sourceId) })
      addToast(t('Token revoked'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to revoke token'), e), 'danger'),
  })

  // The address as this page reached the app - what a script on the same network uses too.
  const endpoint = data ? `${window.location.origin}${data.endpoint}` : ''
  const full = !!data && data.tokens.length >= data.limits.maxTokens

  return (
    <div className="px-4 sm:px-6 py-4 space-y-4 border-b border-line/30 animate-fade-in">
      <Caption>
        {t('Push documents to this source from a script, Zapier, Make or n8n. Each request carries one of its tokens, which opens this source and nothing else.')}{' '}
        <ExternalLink href="https://proxyma.ai/docs/api/#ingest">{t('How to push documents')}</ExternalLink>
      </Caption>

      {/* Where scripts push to: a value to copy, in a field. */}
      {data
        ? <CopyField className="animate-fade-in" label={t('Endpoint')} value={endpoint} copyLabel={t('Copy')} copiedLabel={t('Copied')} />
        : <FieldSkeleton />}

      {/* Shown once - the kit's one-time secret, gone when its Done is pressed. */}
      <Presence show={!!created}>
        <OneTimeSecret
          title={t("Copy this token now - it won't be shown again.")}
          value={created ?? ''}
          copyLabel={t('Copy token')}
          copiedLabel={t('Copied')}
          actions={<Button variant="secondary" onClick={() => setCreated(null)}>{t('Done')}</Button>}
        />
      </Presence>

      <div>
        <FieldLabel>{t('Tokens')}</FieldLabel>
        {isLoading ? (
          <ListRowSkeleton />
        ) : data && data.tokens.length > 0 ? (
          <List>
            {data.tokens.map(token => (
              <TokenRow
                key={token.id}
                token={token}
                confirming={revoking === token.id}
                pending={revoke.isPending}
                onRevoke={() => setRevoking(token.id)}
                onCancel={() => setRevoking(null)}
                onConfirm={() => revoke.mutate(token.id)}
              />
            ))}
          </List>
        ) : (
          <Caption className="animate-fade-in">{t('No tokens yet. Create one for each script, so each can be revoked on its own.')}</Caption>
        )}
      </div>

      <form
        className="flex flex-col sm:flex-row sm:items-end gap-3"
        onSubmit={e => {
          e.preventDefault()
          if (!full && !create.isPending) create.mutate()
        }}
      >
        <div className="flex-1 min-w-0">
          <Input
            label={t('Token name')}
            placeholder={t('For example: n8n workflow')}
            value={label}
            maxLength={100}
            onChange={e => setLabel(e.target.value)}
            disabled={create.isPending || full}
          />
        </div>
        <Button type="submit" loading={create.isPending} disabled={full}>
          {!create.isPending && <Plus size={14} />}
          {t('Create token')}
        </Button>
      </form>
      <Presence show={full}>
        <Caption tone="warning">{t('This source has as many tokens as it may. Revoke one you no longer use to create another.')}</Caption>
      </Presence>
    </div>
  )
}

function TokenRow({ token, confirming, pending, onRevoke, onCancel, onConfirm }: {
  token: IngestToken; confirming: boolean; pending: boolean
  onRevoke: () => void; onCancel: () => void; onConfirm: () => void
}) {
  const { t, locale } = useTranslation()
  const date = (iso: string) => new Date(iso).toLocaleDateString(locale, { year: 'numeric', month: 'short', day: 'numeric' })
  return (
    <ListRow
      className="animate-fade-in"
      title={token.label}
      // The prefix is code; the dates are figures.
      caption={<MetaList items={[
        <InlineCode key="prefix">{token.tokenPrefix}...</InlineCode>,
        <Readout key="created">{t('created {date}', { date: date(token.createdAt) })}</Readout>,
        token.lastUsedAt
          ? <Readout key="used">{t('last used {date}', { date: date(token.lastUsedAt) })}</Readout>
          : <span key="unused">{t('never used')}</span>,
      ]} />}
      action={confirming ? (
        <InlineConfirm
          cancelLabel={t('Cancel')}
          confirmLabel={t('Revoke')}
          pendingLabel={t('Revoking...')}
          pending={pending}
          onCancel={onCancel}
          onConfirm={onConfirm}
        />
      ) : (
        <Button variant="danger-quiet" onClick={onRevoke}>
          <Trash2 size={14} />
          {t('Revoke')}
        </Button>
      )}
    />
  )
}
