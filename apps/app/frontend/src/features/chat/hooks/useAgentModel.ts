import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { useSettings } from '@/features/settings/hooks/useSettings'
import { providersApi } from '@/features/settings/services/providersApi'
import { useCanReadAgentSettings } from './useCanReadAgentSettings'

export interface AgentModel {
  /** The provider instance's name, when it can be resolved. */
  provider?: string
  model: string
}

/**
 * The provider and model the agent answers with - read by the composer's label and by the chat's
 * "Waiting for ..." row, so the two can never name different models.
 *
 * Null where the reader may not see it: on Enterprise Server the chat settings are an
 * administrator's to read, so a user gets the unnamed wording rather than a request that 403s.
 */
export function useAgentModel(): AgentModel | null {
  const canSee = useCanReadAgentSettings()
  const { data: chat } = useSettings('chat', canSee)
  const { data: instances } = useQuery({
    queryKey: keys.providerInstances(),
    queryFn: providersApi.list,
    enabled: canSee,
  })
  if (!canSee || !chat) return null
  const model = String(chat.model ?? '').trim()
  if (!model) return null
  return { provider: instances?.find(p => p.id === chat.provider)?.name, model }
}
