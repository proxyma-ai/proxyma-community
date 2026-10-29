import type { Translate } from '@/i18n'

/**
 * What the Logs page calls the server's codes - categories, actions and outcomes - in words.
 *
 * <p>Each takes the translation function and calls it with a LITERAL, so the catalog checker sees
 * every string here (a code looked up in a table would reach `t()` as a variable, and be in
 * neither of its reports). Plain `.ts` with no JSX, so `node --test` reaches it.
 *
 * <p>An action this page does not know yet - a newer server, a code added without its label - is
 * still shown, as its code made readable, never as a blank.
 */

export type OutcomeTone = 'success' | 'danger' | 'warning' | 'neutral'

/** The Activity tab's categories, in the order its filter lists them. */
export const ACTIVITY_CATEGORIES = [
  'SIGN_IN', 'USERS', 'CONFIG', 'DATA', 'SOURCES', 'ACCESS', 'LIMITS', 'UPDATES', 'SYSTEM',
] as const

/** Categories only Enterprise Server records - left out of the filter elsewhere. */
export const SERVER_ONLY_CATEGORIES = new Set(['SIGN_IN', 'USERS', 'SYSTEM'])

export function categoryLabel(category: string, t: Translate): string {
  switch (category) {
    case 'TOOL': return t('Tool calls')
    case 'GUARDRAIL': return t('Guardrails')
    case 'DIAGNOSTICS': return t('Diagnostics')
    case 'SIGN_IN': return t('Sign-in')
    case 'USERS': return t('Users')
    case 'CONFIG': return t('Configuration')
    case 'DATA': return t('Data')
    case 'SOURCES': return t('Sources')
    case 'ACCESS': return t('Access')
    case 'LIMITS': return t('Limits')
    case 'UPDATES': return t('Updates')
    case 'SYSTEM': return t('System')
    default: return readable(category)
  }
}

export function outcomeLabel(outcome: string, t: Translate): string {
  switch (outcome) {
    case 'OK': return t('Succeeded')
    case 'FAILED': return t('Failed')
    case 'DENIED': return t('Refused')
    case 'ALLOWED': return t('Allowed')
    case 'REDACTED': return t('Redacted')
    case 'BLOCKED': return t('Blocked')
    case 'OBSERVED': return t('Observed only')
    default: return readable(outcome)
  }
}

/** The ink an outcome is drawn in. Allowed is a guardrail passing text, which is the quiet case. */
export function outcomeTone(outcome: string): OutcomeTone {
  switch (outcome) {
    case 'OK': return 'success'
    case 'ALLOWED': return 'neutral'
    case 'FAILED':
    case 'BLOCKED': return 'danger'
    case 'DENIED':
    case 'REDACTED':
    case 'OBSERVED': return 'warning'
    default: return 'neutral'
  }
}

export function actionLabel(action: string, t: Translate): string {
  switch (action) {
    // Tool calls and guardrails
    case 'tool.call': return t('Tool call')
    case 'guardrail.input': return t('Input check')
    case 'guardrail.output': return t('Output check')
    case 'guardrail.retrieval': return t('Retrieval check')
    case 'guardrail.classifier-failed': return t('Classifier unreachable')
    // Diagnostics
    case 'diagnostics.sent': return t('Diagnostics sent')
    case 'diagnostics.send-failed': return t('Send failed')
    case 'diagnostics.expired': return t('Unsent days deleted')
    case 'diagnostics.switched-on': return t('Switched on')
    case 'diagnostics.switched-off': return t('Switched off')
    case 'diagnostics.deleted': return t('Collected data deleted')
    // Configuration
    case 'setting.saved': return t('Setting changed')
    case 'setting.cleared': return t('Setting cleared')
    // Data
    case 'audit.cleared': return t('Log cleared')
    case 'audit.exported': return t('Log downloaded')
    case 'llm-calls.exported': return t('Call log downloaded')
    case 'llm-calls.cleared': return t('Call log cleared')
    case 'guardrail-window.reset': return t('Guardrail window reset')
    case 'conversation.deleted': return t('Conversation deleted')
    case 'memory.deleted': return t('Memory deleted')
    // Access
    case 'conversation.shared': return t('Conversation shared')
    // Limits
    case 'spending-limit.reached': return t('Spending limit reached')
    case 'provider.rate-limited': return t('Provider rate limit')
    case 'budget.user-exceeded': return t('User budget reached')
    case 'chat-quota.reached': return t('Chat quota reached')
    case 'quota.storage-exceeded': return t('Storage quota reached')
    // Sign-in
    case 'signin.succeeded': return t('Signed in')
    case 'signin.failed': return t('Sign-in failed')
    case 'signin.locked': return t('Account locked')
    case 'signout': return t('Signed out')
    case 'signup': return t('Account created')
    case 'password.reset-requested': return t('Password reset requested')
    case 'password.reset': return t('Password reset')
    // Users
    case 'user.created': return t('User created')
    case 'user.role-changed': return t('Role changed')
    case 'user.quota-changed': return t('Quota changed')
    case 'user.deleted': return t('User deleted')
    case 'user.deletion-requested': return t('Deletion requested')
    case 'user.deletion-canceled': return t('Deletion canceled')
    case 'user.data-deleted': return t('User data deleted')
    case 'password.reset-link-created': return t('Password reset link made')
    // Access
    case 'mcp-token.created': return t('Token created')
    case 'mcp-token.revoked': return t('Token revoked')
    case 'ingest-token.created': return t('Ingest token created')
    case 'ingest-token.revoked': return t('Ingest token revoked')
    // Sources
    case 'source.added': return t('Source added')
    case 'source.changed': return t('Source changed')
    case 'source.removed': return t('Source removed')
    case 'sync.finished': return t('Sync finished')
    case 'sync.failed': return t('Sync failed')
    case 'file.uploaded': return t('File uploaded')
    case 'file.removed': return t('File removed')
    case 'connection.added': return t('Connection added')
    case 'connection.changed': return t('Connection changed')
    case 'connection.removed': return t('Connection removed')
    case 'connection.signed-in': return t('Connection signed in')
    case 'connection.signed-out': return t('Connection signed out')
    case 'connection.test-failed': return t('Connection test failed')
    // Configuration
    case 'provider.added': return t('Provider added')
    case 'provider.changed': return t('Provider changed')
    case 'provider.removed': return t('Provider removed')
    case 'pricing.changed': return t('Rate changed')
    case 'pricing.removed': return t('Rate removed')
    case 'license.activated': return t('License activated')
    case 'license.deactivated': return t('License removed')
    case 'license.trial-started': return t('Trial started')
    case 'license.verification-failed': return t('License check failed')
    case 'license.restored': return t('License restored')
    case 'license.revoked': return t('License revoked')
    case 'config.exported': return t('Configuration exported')
    case 'config.imported': return t('Configuration imported')
    // Updates
    case 'update.found': return t('Update found')
    case 'update.downloaded': return t('Update downloaded')
    case 'update.applied': return t('Update installed')
    case 'update.failed': return t('Update failed')
    case 'update.cancelled': return t('Download canceled')
    case 'update-history.cleared': return t('Update history cleared')
    // System
    case 'maintenance.enabled': return t('Maintenance started')
    case 'maintenance.disabled': return t('Maintenance ended')
    case 'backup.created': return t('Backup created')
    case 'backup.restored': return t('Backup restored')
    case 'backup.deleted': return t('Backup deleted')
    case 'backup.stopped': return t('Backup stopped')
    case 'system.reset': return t('Reset')
    default: return readable(action)
  }
}

/**
 * Who an entry names, in words: a person by the name captured when it was written, else the system
 * or a guest. `_local` is the one person on Desktop and Home Server (SpendSubject.LOCAL_ID).
 */
export function actorText(kind: string, id: string, name: string | null, t: Translate): string {
  if (kind === 'SYSTEM') return t('Proxyma')
  if (kind === 'ANONYMOUS') return t('Guest')
  if (id === '_local') return t('You')
  return name || id
}

/** An entry's second line. A setting change is named as Settings names it, not by its key. */
export function summaryText(row: { action: string; target: string | null; summary: string | null }, t: Translate): string {
  if (row.action === 'setting.saved' || row.action === 'setting.cleared') return settingLabel(row.target, t)
  return row.summary ?? ''
}

/**
 * What a model call was for - the LLM call log's Service - in words, not the server's code. The same
 * names Administration > Usage & cost gives each service (its ROLE_LABEL), so one call is one name.
 */
export function serviceLabel(role: string, t: Translate): string {
  switch (role) {
    case 'CHAT': return t('Chat')
    case 'SUB_AGENT': return t('Deployed agents')
    case 'TITLE': return t('Conversation titles')
    case 'CONDENSATION': return t('Memory condensation')
    case 'QUERY_EXPANSION': return t('Query expansion')
    case 'RERANK': return t('Reranking')
    case 'EMBEDDING': return t('Indexing and search')
    case 'OCR': return t('Reading scans')
    case 'GRAPH': return t('Knowledge graph')
    case 'SPACE_FACT': return t('Home-screen fact')
    case 'WORKFLOW': return t('Workflow steps')
    case 'GUARDRAIL': return t('Guardrail checks')
    default: return readable(role)
  }
}

/**
 * A setting's name as Settings shows it - "Guardrails: Detect PII" for `guardrail.detect-pii` - from
 * the `prefix.field` a setting change is recorded under. A group this page does not know keeps its
 * own name, made readable.
 */
export function settingLabel(target: string | null, t: Translate): string {
  if (!target) return ''
  const dot = target.indexOf('.')
  const prefix = dot < 0 ? target : target.slice(0, dot)
  const field = dot < 0 ? '' : target.slice(dot + 1)
  const group = settingGroup(prefix, t)
  return field ? `${group}: ${words(field)}` : group
}

function settingGroup(prefix: string, t: Translate): string {
  switch (prefix) {
    case 'chat': return t('Agents')
    case 'sub-agent': return t('Agents (deployed agents)')
    case 'embedding': return t('Search (embedding)')
    case 'reranking': return t('Search (reranking)')
    case 'retrieval': return t('Search')
    case 'guardrail': return t('Guardrails')
    case 'retention': return t('Data Retention')
    case 'proxy': return t('Network')
    case 'updates': return t('Updates')
    case 'privacy': return t('Privacy')
    case 'cost': return t('Usage & Cost')
    case 'quota': return t('Usage & Cost')
    default: return words(prefix)
  }
}

/** A rule category or a code as words, with its acronyms kept: `pii` is "PII", `secret` is "Secret". */
export function words(code: string): string {
  const text = code.replace(/[._-]+/g, ' ').trim().split(/\s+/)
    .map(w => ACRONYMS[w.toLowerCase()] ?? w.toLowerCase()).join(' ')
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : code
}

const ACRONYMS: Record<string, string> = {
  pii: 'PII', llm: 'LLM', api: 'API', url: 'URL', mcp: 'MCP', ocr: 'OCR', id: 'ID', ssl: 'SSL', tls: 'TLS',
  ca: 'CA', jql: 'JQL', cql: 'CQL', ip: 'IP', aws: 'AWS', ms: 'ms', usd: 'USD', svn: 'SVN',
}

/**
 * What an entry was recorded with, ready for the field tree: a true or false as Yes or No, and a
 * code the server wrote in capitals (ALLOW, PROFESSIONAL) as a word. Everything else - names,
 * numbers, sentences, arguments - is left exactly as recorded.
 */
export function readableDetail(value: unknown, t: Translate): unknown {
  if (value === true) return t('Yes')
  if (value === false) return t('No')
  if (typeof value === 'string') return /^[A-Z][A-Z0-9_]{1,30}$/.test(value) ? words(value) : value
  if (Array.isArray(value)) return value.map(v => readableDetail(v, t))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([k, v]) => [k, readableDetail(v, t)]))
  }
  return value
}

/** A code made readable: `user.role-changed` is "User role changed". */
export function readable(code: string): string {
  const words = code.replace(/[._-]+/g, ' ').trim().toLowerCase()
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : code
}

/** A duration as a reader takes it in: milliseconds under a second, then seconds, then minutes. */
export function durationText(ms: number | null | undefined): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)} s`
  const minutes = Math.floor(ms / 60_000)
  const seconds = Math.round((ms % 60_000) / 1000)
  return seconds ? `${minutes} min ${seconds} s` : `${minutes} min`
}
