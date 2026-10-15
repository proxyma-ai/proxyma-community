import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { providerModelsApi } from '../services/providerModelsApi'
import type { ModelLimits } from '../utils/modelDefaults'

/**
 * What the provider states a model can take in and give back, for the "not set" choice of the
 * agent's two limits. Undefined until it answers and while no model is chosen; the server
 * remembers the answer, so this is one request per model per session.
 */
export function useModelLimits(provider: string, model: string): ModelLimits | undefined {
  const { data } = useQuery({
    queryKey: keys.modelLimits(provider, model),
    queryFn: () => providerModelsApi.modelLimits(provider, model),
    enabled: provider !== '' && model.trim() !== '',
    retry: false,
    staleTime: 10 * 60_000,
  })
  return data
}
