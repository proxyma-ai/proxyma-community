import { http } from '@/services/httpClient'

// One provider's verdict from the startup sweep. `probeable` is false only for a provider with no
// model-listing API at all (Azure OpenAI) - it is excluded from the "nothing connects" verdict
// rather than counted as a failure, since we have no evidence either way about it.
export interface ProviderConnectivityEntry {
  provider: string
  probeable: boolean
  reachable: boolean
  code: string
  message: string
}

export interface ProviderConnectivity {
  // PROBING until the boot sweep finishes. Callers must not treat PROBING as "nothing connects".
  state: 'PROBING' | 'DONE'
  probed: number
  reachable: number
  // The backend's own verdict, so the rule for when to warn lives in one place.
  warn: boolean
  providers: ProviderConnectivityEntry[]
}

export const providerModelsApi = {
  // What the provider states the model can take in and give back - either may be null. Never an
  // error: a model nobody could be asked about is two nulls.
  modelLimits: (provider: string, model: string) =>
    http.get<{ contextWindow: number | null; maxOutputTokens: number | null }>(
      `/api/v1/providers/${provider}/model-limits`, { params: { model } },
    ).then(r => r.data),
  listModels: (provider: string) =>
    http.get<{ models: string[] }>(`/api/v1/providers/${provider}/models`).then(r => r.data),
  connectivity: () =>
    http.get<ProviderConnectivity>('/api/v1/providers/connectivity').then(r => r.data),
}
