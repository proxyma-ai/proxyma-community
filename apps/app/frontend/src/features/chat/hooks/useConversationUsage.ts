import { useQuery } from '@tanstack/react-query'
import { chatApi } from '../services/chatApi'
import { keys } from '@/services/queryKeys'
import type { ConversationUsage } from '../types/chat.types'

// Shares its query key with useChat's own imperative chatApi.getConversation() fetches, so the
// invalidation useChat already runs after every turn ('done'/'error' SSE branches) refetches this
// too - no separate plumbing needed to keep the token-usage meter live turn to turn.
export function useConversationUsage(conversationId: string | undefined) {
  return useQuery({
    queryKey: keys.conversation(conversationId ?? ''),
    queryFn: () => chatApi.getConversation(conversationId!),
    enabled: !!conversationId,
    select: (data): ConversationUsage => ({
      inputTokens: data.inputTokens,
      outputTokens: data.outputTokens,
      cacheReadTokens: data.cacheReadTokens,
      cacheWriteTokens: data.cacheWriteTokens,
      contextTokens: data.contextTokens,
      contextTokenLimit: data.contextTokenLimit,
    }),
  })
}
