import { useQuery } from '@tanstack/react-query'
import { chatApi } from '../services/chatApi'
import { keys } from '@/services/queryKeys'
import type { ConversationUsage } from '../types/chat.types'

// The usage meter: its own small endpoint and its own key (since 2026-10-17). It shared the whole
// conversation's key and refetched the WHOLE transcript to read six numbers - after every turn, and
// again whenever the history list was invalidated, since that key sat under the list's. With
// background sub-agents each report is a turn, so a run of eight loaded the conversation a dozen
// times. useChat merges each 'usage' event in and invalidates this after a turn.
export function useConversationUsage(conversationId: string | undefined) {
  return useQuery<ConversationUsage>({
    queryKey: keys.conversationUsage(conversationId ?? ''),
    queryFn: () => chatApi.getConversationUsage(conversationId!),
    enabled: !!conversationId,
  })
}
