import { useQueries } from '@tanstack/react-query'
import { FEATURES } from '@/config/features'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { indexingApi } from '@/features/indexing/services/indexingApi'
import { keys } from '@/services/queryKeys'

/**
 * The source type of each name a sent message mentioned, so its chip keeps the composer's color.
 * A message keeps only the NAMES it mentioned (ChatMessageResponse.mentions), so the type is looked
 * up the way the composer's @ dropdown finds it - the same listing, by name - and remembered.
 * A name that matches no source any more (renamed, removed) has no type and keeps the neutral chip.
 *
 * Only for a reader who can list sources: a server visitor without an account cannot, and a
 * request they may not make would take them to the sign-in page (see .claude/rules/frontend.md).
 */
export function useMentionTypes(names: string[] | undefined): Map<string, string> {
  const { user } = useAuth()
  const canList = !FEATURES.auth || !!user
  const unique = canList ? [...new Set(names ?? [])] : []
  const results = useQueries({
    queries: unique.map(name => ({
      queryKey: keys.connectorsByType('all', name, true),
      queryFn: () => indexingApi.listByType('all', 0, name, 8, true),
      staleTime: 5 * 60_000,
    })),
  })
  const types = new Map<string, string>()
  unique.forEach((name, i) => {
    const match = results[i]?.data?.items.find(c => c.name === name)
    if (match) types.set(name, match.type)
  })
  return types
}
