import { http } from '@/services/httpClient'

// What is left of the free allowance an anonymous visitor gets on a server deployment. Counted in
// chat turns rather than dollars - see GuestChatQuotaService for why that is the only meter that
// works before anybody has configured a per-model rate.
export interface GuestAllowance {
  // Whether an anonymous visitor may chat here AT ALL. False on a server deployment that has not
  // set PROXYMA_GUEST_CHAT_ENABLED - POST /api/v1/chat answers 401 there, so the banner has to ask
  // for a sign-in rather than offer a preview it cannot deliver.
  allowed: boolean
  // Whether turns are METERED, which is a different question - see GuestChatQuotaService. Reading
  // this one as "unlimited" when it is false is the bug `allowed` exists to prevent.
  enabled: boolean
  limit: number
  used: number
  remaining: number
  windowHours: number
}

export const guestApi = {
  // Never 401s: /api/v1/chat/** is permitAll, which is the point - the only caller is a visitor
  // with no session. Reads memory and one cookie, and mints nothing.
  allowance: () => http.get<GuestAllowance>('/api/v1/chat/guest-allowance').then(r => r.data),
}
