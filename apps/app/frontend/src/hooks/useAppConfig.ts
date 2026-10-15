import { useQuery } from '@tanstack/react-query'
import { configApi } from '@/services/configApi'

/**
 * The deployment's public bootstrap answer, `/api/v1/config` - edition switches, the platform, the
 * sandbox flag, the version. Fetched once (ThemeProvider asks first, before sign-in resolves) and
 * shared by every reader at staleTime Infinity, so reading it costs no request.
 *
 * One hook so the key and the options cannot drift: the same `['config']` query used to be typed
 * out by hand in a dozen places. Settings invalidates `['config']` after a Microsoft 365 switch.
 */
export function useAppConfig() {
  return useQuery({
    queryKey: ['config'],
    queryFn: configApi.get,
    staleTime: Infinity,
    retry: false,
  })
}
