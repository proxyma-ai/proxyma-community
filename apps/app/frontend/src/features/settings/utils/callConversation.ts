// The call log by conversation (2026-11-04): which conversations its filter offers, and how an agent's
// work started, in words. Pure, so `node --test` reaches it.

/** One choice of the log's conversation filter. `label` null: no agent - the page names it by id. */
export interface ConversationOption {
  value: string
  label: string | null
  /** The id's first characters, for a conversation with no agent to name it by. */
  shortId: string
}

/**
 * The filter's choices: the period's costliest conversations as the summary lists them, each once,
 * and - first - the one chosen, when a row chose one the list does not hold (a cheaper conversation),
 * named as that row named it.
 */
export function conversationOptions(
  groups: { conversationId: string; agent?: string | null }[],
  selected: string,
  selectedLabel?: string,
): ConversationOption[] {
  const out: ConversationOption[] = []
  const seen = new Set<string>()
  for (const g of groups) {
    if (!g.conversationId || seen.has(g.conversationId)) continue
    seen.add(g.conversationId)
    out.push({ value: g.conversationId, label: g.agent || null, shortId: shortId(g.conversationId) })
  }
  if (selected && !seen.has(selected)) {
    out.unshift({ value: selected, label: selectedLabel || null, shortId: shortId(selected) })
  }
  return out
}

function shortId(id: string): string {
  return id.slice(0, 8)
}

/**
 * How an agent's work started (the server's LlmCallLabels.TRIGGER_* words), as the tooltip says it
 * after "started by", or null for none. An unknown word is shown as it came.
 */
export function triggerLabel(trigger: string | null | undefined, t: (s: string) => string): string | null {
  if (!trigger) return null
  const words: Record<string, string> = {
    person: t('a person in its panel'),
    schedule: t('a schedule'),
    'run now': t('the Run now button'),
    message: t("a teammate's message"),
    note: t('a note from the server'),
    webhook: t('a webhook'),
    source: t('new documents'),
    chat: t('the main chat'),
  }
  return words[trigger] ?? trigger
}
