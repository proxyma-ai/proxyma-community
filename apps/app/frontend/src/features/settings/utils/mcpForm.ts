import type { McpAuth, McpInput, McpTransport, McpView } from '../services/connectionsApi.ts'

/**
 * An MCP server connection's form, apart from the component that draws it, so what is sent can be
 * tested without a DOM.
 *
 * <p>Header and environment VALUES never come back from the server - only their names. A row the
 * server named starts with an empty value and `stored` set, and is sent with the empty value, which
 * the server reads as "keep what is stored". A row removed from the list is left out, which removes
 * it. A row added here with a blank name is dropped.
 */
export interface NamedValue {
  name: string
  value: string
  /** The server holds a value for this name; an empty box keeps it. */
  stored: boolean
}

export interface McpFormState {
  transport: McpTransport
  url: string
  command: string
  /** One argument per line. */
  argsText: string
  auth: McpAuth
  headers: NamedValue[]
  env: NamedValue[]
  shared: boolean
  oauthClientId: string
  oauthClientSecret: string
  oauthScope: string
}

export function initialMcpForm(view: McpView | null | undefined, baseUrl: string | null | undefined): McpFormState {
  return {
    transport: view?.transport ?? 'STREAMABLE_HTTP',
    url: baseUrl ?? '',
    command: view?.command ?? '',
    argsText: (view?.args ?? []).join('\n'),
    auth: view?.auth ?? 'NONE',
    headers: (view?.headerNames ?? []).map(name => ({ name, value: '', stored: true })),
    env: (view?.envNames ?? []).map(name => ({ name, value: '', stored: true })),
    shared: view?.shared ?? false,
    // A client registered automatically is the server's business, not something to show as typed.
    oauthClientId: view && !view.oauthClientRegistered ? (view.oauthClientId ?? '') : '',
    oauthClientSecret: '',
    oauthScope: view?.oauthScope ?? '',
  }
}

/** The arguments, one per line. Each line is one argument, spaces and all; blank lines are dropped. */
export function argLines(text: string): string[] {
  return text.split(/\r?\n/).map(line => line.trim()).filter(line => line.length > 0)
}

/** Rows as a name-to-value map. A later row with the same name wins; a blank name is dropped. */
export function namedMap(rows: NamedValue[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const row of rows) {
    const name = row.name.trim()
    if (name) out[name] = row.value
  }
  return out
}

/** Whether the form names somewhere to connect - the server says what else is wrong. */
export function mcpFormReady(state: McpFormState): boolean {
  return state.transport === 'STDIO' ? state.command.trim().length > 0 : state.url.trim().length > 0
}

/**
 * What is sent. Only the fields the transport and the sign-in method use: a local command has no
 * address and no sign-in, a remote server no command or environment. `canShare` is whether this
 * person may share a server at all (a Super admin on Enterprise Server); otherwise sharing is not
 * sent, and the server keeps what it has.
 */
export function buildMcpInput(state: McpFormState, canShare: boolean): McpInput {
  const stdio = state.transport === 'STDIO'
  const input: McpInput = { transport: state.transport, auth: stdio ? 'NONE' : state.auth }
  if (stdio) {
    input.command = state.command.trim()
    input.args = argLines(state.argsText)
    input.env = namedMap(state.env)
  } else if (state.auth === 'HEADERS') {
    input.headers = namedMap(state.headers)
  } else if (state.auth === 'OAUTH') {
    // Empty strings rather than absent: the server reads an empty scope as "none" and keeps a
    // client it registered itself when the box is empty.
    input.oauthClientId = state.oauthClientId.trim()
    input.oauthScope = state.oauthScope.trim()
    if (state.oauthClientSecret.trim()) input.oauthClientSecret = state.oauthClientSecret.trim()
  }
  if (canShare) input.shared = state.shared
  return input
}
