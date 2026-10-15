import type { EditionCatalogs } from './types'

/**
 * The Community edition's translations beyond the app's own: none. What `@edition-i18n` resolves to
 * in every build except Enterprise's, whose pages bring their own catalog (types.ts). A separate
 * alias from `@edition` so the i18n entry point imports a catalog and nothing that imports it back.
 */
export const EDITION_CATALOGS: EditionCatalogs = { vi: {} }
