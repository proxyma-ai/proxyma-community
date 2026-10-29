// What an output ceiling or a context window is while nobody has set one, for Settings > Agents.
//
// Since 2026-10-03 the agent runs with the MODEL's own limits until it is given others (see
// ChatSettings.FROM_MODEL on the server). Each of those two controls therefore has a first choice
// that means "not set", and what it says depends on what is known: the number the provider states
// for the model, the agent's own setting for a deployed agent, or neither.
//
// Pure, so `node --test` reaches it: the page must name the same number the server will use, and
// which of the three it is was decided here and nowhere else.

/** What the provider states a model can take in and give back; either may be unstated. */
export interface ModelLimits {
  contextWindow: number | null
  maxOutputTokens: number | null
}

/** The window the server assumes for a model whose provider states none (ChatSettings). */
export const FALLBACK_CONTEXT_WINDOW = 200000

function short(value: number): string {
  return String(Math.round(value * 10) / 10)
}

/**
 * A token count as the settings write one: 8k, 64k, 200k, 1M. Providers state powers of two and
 * round thousands alike - 65536 and 64000 are both "64k" on every vendor's own page - so a
 * multiple of 1024 is counted in 1024s and anything else in 1000s.
 */
export function tokenCountLabel(tokens: number): string {
  if (!Number.isFinite(tokens) || tokens <= 0) return ''
  if (tokens < 1000) return String(tokens)
  const binary = tokens % 1024 === 0
  if (tokens >= 1_000_000) return `${short(tokens / (binary ? 1024 * 1024 : 1_000_000))}M`
  return `${short(tokens / (binary ? 1024 : 1000))}k`
}

/**
 * The first choice of a limit's group - the one that means "not set":
 *
 * - `same`: a deployed agent whose agent HAS a setting takes that setting.
 * - `model`: nothing is set, and the provider states the model's own.
 * - `default`: nothing is set and nothing is stated, for the context window - the server assumes
 *   one, and the page says which.
 * - `provider`: nothing is set and nothing is stated, for the output ceiling - none is sent, and
 *   the provider applies its own, which the page cannot name.
 */
export interface UnsetLimit {
  words: 'same' | 'model' | 'default' | 'provider'
  amount?: string
}

export function unsetLimit(
  kind: 'output' | 'context',
  stated: number | null | undefined,
  // For a deployed agent: the agent's own stored value, '' while the agent follows its model.
  agentSetting = '',
): UnsetLimit {
  const inherited = Number.parseInt(agentSetting, 10)
  if (Number.isFinite(inherited) && inherited > 0) return { words: 'same', amount: tokenCountLabel(inherited) }
  if (typeof stated === 'number' && stated > 0) return { words: 'model', amount: tokenCountLabel(stated) }
  return kind === 'context'
    ? { words: 'default', amount: tokenCountLabel(FALLBACK_CONTEXT_WINDOW) }
    : { words: 'provider' }
}
