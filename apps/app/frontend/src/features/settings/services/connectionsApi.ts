import { useQuery } from '@tanstack/react-query'
import { http } from '@/services/httpClient'
import { keys } from '@/services/queryKeys'
import { ENV } from '@/config/env'
import { EDITION_SIGN_INS } from '@edition'

/**
 * The caller's own CONNECTIONS - one server (or one Microsoft 365 sign-in) each, with its own name,
 * credential and Read/Write switches. Built on the LLM provider pattern (providersApi): a user adds
 * as many as they like per service, and every source names the one it reads through.
 *
 * <p>Mirrors ConnectorConnectionView on the backend. Like a provider there is no secret field on a
 * read in either direction: the server sends a mask and a boolean, and the real value only ever
 * travels one way.
 */
// A service's name as the server states it (ConnectionProtocol on the backend) - JIRA, GITHUB,
// MICROSOFT_365, MCP_SERVER. A string rather than a union: the services are the connector catalog's,
// and a new one arrives from the server without this bundle changing.
export type ConnectionProtocol = string

/** How an MCP server is reached: over HTTP in either of its two forms, or as a program started here. */
export type McpTransport = 'STREAMABLE_HTTP' | 'SSE' | 'STDIO'

/** What an MCP server is sent: nothing, one bearer token, named headers, or an OAuth sign-in. */
export type McpAuth = 'NONE' | 'BEARER' | 'HEADERS' | 'OAUTH'

/**
 * An MCP server connection's own settings, as the server reads them back. Header and environment
 * VALUES are secrets and never come back - only their names do.
 */
export interface McpView {
  transport: McpTransport
  command: string | null
  args: string[]
  auth: McpAuth
  headerNames: string[]
  envNames: string[]
  /** Usable by everyone on this deployment (Enterprise Server, set by a Super admin). */
  shared: boolean
  /** An OAuth server with a stored grant. */
  signedIn: boolean
  oauthClientId: string | null
  oauthScope: string | null
  /** The OAuth client was registered automatically rather than typed in. */
  oauthClientRegistered: boolean
}

/**
 * What a form sends for an MCP server. A header or variable given with a blank value keeps the stored
 * one; one left out is removed. The OAuth client is optional - most servers register one themselves.
 */
export interface McpInput {
  transport: McpTransport
  command?: string
  args?: string[]
  auth: McpAuth
  headers?: Record<string, string>
  env?: Record<string, string>
  shared?: boolean
  oauthClientId?: string
  oauthClientSecret?: string
  oauthScope?: string
}

/** TOKEN is one access token, BASIC a username and password, OAUTH a sign-in with no secret to type. */
export type ConnectionAuthKind = 'TOKEN' | 'BASIC' | 'OAUTH'

export interface Connection {
  id: string
  name: string
  protocol: ConnectionProtocol
  protocolLabel: string
  authKind: ConnectionAuthKind
  baseUrl: string | null
  username: string | null
  secretMasked: string | null
  secretConfigured: boolean
  isDefault: boolean
  allowRead: boolean
  allowWrite: boolean
  supportsWrite: boolean
  /** Wire names of the source types it can serve - another service's too, for an Atlassian Cloud sign-in. */
  sourceTypes?: string[]
  /** Microsoft 365 only. Null for a connection nobody has signed in to yet. */
  status: 'CONNECTED' | 'NEEDS_RECONNECT' | null
  /** Why a sign-in is needed again - written by the backend, shown verbatim. */
  statusDetail: string | null
  /** The signed-in Microsoft 365 account. */
  accountLabel: string | null
  connectedAt: string | null
  /** MCP servers only. */
  mcp?: McpView | null
  /** Which of the service's sign-in methods it uses (an AuthKind name); null for the service's first. */
  authMethod?: string | null
  /** The method's settings that are not secret - a workspace id, an OAuth client id. */
  settings?: Record<string, string> | null
  /** Which of the method's secret fields hold a value - never the values. */
  secretsConfigured?: string[] | null
}

// Absent or null means "unchanged" on an update. `secret` is write-only and a blank one keeps what
// is stored - clearing is the explicit `clearSecret`, never an empty string, which is only ever an
// accident. The protocol is fixed once a connection exists, so an update does not send it.
export interface ConnectionInput {
  name: string
  protocol?: ConnectionProtocol
  baseUrl?: string
  username?: string
  secret?: string
  clearSecret?: boolean
  isDefault?: boolean
  allowRead?: boolean
  allowWrite?: boolean
  mcp?: McpInput
  /** The sign-in method (an AuthKind name), for a service that offers more than one. */
  authMethod?: string
  /** The method's fields by key - a secret left out keeps what is stored. */
  fields?: Record<string, string>
}

export interface ConnectionTestResult {
  ok: boolean
  // The English sentence. `template` is the same with {placeholders} filled from `params` - the
  // translation key; see connectionTestMessage.
  message: string
  template?: string
  params?: Record<string, string>
}

export const connectionsApi = {
  list: () =>
    http.get<Connection[]>('/api/v1/connections/instances').then(r => r.data),
  create: (input: ConnectionInput) =>
    http.post<Connection>('/api/v1/connections/instances', input).then(r => r.data),
  update: (id: string, input: ConnectionInput) =>
    http.patch<Connection>(`/api/v1/connections/instances/${id}`, input).then(r => r.data),
  remove: (id: string) =>
    http.delete<void>(`/api/v1/connections/instances/${id}`).then(() => undefined),
  // Always 200 with a verdict - a failed test is an answer, not an error.
  test: (id: string) =>
    http.post<ConnectionTestResult>(`/api/v1/connections/instances/${id}/test`).then(r => r.data),
  // The address of the MCP server's own sign-in page, to open in a new window. The server finishes
  // the sign-in on its callback; the page learns of it by reading the connection again.
  mcpSignIn: (id: string) =>
    http.post<{ authorizeUrl: string }>(`/api/v1/connections/instances/${id}/mcp-sign-in`).then(r => r.data.authorizeUrl),
}

// `enabled` defers the fetch, for a dialog that only needs the list for some source types.
export function useConnections(enabled = true) {
  return useQuery({
    queryKey: keys.connections(),
    queryFn: connectionsApi.list,
    enabled,
  })
}

// The service an `app://settings/connectors/<focus>` link names. A service's own name in lower case
// (jira, github) is the usual key; two older slugs from before connections existed are still in
// stored chat history - `microsoft`, the Microsoft 365 account panel's, and `smb`, the network
// share account panel's.
const SERVICE_KEY_ALIASES: Record<string, ConnectionProtocol> = {
  microsoft: 'MICROSOFT_365',
  smb: 'NETWORK_SHARE',
  networkshare: 'NETWORK_SHARE',
}

export function serviceKeyProtocol(key: string): ConnectionProtocol {
  return SERVICE_KEY_ALIASES[key] ?? key.toUpperCase().replace(/-/g, '_')
}

/**
 * Where a service's sign-in starts, for one connection - the generic OAuth2 flow every service that
 * signs in uses (OAuth2ConnectionService), unless the edition brings a service with a flow of its
 * own (EDITION_SIGN_INS - Microsoft 365 on Enterprise Server). A BROWSER NAVIGATION, never an XHR:
 * the endpoint answers with a redirect to the service's sign-in page, and a sign-in page fetched
 * with XMLHttpRequest is a page nobody can type a password into.
 */
export function connectionSignInUrl(connection: Pick<Connection, 'id' | 'protocol'>): string {
  const own = EDITION_SIGN_INS[connection.protocol]
  if (own) return own.startUrl(connection.id)
  return `${ENV.apiBaseUrl ?? ''}/api/v1/connections/instances/${encodeURIComponent(connection.id)}/oauth/start`
}

/** Leaves the SPA for the service's sign-in page. */
export function startConnectionSignIn(connection: Pick<Connection, 'id' | 'protocol'>): void {
  window.location.assign(connectionSignInUrl(connection))
}

/** Drops a connection's sign-in and keeps the connection, so its sources still point at it. */
export function signOutConnection(connection: Pick<Connection, 'id' | 'protocol'>): Promise<void> {
  const own = EDITION_SIGN_INS[connection.protocol]
  if (own) return own.signOut(connection.id)
  return http.delete<void>(`/api/v1/connections/instances/${connection.id}/oauth`).then(() => undefined)
}

/** The backend's refusal, written to be read verbatim - or null when there is none. */
export function connectionError(e: unknown): string | null {
  const detail = (e as { response?: { data?: { error?: string } } })?.response?.data?.error
  return typeof detail === 'string' && detail ? detail : null
}
