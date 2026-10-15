import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { providerModelsApi } from '../services/providerModelsApi'
import type { ComboboxStatus } from '@/components/ui/kit'
import { apiErrorText } from '@/services/apiError'

// enabled=false + refetch(): fetch only when the caller's combobox dropdown is actually opened,
// same deferred-fetch idiom useSettings(prefix, open) already uses for accordion sections.
// Keying by provider means switching providers lands on a fresh 'idle' entry, so reopening the
// dropdown after a provider switch fetches that provider's own models, not a stale cache.
export function useProviderModels(provider: string) {
  const query = useQuery({
    queryKey: keys.providerModels(provider),
    queryFn: () => providerModelsApi.listModels(provider),
    enabled: false,
    retry: false,
    staleTime: 5 * 60_000,
  })

  const status: ComboboxStatus =
    query.fetchStatus === 'fetching' ? 'loading'
    : query.isError ? 'error'
    : query.isSuccess ? 'success'
    : 'idle'

  return {
    options: query.data?.models ?? [],
    status,
    // The backend classifies the failure ("Authentication failed - check the API key.") - shown
    // instead of a bare "Failed to load models", which was the usual symptom of a wrong key.
    errorText: query.isError ? apiErrorText(query.error) : null,
    // No provider, no request: it asked GET /providers//models and reported the failure.
    onOpen: () => { if (provider && query.fetchStatus !== 'fetching') query.refetch() },
  }
}
