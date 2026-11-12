/**
 * THE FREE-GEMINI QUICK START's two decisions, kept pure so `node --test` reaches them
 * (geminiQuickStart.test.ts). The dialog itself is GeminiQuickStart.tsx.
 *
 * Decided by the maintainer on 2026-10-30: a non-technical owner's hardest step is getting an AI
 * key, and the guided path recommends a free Gemini key first - a Google account is enough, no
 * card. The dialog says plainly that on the free tier Google may use what is sent.
 */

/** Google AI Studio's API key page; a signed-out visitor is sent to sign in and back to it. */
export const GEMINI_KEY_URL = 'https://aistudio.google.com/apikey'

/**
 * Whether the new-chat screen offers the guide: on Desktop and Home Server only (one person, who
 * may set a provider up), once the provider list has loaded, and only while there is none at all.
 * Enterprise Server's providers are an administrator's, chosen for the organization - a free tier
 * that may use what is sent is not the default to put in front of them.
 */
export function offersGeminiQuickStart(opts: { multiUser: boolean; providersLoaded: boolean; providerCount: number }): boolean {
  return !opts.multiUser && opts.providersLoaded && opts.providerCount === 0
}

// Words in a model's name that make it something other than a general chat model: image, speech and
// live-audio variants, robotics, computer use, the embedding models, and Google's own experiments.
const NOT_CHAT = ['lite', 'image', 'tts', 'audio', 'live', 'embedding', 'robotics', 'computer-use', 'exp', 'learnlm', 'aqa']
const UNSTABLE = ['preview', 'latest']

/** The version in a Gemini model's name ("gemini-2.5-flash" is 2.5), or -1 for none. */
function versionOf(name: string): number {
  const m = /^gemini-(\d+(?:\.\d+)?)/.exec(name)
  return m ? Number(m[1]) : -1
}

/** Highest version first, then the shorter (the base model before a dated or suffixed variant). */
function best(names: string[]): string | null {
  if (names.length === 0) return null
  return [...names].sort((a, b) => versionOf(b) - versionOf(a) || a.length - b.length || a.localeCompare(b))[0]
}

const has = (name: string, words: string[]) => words.some(w => name.includes(w))

/**
 * The agent's model and the embedding model for a new Gemini provider, chosen from the list Google
 * itself returned (never a name assumed to exist): the newest STABLE Flash model - what the free
 * tier serves most generously - then a preview or "-latest" Flash, then any other Gemini chat model;
 * and the newest gemini-embedding model, then text-embedding, then the oldest "embedding-NNN".
 * Either is null when the list holds nothing suitable, which the dialog reports rather than guesses.
 */
export function pickGeminiModels(models: readonly string[]): { chat: string | null; embedding: string | null } {
  const names = models.map(m => m.replace(/^models\//, '').trim()).filter(Boolean)
  const gemini = names.filter(n => n.startsWith('gemini-') && !has(n, NOT_CHAT))
  const flash = gemini.filter(n => n.includes('flash'))
  const chat = best(flash.filter(n => !has(n, UNSTABLE)))
    ?? best(flash)
    ?? best(gemini.filter(n => !has(n, UNSTABLE)))
    ?? best(gemini)

  const embeddings = names.filter(n => n.includes('embedding') && !n.includes('exp'))
  const embedding = newest(embeddings.filter(n => /^gemini-embedding-\d+$/.test(n)))
    ?? newest(embeddings.filter(n => n.startsWith('gemini-embedding')))
    ?? newest(embeddings.filter(n => /^text-embedding-\d+$/.test(n)))
    ?? newest(embeddings)

  return { chat, embedding }
}

/** The name ending in the highest number ("-002" over "-001"), the plain name on a tie. */
function newest(names: string[]): string | null {
  const trailing = (n: string) => Number(/(\d+)$/.exec(n)?.[1] ?? -1)
  return [...names].sort((a, b) => trailing(b) - trailing(a) || a.length - b.length || a.localeCompare(b))[0] ?? null
}
