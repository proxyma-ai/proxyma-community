import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { FEATURES } from '@/config/features'
import { useAuthStore } from '@/store/authStore'
import { catalogApi, type CatalogProtocol, type CatalogType, type ConnectorCatalog } from '../services/catalogApi'
import { mayAskForCatalog } from '../utils/catalogQuery'

const EMPTY: ConnectorCatalog = { types: [], protocols: [], categories: [] }

/**
 * The connector catalog. Prefetched by AppShell, so the Sources page and the dialogs open on it.
 * Short-lived rather than forever: what a deployment offers moves when a super admin flips a
 * Microsoft 365 switch or a license is activated (the license dialog re-reads it at once).
 * Not asked for a visitor on Enterprise Server, where it needs a session - see catalogQuery.ts.
 */
export function useConnectorCatalog() {
  const user = useAuthStore(s => s.user)
  const query = useQuery({
    queryKey: keys.connectorCatalog(),
    queryFn: catalogApi.get,
    staleTime: 30_000,
    enabled: mayAskForCatalog(FEATURES.auth, !!user),
  })
  return { ...query, catalog: query.data ?? EMPTY }
}

/** One source type's descriptor, or undefined while the catalog loads or for a type it does not list. */
export function useCatalogType(type: string | null | undefined): CatalogType | undefined {
  const { catalog } = useConnectorCatalog()
  return type ? catalog.types.find(t => t.type === type) : undefined
}

/** One service's descriptor. */
export function useCatalogProtocol(protocol: string | null | undefined): CatalogProtocol | undefined {
  const { catalog } = useConnectorCatalog()
  return protocol ? catalog.protocols.find(p => p.protocol === protocol) : undefined
}

/** The service a source type reads through, or undefined for one that needs no connection. */
export function useSourceProtocol(type: string | null | undefined): string | undefined {
  return useCatalogType(type)?.protocol ?? undefined
}
