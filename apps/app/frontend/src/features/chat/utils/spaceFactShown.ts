import type { SpaceFact } from '../services/spaceFactApi'

// WHICH ASTRONOMY FACT THE NEW-CHAT SCREEN SHOWS, since 2026-10-15 (maintainer's request: "Default
// space fact is about Proxima Centauri"). Plain .ts so `node --test` reaches it.
//
// The deployment's own fact when it has one - SpaceFactService writes one every half hour while
// somebody looks, through the chat model. When it has none (the endpoint's 204: no model, no
// network, every link refused so far) or the request failed, the DEFAULT: one fixed sentence about
// Proxima Centauri, the nearest star to the Sun and the star the product's name comes from. Nothing
// while the reader has the fact off, or while the first answer is still on its way - the default
// is not painted first and then swapped for the generated one on every load.
//
// The sentence itself is in ChatPage, where it reaches the translation function as one literal (a
// default kept here would be invisible to the catalog checker); its source is fixed here.

export const DEFAULT_FACT_SOURCE = {
  sourceTitle: 'Wikipedia - Proxima Centauri',
  sourceUrl: 'https://en.wikipedia.org/wiki/Proxima_Centauri',
} as const

export interface SpaceFactQueryState {
  /** The reader has the fact on (useSpaceFactPreference). */
  enabled: boolean
  /** The query's status: 'pending' until the first answer, then 'success' or 'error'. */
  status: 'pending' | 'success' | 'error'
  /** The deployment's fact, or null when it has none. */
  data: SpaceFact | null | undefined
}

export function shownSpaceFact(query: SpaceFactQueryState, defaultText: string): SpaceFact | null {
  if (!query.enabled || query.status === 'pending') return null
  if (query.status === 'success' && query.data) return query.data
  return { text: defaultText, ...DEFAULT_FACT_SOURCE, generatedAt: 0 }
}
