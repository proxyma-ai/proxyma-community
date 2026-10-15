import { http } from '@/services/httpClient'

export interface ModelPricing {
  id: string
  provider: string
  model: string
  inputPricePerMillion: number
  outputPricePerMillion: number
  cacheReadPricePerMillion: number
  cacheWritePricePerMillion: number
  createdAt: string
  updatedAt: string
}

export const costPricingApi = {
  list: () => http.get<ModelPricing[]>('/api/v1/admin/model-pricing').then(r => r.data),
  create: (data: Omit<ModelPricing, 'id' | 'createdAt' | 'updatedAt'>) =>
    http.post<ModelPricing>('/api/v1/admin/model-pricing', data).then(r => r.data),
  update: (id: string, data: Pick<ModelPricing, 'inputPricePerMillion' | 'outputPricePerMillion' | 'cacheReadPricePerMillion' | 'cacheWritePricePerMillion'>) =>
    http.patch<ModelPricing>(`/api/v1/admin/model-pricing/${id}`, data).then(r => r.data),
  delete: (id: string) => http.delete(`/api/v1/admin/model-pricing/${id}`),
}
