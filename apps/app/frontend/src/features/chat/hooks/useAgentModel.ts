import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { useSettings } from '@/features/settings/hooks/useSettings'
import { providersApi } from '@/features/settings/services/providersApi'
import { useCanReadAgentSettings } from './useCanReadAgentSettings'
import { useConversationUsage } from './useConversationUsage'
import { conversationModel, type AgentModel } from '../utils/conversationModel'

export type { AgentModel }

/**
 * The provider and model the agent answers with - read by the composer's label and by the chat's
 * "Waiting for ..." row, so the two can never name different models.
 *
 * Given the open conversation, the model THAT conversation runs on: a saved agent's own when the
 * server says so (utils/conversationModel.ts - a Constellations lead chat), else the chat settings'.
 *
 * The chat settings' is null where the reader may not see it: on Enterprise Server they are an
 * administrator's to read, so a user gets the unnamed wording rather than a request that 403s. An
 * agent's own model is the conversation's, and its owner is told it.
 */
export function useAgentModel(conversationId?: string): AgentModel | null {
  const canSee = useCanReadAgentSettings()
  const { data: chat } = useSettings('chat', canSee)
  const { data: instances } = useQuery({
    queryKey: keys.providerInstances(),
    queryFn: providersApi.list,
    enabled: canSee,
  })
  const { data: usage } = useConversationUsage(conversationId)
  const model = String(chat?.model ?? '').trim()
  const fromSettings = canSee && chat && model ? { provider: instances?.find(p => p.id === chat.provider)?.name, model } : null
  return conversationModel(fromSettings, usage)
}
