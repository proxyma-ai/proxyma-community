import { useState } from 'react'
import { Button, DialogBody, DialogDescription, DialogFooter, DialogHeader } from '@/components/ui/kit'
import { WriteToolConsentModal } from '@/features/settings/components/WriteToolConsentModal'
import { settingsApi } from '@/features/settings/services/settingsApi'
import { connectionsApi } from '@/features/settings/services/connectionsApi'
import { queryClient } from '@/app/QueryClient'
import { keys } from '@/services/queryKeys'
import { useChatInstance } from '../instance/ChatInstanceContext'
import { useToastStore } from '@/store/toastStore'
import { chatApi } from '../services/chatApi'
import { toolDisplayName } from '../utils/toolDisplayName'
import type { PendingEnableRequest } from '../types/chat.types'
import { useTranslation, type Translate } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { ModalShell } from '@/components/ui/ModalShell'
import { isGone, withReason } from '@/services/apiError'

interface Props {
  request: PendingEnableRequest
  conversationId: string
  /** Who asks, when it is not the agent itself - under the dialog's title. */
  from?: string
}

// A genuine modal (unlike ToolApprovalPrompt, which is deliberately inline) - rendered whenever
// the backend fires a 'tool-enable-request' SSE event, which happens when a write-capable tool
// call is blocked solely by the calling user's own personal connector-tools setting being off
// (a second gate ahead of the existing per-call approval prompt; an org-wide admin flag still
// rejects immediately with no pause/no prompt). The backend blocks server-side (up to 30 min)
// until this resolves via POST .../tool-enable-decision, one way or another.
//
// WHAT "ENABLE" TURNS ON IS ONE OF TWO THINGS. Connector management is a per-user setting, named by
// `connectorField`. A Jira, Confluence or Bitbucket write is the Write switch on the CONNECTION the
// conversation writes through, named by `connectionId` - the per-service settings this used to flip
// are gone, and flipping a field nobody reads would resolve the prompt and change nothing.
async function enable(request: PendingEnableRequest, t: Translate): Promise<void> {
  if (request.connectionId) {
    // The update requires the name; the event carries it, and the list is the fallback for an
    // event that did not.
    const name = request.connectionName
      ?? (await connectionsApi.list()).find(c => c.id === request.connectionId)?.name
    // In words the reader's toast can say, in the reader's language (CHAT-32).
    if (!name) throw new Error(t('The connection to enable was not found.'))
    await connectionsApi.update(request.connectionId, { name, allowWrite: true })
    queryClient.invalidateQueries({ queryKey: keys.connections() })
    return
  }
  if (request.connectorField) {
    await settingsApi.saveField('connector-tools', request.connectorField, 'true')
    return
  }
  throw new Error(t('There is no setting to enable for this tool.'))
}

// Two requests want the same switch when they name the same connection, or the same setting.
function sameSwitch(a: PendingEnableRequest, b: PendingEnableRequest): boolean {
  return a.connectionId
    ? a.connectionId === b.connectionId
    : !!a.connectorField && !b.connectionId && a.connectorField === b.connectorField
}

export function ToolEnablePrompt({ request, conversationId, from }: Props) {
  const [phase, setPhase] = useState<'ask' | 'consent'>('ask')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const { t } = useTranslation()
  // The chat this prompt belongs to - the chat page's, or a panel's.
  const chat = useChatInstance()
  // The connection's own name beside the service, where there is one: a user with two Jira
  // servers needs to know which of them is about to accept writes.
  const label = request.connectionName
    ? `${request.connectorLabel} (${request.connectionName})`
    : request.connectorLabel

  const keepDisabled = async () => {
    setIsSubmitting(true)
    try {
      await chatApi.resolveToolEnableDecision(conversationId, request.callId, 'keep_disabled')
    } catch (e) {
      useToastStore.getState().addToast(isGone(e)
        ? t('This request is no longer waiting for an answer.')
        : withReason(t('Could not send your decision'), e), 'danger')
    } finally {
      setIsSubmitting(false)
      chat.store.getState().removePendingEnableRequest(request.callId)
    }
  }

  const acceptConsent = async () => {
    setIsSubmitting(true)
    try {
      await enable(request, t)
      await chatApi.resolveToolEnableDecision(conversationId, request.callId, 'enabled')
      // Enabling is a setting, not a per-call answer: every other call of this turn waiting on the
      // same one is answered with it, rather than asking again about a setting that is already on
      // (parallel calls to one tool each showed their own prompt - maintainer's report, 2026-09-26).
      const siblings = chat.store.getState().pendingEnableRequests
        .filter(r => r.callId !== request.callId && sameSwitch(request, r))
      await Promise.all(siblings.map(r =>
        chatApi.resolveToolEnableDecision(conversationId, r.callId, 'enabled')
          .then(() => chat.store.getState().removePendingEnableRequest(r.callId))
          .catch(() => { /* its own prompt stays up and can still be answered */ })))
    } catch (e) {
      useToastStore.getState().addToast(isGone(e)
        ? t('This request is no longer waiting for an answer.')
        : withReason(t('Could not enable {connector} write tools', { connector: label }), e), 'danger')
      try {
        await chatApi.resolveToolEnableDecision(conversationId, request.callId, 'keep_disabled')
      } catch { /* best-effort - the paused tool call must not hang forever */ }
    } finally {
      setIsSubmitting(false)
      chat.store.getState().removePendingEnableRequest(request.callId)
    }
  }

  if (phase === 'consent') {
    return (
      <WriteToolConsentModal
        connectorLabel={label}
        onAccept={acceptConsent}
        onCancel={() => setPhase('ask')}
      />
    )
  }

  return (
    <ModalShell
      // No onClose: the backend is paused on this answer, so it has to be one of the two buttons.
      closeOnBackdrop={false}
      size="md"
      ariaLabel={t('Enable {connector} write tools?', { connector: label })}
    >
      <DialogHeader title={t('Enable {connector} write tools?', { connector: label })} subtitle={from} />
      <DialogBody>
        {/* ONE sentence with the tool's name as a slot (Rich), so a language can put the name where
            its grammar wants it - it was forced first, the sentence split at the <strong> (CHAT-32). */}
        <DialogDescription>
          <Rich
            text={t("{tool} needs {connector} write tools, which you haven't enabled yet. Enable them now, or keep this action blocked?", { connector: label })}
            parts={{ tool: <strong className="text-ink-primary font-semibold">{toolDisplayName(request.toolName, t)}</strong> }}
          />
        </DialogDescription>
      </DialogBody>
      <DialogFooter>
        <Button variant="secondary" onClick={keepDisabled} disabled={isSubmitting}>{t('Keep disabled')}</Button>
        <Button variant="primary" onClick={() => setPhase('consent')} disabled={isSubmitting}>{t('Enable')}</Button>
      </DialogFooter>
    </ModalShell>
  )
}
