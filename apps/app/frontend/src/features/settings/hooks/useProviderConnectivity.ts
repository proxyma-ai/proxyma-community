import { useQuery } from '@tanstack/react-query'
import { providerModelsApi, type ProviderConnectivity } from '../services/providerModelsApi'
import { keys } from '@/services/queryKeys'
import { FEATURES } from '@/config/features'
import { useAuthStore } from '@/store/authStore'

// The startup provider sweep's verdict - see ProviderConnectivityService on the backend.
//
// Polled while the backend reports PROBING rather than fetched once. The probe starts on
// ApplicationReadyEvent and in desktop that same event opens the browser, so the UI reliably asks
// BEFORE there is an answer. One fetch would therefore see PROBING, conclude nothing, and never
// look again - which is indistinguishable from "everything is fine" and is exactly the failure this
// whole check exists to remove.
//
// Polling stops the moment state flips to DONE. The sweep runs once per boot, so there is nothing
// further to learn after that and no reason to keep asking.
//
// ADMINS ONLY in server mode. /api/v1/providers/** is ADMIN-gated, so this was never answerable
// for anyone else - a VIEWER got a 403 and an anonymous visitor got a 401, which httpClient's
// interceptor turned into a forced redirect to /login. AppShell mounts on every route including
// the chat hero, which is open to guests, so that redirect was the whole reason server-mode chat
// appeared to demand a login. The verdict it fetches is only actionable by an admin anyway - the
// toast it drives opens Settings > Providers.
export function useProviderConnectivity() {
  const role = useAuthStore(s => s.user?.role)
  const isAdmin = role === 'SUPER_ADMIN' || role === 'ADMIN'
  return useQuery<ProviderConnectivity>({
    queryKey: keys.providerConnectivity(),
    queryFn: providerModelsApi.connectivity,
    enabled: !FEATURES.auth || isAdmin,
    refetchInterval: q => (q.state.data?.state === 'PROBING' ? 2000 : false),
    // A failed request here means the backend is not answering at all, which the app surfaces in
    // its own ways - retrying forever would just add noise to the console on a dead backend.
    retry: 1,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })
}
