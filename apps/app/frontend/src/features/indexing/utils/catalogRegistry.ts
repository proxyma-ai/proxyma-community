import type { ConnectorCatalog } from '../services/catalogApi'

/**
 * The connector catalog's answer, as plain lookups for code that is not a component - a mention chip
 * built straight into the DOM, a tab's icon chosen in a map. Filled each time the catalog is read
 * (catalogApi.get), so it holds what the server said last; a component that has to re-render when it
 * arrives subscribes with useConnectorCatalog as well.
 *
 * Empty until the first answer, and every reader has a fallback for that moment: AppShell asks for
 * the catalog as the app starts, so in practice nothing is drawn before it.
 */
const typeMarks = new Map<string, string>()
const typeCategories = new Map<string, string>()
const typeNames = new Map<string, string>()
const protocolMarks = new Map<string, string>()

export function rememberCatalog(catalog: ConnectorCatalog): void {
  for (const t of catalog.types) {
    typeMarks.set(t.type, t.mark)
    typeCategories.set(t.type, t.category)
    typeNames.set(t.type, t.displayName)
  }
  for (const p of catalog.protocols) protocolMarks.set(p.protocol, p.mark)
}

/** The mark a source type's descriptor names, or undefined before the catalog answers. */
export function catalogTypeMark(type: string): string | undefined {
  return typeMarks.get(type)
}

/** The category a source type is listed under (FILES, CODE, ...), or undefined. */
export function catalogTypeCategory(type: string): string | undefined {
  return typeCategories.get(type)
}

/** A source type's English name (translate it), or undefined. */
export function catalogTypeName(type: string): string | undefined {
  return typeNames.get(type)
}

/** The mark a service's descriptor names, or undefined. */
export function catalogProtocolMark(protocol: string): string | undefined {
  return protocolMarks.get(protocol)
}
