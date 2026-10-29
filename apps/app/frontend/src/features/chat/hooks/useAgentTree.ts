import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { withReason } from '@/services/apiError'
import { useTranslation } from '@/i18n'
import { useChatInstance, useChatStore } from '../instance/ChatInstanceContext'
import { chatApi } from '../services/chatApi'
import { isBusy, isWorking, withLive, type AgentTreeData, type TreeAgent } from '../utils/agentTree'

const POLL_MS = 3_000

/**
 * THE TREE OF AGENTS this conversation belongs to (GET .../agent-tree), with what its stream said
 * since laid over it - for the agents map, a deployed agent's panel and the strip over a deployed
 * agent's own conversation, which all read this one query.
 *
 * Read again every few seconds while anything in the tree is at work or the conversation's own
 * turn runs: a deployed agent's figures grow with every call, and a grandchild's state reaches no
 * stream this page follows. Under the conversation's own key, so the refresh after every turn
 * reaches it too.
 */
export function useAgentTree(conversationId: string | undefined) {
  const chat = useChatInstance()
  const live = useChatStore(s => s.agentStatus)
  const query = useQuery({
    queryKey: keys.agentTree(conversationId ?? ''),
    queryFn: () => chatApi.getAgentTree(conversationId!),
    enabled: !!conversationId,
    // A conversation the server does not hold yet (an id minted for an attachment) answers 404.
    retry: false,
    refetchInterval: q => (anyAtWork(q.state.data) || chat.store.getState().streaming ? POLL_MS : false),
  })
  const agents = useMemo(() => withLive(query.data?.agents ?? [], live), [query.data, live])
  const self = query.data?.self ? agents.find(a => a.agentId === query.data!.self!.agentId) ?? query.data.self : undefined
  return { ...query, agents, self, rootConversationId: query.data?.rootConversationId }
}

function anyAtWork(data: AgentTreeData | undefined): boolean {
  return !!data?.agents.some(a => isWorking(a) || isBusy(a.status) || a.owesReport)
}

/** One agent of the tree, by its id - undefined while the tree has not listed it. */
export function useTreeAgent(conversationId: string | undefined, agentId: string | undefined): TreeAgent | undefined {
  const { agents } = useAgentTree(conversationId)
  return agentId ? agents.find(a => a.agentId === agentId) : undefined
}

export type AgentAction = 'pause' | 'resume' | 'stop' | 'dismiss'

/**
 * The person's controls over any agent of the tree - Pause, Resume, Stop, Dismiss - asked through
 * the conversation the page has open (`conversationId`), which the server checks is the person's.
 * `busy` names the agent whose request is on its way, so its buttons hold still meanwhile.
 */
export function useAgentControl(conversationId: string | undefined) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const [busy, setBusy] = useState<string | null>(null)
  const control = async (agentId: string, action: AgentAction) => {
    if (!conversationId || busy) return
    setBusy(agentId)
    try {
      await chatApi.controlAgent(conversationId, agentId, action)
    } catch (e) {
      useToastStore.getState().addToast(withReason(t('Could not change the agent'), e), 'danger')
    } finally {
      setBusy(null)
      qc.invalidateQueries({ queryKey: keys.agentTree(conversationId) })
    }
  }
  return { control, busy }
}
