import { http } from '@/services/httpClient'

// One deployment-wide astronomy fact for the chat hero, with the source it is attributed to. The
// URL has already been checked against a host allowlist and fetched server-side before it was
// stored (see SpaceFactService), so this can be rendered as a link without further validation.
export interface SpaceFact {
  text: string
  sourceTitle: string
  sourceUrl: string
  // Epoch millis of the generation behind this fact. Always a real generation now - the built-in
  // seed facts that used to carry a 0 here are gone (see SpaceFactService), so a deployment with
  // no reachable chat model has no fact at all rather than a hand-written one.
  generatedAt: number
  // The same fact in the app's other languages, keyed by locale ("vi") - since 2026-10-01, and
  // absent from an older server. The hero falls back to `text` for a language not here.
  translations?: Record<string, string>
}

export const spaceFactApi = {
  // Never 401s and never blocks: /api/v1/chat/** is permitAll, and the endpoint only reads memory
  // - the LLM call that produces this happens on a schedule, not on this request.
  //
  // 204 is the normal answer on a deployment that has never generated one, and axios hands a
  // no-content body over as an empty string rather than undefined - so the emptiness is checked
  // here, once, instead of every caller having to know that. null means "no fact", for which the
  // hero shows its Proxima Centauri default (shownSpaceFact).
  get: () => http.get<SpaceFact | ''>('/api/v1/chat/space-fact')
    .then(r => (r.status === 204 || !r.data ? null : r.data)),
}
