/**
 * WHICH SOURCES A CONNECTION'S DELETE WARNING NAMES (the maintainer, 2026-11-03: a connection in use may
 * be deleted, with a warning). The server sends how many sources read through it and the first few by
 * name (ConnectorConnectionService.usage); the warning names at most `shown` of them and counts the rest,
 * so a connection behind forty sources is one line, not forty.
 *
 * Plain .ts so `node --test` reads it (connectionUsage.test.ts).
 */

export interface UsageNames {
  /** The names to list, in the server's order. */
  names: string[]
  /** How many more read through it beyond those listed. */
  more: number
}

export function usageNames(usage: { sources: number; names: readonly string[] }, shown = 3): UsageNames {
  const names = usage.names.filter(n => n.trim() !== '').slice(0, Math.max(0, shown))
  return { names, more: Math.max(0, usage.sources - names.length) }
}
