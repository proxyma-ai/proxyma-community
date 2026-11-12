// WHAT THE COMPOSER'S @ LIST OFFERS BESIDE THE SOURCES - the edition's own mentions (`@edition`'s
// useEditionMentions: on Proxyma Enterprise and the add-on build, the Constellations projects whose
// lead the person may address). The edition lists them all once; the composer narrows them here as
// the person types, and a sent message finds a chip's glyph here by name. Pure, so `node --test`
// reaches it.

/** The shape this needs of an edition mention (EditionMention in src/edition/types.ts). */
export interface NamedMention {
  name: string
}

/** How many of them the @ list shows at once, under the sources. */
export const EDITION_MENTIONS_SHOWN = 6

/**
 * The mentions whose name contains what was typed after the "@", ignoring case: names that START
 * with it first, then the rest, each in name order - at most {@link EDITION_MENTIONS_SHOWN}. An empty
 * query lists the first ones.
 */
export function matchEditionMentions<T extends NamedMention>(all: readonly T[], query: string,
                                                             max: number = EDITION_MENTIONS_SHOWN): T[] {
  const q = query.trim().toLowerCase()
  const hits = all.filter(m => m.name.toLowerCase().includes(q))
  const byName = (a: T, b: T) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
  const starts = hits.filter(m => m.name.toLowerCase().startsWith(q)).sort(byName)
  const rest = hits.filter(m => !m.name.toLowerCase().startsWith(q)).sort(byName)
  return [...starts, ...rest].slice(0, Math.max(0, max))
}

/** The edition mention a chip of this name is, if any - exact name, as the composer inserted it. */
export function editionMentionNamed<T extends NamedMention>(all: readonly T[], name: string): T | undefined {
  return all.find(m => m.name === name)
}
