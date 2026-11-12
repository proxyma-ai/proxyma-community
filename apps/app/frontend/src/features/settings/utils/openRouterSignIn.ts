/**
 * SIGN IN WITH OPENROUTER's decisions, kept pure so `node --test` reaches them (openRouterSignIn.test.ts). The server
 * holds the sign-in (OpenRouterSignIn): the window opens OpenRouter in the person's browser, asks where it stands until
 * it ends, and then picks the agent's model from the list OpenRouter itself returned (2026-11-07: an AI model with no
 * key copied).
 */

/** Where the server says the last sign-in stands (OpenRouterSignIn.Status). */
export type OpenRouterState = 'NONE' | 'PENDING' | 'SIGNED_IN' | 'DENIED' | 'EXPIRED' | 'FAILED'

export interface OpenRouterStatus {
  state: OpenRouterState
  providerId: string | null
  at: string | null
}

/** How often the window asks, and how long it waits for the person's browser - the server's flow lives ten minutes. */
export const OPENROUTER_POLL_MS = 2_000
export const OPENROUTER_WAIT_MS = 10 * 60_000

/** Whether the wait is over: the sign-in ended one way or another, or the window gave up on it. */
export function openRouterWaitOver(status: OpenRouterStatus | undefined, waitedMs: number): boolean {
  if (waitedMs >= OPENROUTER_WAIT_MS) return true
  return !!status && status.state !== 'PENDING' && status.state !== 'NONE'
}

// Families tried in order: what follows tool calls well and costs little per comment drafted. Each is a test on the id
// OpenRouter lists ("google/gemini-2.5-flash"), so a model that is gone is never named.
const PREFERRED: readonly ((id: string) => boolean)[] = [
  id => /^google\/gemini-[\d.]+-flash$/.test(id),
  id => /^google\/gemini-[\d.]+-flash/.test(id) && !/(lite|image|preview|exp)/.test(id),
  id => /^anthropic\/claude-.*haiku/.test(id),
  id => /^openai\/gpt-[\d.]+-mini$/.test(id),
]

/** The numbers in an id, for "newest first": gemini-3.0-flash before gemini-2.5-flash. */
function version(id: string): number[] {
  return (id.match(/\d+(?:\.\d+)?/g) ?? []).map(Number)
}

function newer(a: string, b: string): number {
  const x = version(a)
  const y = version(b)
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (y[i] ?? -1) - (x[i] ?? -1)
    if (d !== 0) return d
  }
  return a.length - b.length || a.localeCompare(b)
}

/**
 * The agent's model for a new OpenRouter provider, from the ids OpenRouter returned: the newest of the first family
 * listed - a paid model, never a ":free" one, whose daily limit a team of agents spends in a morning - else null, which
 * the page reports rather than guessing. An embedding model is not picked: the person's search keeps its own.
 */
export function pickOpenRouterModel(models: readonly string[]): string | null {
  const ids = models.map(m => m.trim()).filter(m => m && !m.endsWith(':free'))
  for (const matches of PREFERRED) {
    const found = ids.filter(matches)
    if (found.length > 0) return [...found].sort(newer)[0]
  }
  return null
}
