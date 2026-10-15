// Removes duplicate entries by id, keeping the first occurrence. Used when flattening
// useInfiniteQuery pages from an offset-paginated (not keyset) endpoint whose sort key can
// change between page fetches (e.g. updatedAt on a row that just changed) - the row can then
// be skipped or duplicated across pages depending on which way it shifted.
export function dedupeById<T extends { id: string }>(items: T[]): T[] {
  const seen = new Set<string>()
  const result: T[] = []
  for (const item of items) {
    if (seen.has(item.id)) continue
    seen.add(item.id)
    result.push(item)
  }
  return result
}
