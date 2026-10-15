/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/i18n/richParts.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * A TRANSLATED SENTENCE WITH SLOTS FOR MARKUP, split into its pieces. A sentence with a link or a
 * bold word inside it was written as three to six catalog keys around the markup - "Write to ",
 * the address, ", or read the ", the manual, " first." - and Vietnamese, which orders a sentence
 * differently, could not move the pieces. The key is ONE sentence with named slots now,
 * `Write to {mail}, or read the {manual} first.`, translated whole; `Rich` puts the markup where
 * the translation put each slot. Plain .ts, no imports - `richParts.test.ts`.
 */
export type RichPart = { text: string } | { slot: string }

/** `{name}` slots, as `translate` writes them; a slot with no part stays as text. */
export function richParts(sentence: string, slots: readonly string[]): RichPart[] {
  const out: RichPart[] = []
  const pattern = /\{([A-Za-z0-9_]+)\}/g
  let last = 0
  for (let m = pattern.exec(sentence); m; m = pattern.exec(sentence)) {
    if (!slots.includes(m[1])) continue
    if (m.index > last) out.push({ text: sentence.slice(last, m.index) })
    out.push({ slot: m[1] })
    last = m.index + m[0].length
  }
  if (last < sentence.length) out.push({ text: sentence.slice(last) })
  return out
}
