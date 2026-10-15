import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { useChatInstance, useChatStore } from '../instance/ChatInstanceContext'
import { chatApi } from '../services/chatApi'
import { isLive } from '../utils/agentsGraph'

const POLL_MS = 3_000

/**
 * The conversation's agents and what each has spent, LIVE while anything works - the main agent's
 * turn or a sub-agent (2026-10-05, maintainer's request). The composer's readout and the agents
 * dialog read this one query. A running sub-agent's figure comes from memory on the server and the
 * conversation's total grows with every call, so polling is enough; and once the work stops, the
 * figures are read once more so the last call is in them.
 */
export function useConversationAgents(conversationId: string | undefined) {
  // The chat this is read in - the chat page's, or a panel's.
  const chat = useChatInstance()
  const turnRunning = useChatStore(s => s.streaming)
  const query = useQuery({
    queryKey: keys.conversationAgents(conversationId ?? ''),
    queryFn: () => chatApi.getAgents(conversationId!),
    enabled: !!conversationId,
    refetchInterval: q => (isLive(q.state.data, chat.store.getState().streaming) ? POLL_MS : false),
  })
  const live = isLive(query.data, turnRunning)
  const wasLive = useRef(live)
  const { refetch } = query
  useEffect(() => {
    // refetch() runs whatever `enabled` says, so a turn starting before its conversation has an id
    // asked for /conversations/undefined/agents.
    if (conversationId && live !== wasLive.current) refetch()
    wasLive.current = live
  }, [live, refetch, conversationId])
  return { ...query, live }
}
