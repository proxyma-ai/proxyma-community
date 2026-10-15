/**
 * THE CONNECT MENU'S SEARCH, as plain functions so `node --test` reaches them (connectMenu.test.ts).
 *
 * With thirty-odd source types in a dozen categories the menu is no longer a list to scan, so it
 * takes a query. A type matches when EVERY word of the query is the start of a word in one of the
 * names it is known by - its noun in the reader's language and in English, its plural, its
 * service's name, its category, its wire name. Accents are ignored both ways, so a Vietnamese name
 * is found typed with or without its diacritics, and case never matters.
 */

/** Lower case, with accents and the Vietnamese d-stroke folded away, and runs of spaces as one. */
export function normalizeSearch(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[\u0300-\u036F]/g, '')
    .replace(/\u0111/g, 'd')
    .replace(/\u0110/g, 'D')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim()
}

/** The words of a name - split on anything that is not a letter or a digit ("Bitbucket Cloud", "Jira/Confluence"). */
function wordsOf(text: string): string[] {
  return normalizeSearch(text).split(/[^\p{L}\p{N}]+/u).filter(Boolean)
}

/**
 * Whether a type known by `names` matches `query`. An empty query matches everything. Each query
 * word must START a word of some name: "git" finds GitHub and GitLab, "cloud" finds Bitbucket
 * Cloud, and "hub" does not find GitHub, which is one word.
 */
export function matchesQuery(names: readonly string[], query: string): boolean {
  const terms = normalizeSearch(query).split(/[^\p{L}\p{N}]+/u).filter(Boolean)
  if (terms.length === 0) return true
  const words = names.flatMap(wordsOf)
  const wholes = names.map(n => normalizeSearch(n))
  return terms.every(term => words.some(w => w.startsWith(term)) || wholes.some(w => w.startsWith(term)))
}

/**
 * The groups with only their matching types, and a group left out when none of its types match.
 * Matching the CATEGORY's own name keeps the whole group ("code" lists every code host).
 */
export function filterGroups<G extends { label: string; types: readonly unknown[] }>(
  groups: readonly G[],
  query: string,
  namesOf: (type: G['types'][number]) => string[],
  groupNames: (group: G) => string[] = g => [g.label],
): G[] {
  if (!normalizeSearch(query)) return [...groups]
  const out: G[] = []
  for (const g of groups) {
    const types = matchesQuery(groupNames(g), query) ? g.types : g.types.filter(t => matchesQuery(namesOf(t), query))
    if (types.length > 0) out.push({ ...g, types })
  }
  return out
}

/**
 * Splits groups of the given heights into `columns` runs IN ORDER, each run one column, so the
 * tallest column is as short as it can be. CSS multi-column layout was tried first and, with every
 * group kept whole, left one column ten rows tall beside one holding a single row. Returns the
 * index each column starts at (the first is always 0); fewer columns than asked when there are
 * fewer groups.
 */
export function balanceColumns(heights: readonly number[], columns: number): number[] {
  const n = heights.length
  const k = Math.max(1, Math.min(columns, n))
  if (n === 0) return [0]
  const prefix = [0]
  for (const h of heights) prefix.push(prefix[prefix.length - 1] + h)
  const span = (from: number, to: number) => prefix[to] - prefix[from]
  // best[c][i]: the least possible tallest column laying out the first i groups in c columns.
  const best: number[][] = Array.from({ length: k + 1 }, () => new Array<number>(n + 1).fill(Infinity))
  const cut: number[][] = Array.from({ length: k + 1 }, () => new Array<number>(n + 1).fill(0))
  best[0][0] = 0
  for (let c = 1; c <= k; c++) {
    for (let i = 1; i <= n; i++) {
      for (let j = c - 1; j < i; j++) {
        const tallest = Math.max(best[c - 1][j], span(j, i))
        if (tallest < best[c][i]) { best[c][i] = tallest; cut[c][i] = j }
      }
    }
  }
  const starts: number[] = []
  for (let c = k, i = n; c >= 1; c--) {
    starts.unshift(cut[c][i])
    i = cut[c][i]
  }
  return starts
}

/**
 * The types a deployment switch has turned off (`offered: false`), in the catalog's order. On
 * Enterprise Server these are Teams channels, Teams chats and Outlook until a Super admin turns
 * them on in Settings > Network (each needs a permission the tenant grants). The menu leaves them
 * out - nothing can be added - and names them under the rows, so a reader learns they exist and who
 * turns them on rather than concluding the product has none (release review 2026-10-15, W21c).
 */
export function switchedOffTypes<T extends { offered: boolean; order: number }>(types: readonly T[]): T[] {
  return types.filter(t => !t.offered).sort((a, b) => a.order - b.order)
}
