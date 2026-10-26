import axios from 'axios'
import { http } from '@/services/httpClient'
import type { RateFields } from '../utils/callCost'

/** One model's rates on one provider. The rate fields are documented on RateFields. */
export interface ModelPricing extends RateFields {
  id: string
  provider: string
  model: string
  createdAt: string
  updatedAt: string
}

/** The built-in list price of a known model - what a model with no rate is priced at. */
export interface ListPrice extends RateFields {
  /** Whose price list it is (Anthropic, OpenAI, Gemini, DeepSeek). */
  vendor: string
  /** The date the list was read, "YYYY-MM-DD". */
  asOf: string
}

export const costPricingApi = {
  list: () => http.get<ModelPricing[]>('/api/v1/admin/model-pricing').then(r => r.data),
  create: (data: RateFields & { provider: string; model: string }) =>
    http.post<ModelPricing>('/api/v1/admin/model-pricing', data).then(r => r.data),
  // Replaces EVERY rate of the row, so the form sends all of them.
  update: (id: string, data: RateFields) =>
    http.patch<ModelPricing>(`/api/v1/admin/model-pricing/${id}`, data).then(r => r.data),
  delete: (id: string) => http.delete(`/api/v1/admin/model-pricing/${id}`),
  /** The list price of a model id, or null when the built-in list does not know it (a 404). */
  listPrice: (model: string) =>
    http.get<ListPrice>('/api/v1/admin/model-pricing/list-price', { params: { model } })
      .then(r => r.data)
      .catch((e: unknown) => {
        if (axios.isAxiosError(e) && e.response?.status === 404) return null
        throw e
      }),
}
