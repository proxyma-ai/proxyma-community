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

/**
 * The provider and model a SUB-AGENT answers with, for its panel's "Waiting for ..." row. With no
 * model of its own it runs on the agent's, and this says so by returning that one. Null under the
 * same rule as above.
 */
export function useSubAgentModel(): AgentModel | null {
  const agent = useAgentModel()
  const canSee = useCanReadAgentSettings()
  const { data: subAgent } = useSettings('sub-agent', canSee)
  const { data: chat } = useSettings('chat', canSee)
  const { data: instances } = useQuery({
    queryKey: keys.providerInstances(),
    queryFn: providersApi.list,
    enabled: canSee,
  })
  if (!canSee) return null
  const model = String(subAgent?.model ?? '').trim()
  if (!model) return agent
  // A model of its own on "Same as the agent" runs on the agent's provider (SubAgentSettings.provider).
  const providerId = String(subAgent?.provider ?? '') || String(chat?.provider ?? '')
  return { provider: instances?.find(p => p.id === providerId)?.name, model }
}
