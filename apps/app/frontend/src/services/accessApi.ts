import { http } from './httpClient'

// The access code of Desktop and the Home Server (AccessGate on the backend). Every API call of
// those editions needs it - as the cookie POST /api/v1/access sets, or as the install's token.
export const accessApi = {
  // 204 when this browser is in; 401 `access_required` when it is not.
  check: () => http.get('/api/v1/access').then(() => true),
  enter: (code: string) => http.post('/api/v1/access', { code }).then(() => undefined),
  // The install's pxm_ token, for an MCP client's configuration (Settings > AI Assistants).
  token: () => http.get<{ token: string }>('/api/v1/access/token').then(r => r.data.token),
}

/** Whether an HTTP error is the backend asking for the access code. */
export function isAccessRequired(err: unknown): boolean {
  const response = (err as { response?: { status?: number; data?: unknown } })?.response
  const data = response?.data as { error?: unknown; untranslated?: { error?: unknown } } | undefined
  return response?.status === 401
    && (data?.error === 'access_required' || data?.untranslated?.error === 'access_required')
}
