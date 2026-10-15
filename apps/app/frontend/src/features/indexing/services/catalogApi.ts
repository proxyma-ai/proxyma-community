import { http } from '@/services/httpClient'
import { rememberCatalog } from '../utils/catalogRegistry'

/**
 * The connector catalog - every source type and every service this deployment offers, as the
 * backend's descriptors state them (ConnectorCatalogController). The Sources page's Connect menu,
 * tabs and groups, the source and connection dialogs, the marks and the @-mention styles all read
 * this one answer, so a connector added on the server appears in every one of them without a line
 * of this bundle changing.
 *
 * <p>The English in it - names, labels, help - is translated on the page like any other text: the
 * strings are listed in i18n/connectorLabels.generated.ts, read out of the descriptors' source, and
 * the Vietnamese catalog carries them.
 */

/** How a field is entered - one kit form part each (FieldSpecForm). */
export type FieldType = 'TEXT' | 'URL' | 'SECRET' | 'NUMBER' | 'BOOLEAN' | 'SELECT' | 'MULTISELECT' | 'LIST' | 'TEXTAREA'

export interface FieldOption {
  value: string
  label: string
}

export interface FieldSpec {
  key: string
  label: string
  type: FieldType
  required: boolean
  placeholder: string | null
  help: string | null
  defaultValue: string | null
  options: FieldOption[]
  pattern: string | null
  /** Behind "More options" rather than on the form's face. */
  advanced: boolean
  min: number | null
  max: number | null
}

export interface CatalogCapabilities {
  incremental: boolean
  fileTypeFilter: boolean
  recursiveScope: boolean
  shareable: boolean
  edition: 'ALL' | 'ENTERPRISE' | string
  /** A dialog of its own (a folder, uploads, a web page, a network share) rather than the generic one. */
  customDialog: boolean
  liveRead: boolean
  /** Its documents are pushed to it (the ingestion API) rather than read from a service - a custom source. */
  push?: boolean
}

export interface CatalogType {
  /** The wire name - what a source's `type` says. */
  type: string
  displayName: string
  description: string
  noun: string
  nounPlural: string
  addTitle: string
  order: number
  category: string
  mark: string
  /** The service a source of this type reads through, or null for one that needs no connection. */
  protocol: string | null
  fields: FieldSpec[]
  capabilities: CatalogCapabilities
  docsAnchor: string | null
  /** The services whose connections can serve this type (its own first). */
  connectionProtocols?: string[]
  /** False while a deployment switch has it off: existing sources keep a name and a mark, nothing new is added. */
  offered: boolean
  /** False on an Enterprise Server with no Enterprise license in force - listed locked. */
  licensed: boolean
}

export type AuthKind = 'TOKEN' | 'BASIC' | 'KEY_PAIR' | 'SERVICE_ACCOUNT' | 'OAUTH2_CODE' | 'OAUTH2_CLIENT' | 'NONE'

export interface CatalogAuthMethod {
  kind: AuthKind
  label: string
  /** Empty for a sign-in whose OAuth client is the deployment's. */
  fields: FieldSpec[]
  /** Wire names of the source types this way of signing in serves. */
  sourceTypes?: string[]
}

export interface CatalogProtocol {
  protocol: string
  displayName: string
  mark: string
  authMethods: CatalogAuthMethod[]
  baseUrl: FieldSpec | null
  /** Wire names of the source types that read through it. */
  sourceTypes: string[]
  supportsWrite: boolean
  edition: string
  docsAnchor: string | null
  /** A form of its own (an MCP server) rather than the generic one. */
  customDialog: boolean
  licensed: boolean
}

export interface CatalogCategory {
  key: string
  label: string
}

export interface ConnectorCatalog {
  types: CatalogType[]
  protocols: CatalogProtocol[]
  categories: CatalogCategory[]
}

export const catalogApi = {
  get: () => http.get<ConnectorCatalog>('/api/v1/connectors/catalog').then(r => {
    rememberCatalog(r.data)
    return r.data
  }),
}
