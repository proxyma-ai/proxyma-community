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
    case 'PENDING': return t('Waiting for approval')
    case 'NOT_MADE': return t('Not made')
    default: return readable(outcome)
  }
}

/** The ink an outcome is drawn in. Allowed is a guardrail passing text, which is the quiet case. */
export function outcomeTone(outcome: string): OutcomeTone {
  switch (outcome) {
    case 'OK': return 'success'
    case 'ALLOWED':
    case 'PENDING':
    case 'NOT_MADE': return 'neutral'
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

/** An entry's target as the dialog shows it: a setting by its Settings name, anything else as recorded. */
export function targetText(row: { action: string; target: string | null }, t: Translate): string {
  if (row.action === 'setting.saved' || row.action === 'setting.cleared') return settingLabel(row.target, t)
  return row.target ?? ''
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
 * A setting's name as Settings shows it - "Guardrails: Personal data" for `guardrail.detect-pii` -
 * from the `prefix.field` a setting change is recorded under. A group or field this page does not
 * know keeps its own name, made readable, rather than showing nothing.
 */
export function settingLabel(target: string | null, t: Translate): string {
  if (!target) return ''
  const dot = target.indexOf('.')
  const prefix = dot < 0 ? target : target.slice(0, dot)
  const field = dot < 0 ? '' : target.slice(dot + 1)
  const group = settingGroup(prefix, t)
  return field ? `${group}: ${settingFieldLabel(target, t) ?? words(field)}` : group
}

/** The Settings tab (or Administration section) a settings prefix belongs to. */
export function settingGroup(prefix: string, t: Translate): string {
  switch (prefix) {
    case 'chat': return t('Agents')
    case 'sub-agent': return t('Agents (deployed agents)')
    case 'embedding': return t('Search (embedding)')
    case 'query-expansion': return t('Search')
    case 'reranking': return t('Search (reranking)')
    case 'retrieval': return t('Search')
    case 'graph': return t('Knowledge graph')
    case 'web-search': return t('Web search')
    case 'guardrail': return t('Guardrails')
    case 'ocr': return t('Documents')
    case 'doc-conversion': return t('Documents')
    case 'others': return t('Documents')
    case 'retention': return t('Data Retention')
    case 'storage': return t('Storage')
    case 'proxy': return t('Network')
    case 'network-share': return t('Network share')
    case 'updates': return t('Updates')
    case 'privacy': return t('Privacy')
    case 'cost': return t('Usage & Cost')
    case 'quota': return t('Usage & Cost')
    case 'cost-centers': return t('Usage & Cost')
    case 'space-fact': return t('Home-screen fact')
    case 'evaluation': return t('Evaluation data')
    case 'backup-schedule': return t('Backup schedule')
    case 'maintenance-announcement': return t('Maintenance announcement')
    case 'connector-tools': return t('Connectors')
    // Product names, the same in every language.
    case 'jira': return 'Jira'
    case 'confluence': return 'Confluence'
    case 'bitbucket': return 'Bitbucket'
    case 'svn': return 'SVN'
    case 'sharepoint': return 'SharePoint'
    case 'teams': return 'Microsoft Teams'
    case 'm365': return 'Microsoft 365'
    default: return words(prefix)
  }
}

/**
 * A setting's field, in the words the Settings tab that changes it uses - so one setting has one
 * name, and its translation is the one that tab already has. Null for a field this page has no name
 * for, which settingLabel makes readable instead. A field no screen shows any more (an older build
 * wrote it, or only an import can) has a plain name of its own.
 *
 * `settingKeys.test.ts` reads every settings DTO the backend declares and fails while one of their
 * fields is missing here.
 */
export function settingFieldLabel(key: string, t: Translate): string | null {
  switch (key) {
    // Agents
    case 'chat.provider': return t('Agent provider')
    case 'chat.model': return t('Agent model')
    case 'chat.tone': return t('Response tone')
    case 'chat.max-output-tokens': return t('Max output tokens')
    case 'chat.max-iterations': return t('Max agent iterations')
    case 'chat.memory-max-tokens': return t('Context window')
    case 'chat.prompt-cache': return t('Prompt cache')
    case 'chat.search-timeout-minutes': return t('Search time limit (minutes)')
    case 'sub-agent.max-iterations': return t('Max deployed agent iterations')
    case 'sub-agent.max-output-tokens': return t('Max output tokens')
    case 'sub-agent.context-max-tokens': return t('Context window')
    case 'sub-agent.timeout-minutes': return t('Time limit (minutes)')
    case 'sub-agent.prompt-cache': return t('Prompt cache')
    case 'sub-agent.max-depth': return t('Max depth')
    case 'sub-agent.peer-messaging': return t('Agents may message their siblings')
    case 'sub-agent.model-rule': return t('Models agents may choose')
    case 'sub-agent.model-list': return t('Models agents may choose')
    // Search
    case 'embedding.provider': return t('Embedding provider')
    case 'embedding.model': return t('Embedding model')
    case 'embedding.max-concurrent-requests': return t('Max concurrent embedding requests')
    case 'query-expansion.enabled': return t('Query expansion')
    case 'query-expansion.provider': return t('Query expansion provider')
    case 'query-expansion.model': return t('Query expansion model')
    case 'query-expansion.variants': return t('Variants')
    case 'reranking.enabled': return t('Reranking')
    case 'reranking.provider': return t('Reranking provider')
    case 'reranking.model': return t('Reranking model')
    case 'graph.enabled': return t('Knowledge graph')
    case 'graph.provider': return t('Extraction provider')
    case 'graph.model': return t('Extraction model')
    case 'graph.gleanings': return t('Gleaning rounds')
    case 'graph.max-output-tokens': return t('Max output tokens')
    case 'graph.call-timeout-seconds': return t('Extraction timeout')
    case 'graph.search-channel': return t('Knowledge graph in search')
    case 'web-search.api-key': return t('API key')
    case 'web-search.search-engine-id': return t('Search engine ID')
    // An older build's per-role endpoint, for every model role alike.
    case 'chat.base-url':
    case 'embedding.base-url':
    case 'query-expansion.base-url':
    case 'reranking.base-url':
    case 'graph.base-url':
    case 'guardrail.base-url': return t('Base URL')
    case 'chat.api-key':
    case 'embedding.api-key':
    case 'query-expansion.api-key':
    case 'reranking.api-key':
    case 'graph.api-key':
    case 'guardrail.api-key': return t('API key')
    // Guardrails
    case 'guardrail.mode': return t('Mode')
    case 'guardrail.input-enabled': return t('Check what people send')
    case 'guardrail.output-enabled': return t('Check what the agent answers')
    case 'guardrail.on-input-violation': return t('When a message matches')
    case 'guardrail.on-output-violation': return t('When an answer matches')
    case 'guardrail.retrieval-enabled': return t('Check what the agent reads')
    case 'guardrail.on-retrieval-violation': return t('When something the agent read matches')
    case 'guardrail.detect-secrets': return t('Credentials')
    case 'guardrail.detect-pii': return t('Personal data')
    case 'guardrail.detect-prompt-injection': return t('Prompt injection')
    case 'guardrail.detect-path-leak': return t('Server file paths')
    case 'guardrail.deny-terms': return t('Blocked terms')
    case 'guardrail.examples-enabled': return t('Check against examples')
    case 'guardrail.examples-threshold': return t('Example match threshold')
    case 'guardrail.llm-enabled': return t('Judge meaning with a model')
    case 'guardrail.provider': return t('Guardrail provider')
    case 'guardrail.model': return t('Guardrail model')
    case 'guardrail.max-output-tokens': return t('Max output tokens')
    case 'guardrail.fail-closed': return t('Refuse when the model cannot be reached')
    case 'guardrail.capture-matched-text': return t('Keep the matched text')
    case 'guardrail.canary-enabled': return t('Detect a leaked system prompt')
    case 'guardrail.canary-token': return t('System prompt marker')
    case 'guardrail.repeat-threshold': return t('Slow down repeated attempts')
    case 'guardrail.repeat-window-minutes': return t('Repeat window (minutes)')
    case 'guardrail.repeat-cooldown-minutes': return t('Pause after repeats (minutes)')
    case 'guardrail.llm-categories': return t('Categories to check')
    case 'guardrail.semantic-enabled': return t('Rules from your documents')
    case 'guardrail.semantic-on-output': return t('Also check answers')
    case 'guardrail.semantic-top-k': return t('Rules to compare')
    case 'guardrail.semantic-min-score': return t('Minimum rule relevance')
    // Documents
    case 'ocr.engine': return t('OCR engine')
    case 'ocr.provider': return t('OCR provider')
    case 'ocr.model': return t('OCR model')
    case 'ocr.languages': return t('Tesseract languages')
    case 'ocr.dpi': return t('Page resolution (DPI)')
    case 'ocr.max-pages': return t('Max pages per document')
    case 'doc-conversion.indexing-method': return t('Method for indexing')
    case 'doc-conversion.attachment-method': return t('Method for chat attachments')
    case 'others.save-markdown': return t('Keep a Markdown copy of each document')
    // Sources
    case 'jira.sync-interval-seconds':
    case 'confluence.sync-interval-seconds':
    case 'bitbucket.sync-interval-seconds':
    case 'svn.sync-interval-seconds':
    case 'network-share.sync-interval-seconds':
    case 'sharepoint.sync-interval-seconds':
    case 'teams.sync-interval-seconds': return t('Sync interval (seconds)')
    case 'jira.skip-custom-fields': return t('Skipped custom fields')
    case 'svn.indexed-extensions': return t('Indexed file extensions')
    case 'network-share.allowed-hosts': return t('Allowed file servers')
    case 'connector-tools.connector-management': return t('Add and remove sources')
    // Network
    case 'proxy.mode': return t('Proxy mode')
    case 'proxy.http-proxy': return t('HTTP proxy')
    case 'proxy.https-proxy': return t('HTTPS proxy')
    case 'proxy.no-proxy': return t('No proxy')
    case 'proxy.ca-certificate': return t('Custom CA certificate')
    // Data Retention and storage
    case 'retention.conversation-days': return t('Conversations (days)')
    case 'retention.evaluation-days': return t('Evaluation data (days)')
    case 'retention.usage-days': return t('Usage records (days)')
    case 'retention.audit-days': return t('Logs (days)')
    case 'retention.feedback-days': return t('Feedback (days)')
    case 'storage.default-user-quota-bytes': return t('Default storage quota')
    case 'storage.overhead-multiplier': return t('Storage overhead multiplier')
    // Usage & Cost
    case 'quota.global-monthly-quota-usd': return t('Monthly limit ($)')
    case 'quota.default-monthly-quota-usd': return t('Default monthly quota ($)')
    case 'quota.quota-reset-day': return t('Reset day')
    case 'quota.default-5h-quota-usd': return t('Default 5-hour quota ($)')
    case 'quota.default-weekly-quota-usd': return t('Default weekly quota ($)')
    // Who pays for each kind of model call, named as the services themselves are.
    case 'cost-centers.chat': return serviceLabel('CHAT', t)
    case 'cost-centers.sub-agent': return serviceLabel('SUB_AGENT', t)
    case 'cost-centers.title': return serviceLabel('TITLE', t)
    case 'cost-centers.condensation': return serviceLabel('CONDENSATION', t)
    case 'cost-centers.query-expansion': return serviceLabel('QUERY_EXPANSION', t)
    case 'cost-centers.rerank': return serviceLabel('RERANK', t)
    case 'cost-centers.embedding': return serviceLabel('EMBEDDING', t)
    case 'cost-centers.ocr': return serviceLabel('OCR', t)
    case 'cost-centers.graph': return serviceLabel('GRAPH', t)
    case 'cost-centers.space-fact': return serviceLabel('SPACE_FACT', t)
    case 'cost-centers.workflow': return serviceLabel('WORKFLOW', t)
    case 'cost-centers.guardrail': return serviceLabel('GUARDRAIL', t)
    // Updates, privacy, the home screen, evaluation
    case 'updates.check-frequency-hours': return t('Check for updates')
    case 'updates.auto-update': return t('Download updates automatically')
    case 'updates.dev-builds': return t('Development builds')
    case 'privacy.data-collection-allowed': return t('Send diagnostics to Proxyma')
    case 'space-fact.fact': return t('Home-screen fact')
    case 'space-fact.fact-vi': return t('Home-screen fact in Vietnamese')
    case 'space-fact.source-title': return t('Fact source title')
    case 'space-fact.source-url': return t('Fact source link')
    case 'space-fact.generated-at': return t('Fact written on')
    case 'evaluation.enabled': return t('Record evaluation data')
    // Administration
    case 'backup-schedule.frequency': return t('Frequency')
    case 'backup-schedule.day-of-week': return t('Day of week')
    case 'backup-schedule.day-of-month': return t('Day of month')
    case 'backup-schedule.hour': return t('Hour')
    case 'backup-schedule.minute': return t('Minute')
    case 'backup-schedule.growth-threshold-bytes': return t('Back up on growth')
    case 'maintenance-announcement.banner-enabled': return t('Show the announcement banner')
    case 'maintenance-announcement.reason': return t('Reason')
    case 'maintenance-announcement.scheduled-start': return t('Scheduled start')
    case 'maintenance-announcement.expected-back-at': return t('Expected back')
    case 'm365.tenant-id': return t('Directory (tenant) ID')
    case 'm365.client-id': return t('Application (client) ID')
    case 'm365.client-secret': return t('Client secret')
    case 'm365.redirect-uri': return t('Redirect URI')
    case 'm365.scopes': return t('Scopes')
    case 'm365.authority-host': return t('Authority host')
    case 'm365.teams-enabled': return t('Teams channels')
    case 'm365.chats-enabled': return t('Teams chats')
    case 'm365.outlook-enabled': return t('Outlook mail and calendars')
    case 'm365.calendar-days-back': return t('Calendar days back')
    case 'm365.calendar-days-ahead': return t('Calendar days ahead')
    case 'm365.outlook-sync-interval-seconds': return t('Outlook sync interval (seconds)')
    default: return null
  }
}

/**
 * A choice a setting was changed to, as that setting's own control words it - `BLOCK` is "Block the
 * turn" for a message but "Withhold the whole source" for what the agent read. Null for a value that
 * is not one of the codes below: a number, a name, an address, written as it was recorded.
 */
export function settingValueLabel(key: string, value: string, t: Translate): string | null {
  if (key === 'guardrail.on-input-violation' || key === 'guardrail.on-output-violation'
    || key === 'guardrail.on-retrieval-violation') {
    if (value === 'REDACT') return t('Remove and continue')
    if (value === 'BLOCK') {
      return key === 'guardrail.on-retrieval-violation' ? t('Withhold the whole source') : t('Block the turn')
    }
  }
  if (key === 'doc-conversion.indexing-method' || key === 'doc-conversion.attachment-method') {
    if (value === 'AUTO') return t('Auto (extract text, OCR if scanned)')
  }
  return codeLabel(value, t)
}

/**
 * A code the server records in an entry's detail (ALLOW, SUPER_ADMIN, GOOGLE_DRIVE), as the screen
 * that offers it words it. Null for a code this page does not know - readableDetail then makes it
 * readable, as before.
 */
export function codeLabel(code: string, t: Translate): string | null {
  switch (code) {
    // A guardrail's verdict and the stage it checked
    case 'ALLOW': return t('Allowed')
    case 'REDACT': return t('Redacted')
    case 'BLOCK': return t('Blocked')
    case 'INPUT': return t('Input')
    case 'OUTPUT': return t('Output')
    case 'RETRIEVAL': return t('Retrieval')
    // Settings choices
    case 'ENFORCE': return t('Enforce')
    case 'MONITOR': return t('Monitor only')
    case 'ANY': return t('Any listed model')
    case 'ONLY': return t('Only these')
    case 'EXCEPT': return t('All but these')
    case 'CONCISE': return t('Concise')
    case 'NORMAL': return t('Normal')
    case 'CREATIVE': return t('Creative')
    case 'AUTO': return t('Auto')
    case 'FIVE_MINUTES': return t('5 minutes')
    case 'ONE_HOUR': return t('1 hour')
    case 'OCR': return t('OCR (always)')
    case 'VISION': return t('Vision model')
    case 'DAILY': return t('Every day')
    case 'WEEKLY': return t('Every week')
    case 'MONTHLY': return t('Every month')
    case 'MONDAY': return t('Monday')
    case 'TUESDAY': return t('Tuesday')
    case 'WEDNESDAY': return t('Wednesday')
    case 'THURSDAY': return t('Thursday')
    case 'FRIDAY': return t('Friday')
    case 'SATURDAY': return t('Saturday')
    case 'SUNDAY': return t('Sunday')
    // Roles
    case 'SUPER_ADMIN': return t('Super admin')
    case 'ADMIN': return t('Admin')
    case 'VIEWER': return t('Viewer')
    case 'USER': return t('User')
    // A license check's answer, and why a license lapsed
    case 'VALID': return t('Valid')
    case 'INVALID': return t('Not valid')
    case 'EXPIRED': return t('Expired')
    case 'REVOKED': return t('Revoked')
    case 'UNREACHABLE': return t('Could not be reached')
    case 'NONE': return t('None')
    // Source types that are not a product's name
    case 'LOCAL_FOLDER': return t('Local folder')
    case 'UPLOADED_FILES': return t('Uploaded files')
    case 'FILES': return t('Files')
    case 'NETWORK_SHARE': return t('Network share')
    case 'WEB_PAGE': return t('Web pages')
    case 'CUSTOM': return t('Custom source')
    case 'YOUTUBE_CHANNEL': return t('YouTube channel')
    case 'IMAP': return t('Email (IMAP)')
    case 'TEAMS_CHAT': return t('Teams chats')
    default: return productName(code)
  }
}

/**
 * Codes that are a product's or a protocol's name, written as its maker writes it in every language.
 * Kept out of the translation catalog, where an entry would only repeat the English.
 */
function productName(code: string): string | null {
  switch (code) {
    case 'JIRA': return 'Jira'
    case 'CONFLUENCE': return 'Confluence'
    case 'BITBUCKET': return 'Bitbucket'
    case 'BITBUCKET_CLOUD': return 'Bitbucket Cloud'
    case 'SVN': return 'SVN'
    case 'SHAREPOINT': return 'SharePoint'
    case 'TEAMS': return 'Microsoft Teams'
    case 'OUTLOOK': return 'Outlook'
    case 'ONEDRIVE': return 'OneDrive'
    case 'GITHUB': return 'GitHub'
    case 'GITLAB': return 'GitLab'
    case 'SLACK': return 'Slack'
    case 'LINEAR': return 'Linear'
    case 'GOOGLE_DRIVE': return 'Google Drive'
    case 'GMAIL': return 'Gmail'
    case 'GOOGLE_CALENDAR': return 'Google Calendar'
    case 'NOTION': return 'Notion'
    case 'AIRTABLE': return 'Airtable'
    case 'ZENDESK': return 'Zendesk'
    case 'FRESHDESK': return 'Freshdesk'
    case 'HUBSPOT': return 'HubSpot'
    case 'SALESFORCE': return 'Salesforce'
    case 'ASANA': return 'Asana'
    case 'MONDAY': return 'monday.com'
    case 'TRELLO': return 'Trello'
    case 'CLICKUP': return 'ClickUp'
    case 'DROPBOX': return 'Dropbox'
    case 'BOX': return 'Box'
    case 'ZOOM': return 'Zoom'
    case 'OPENAI': return 'OpenAI'
    case 'ANTHROPIC': return 'Anthropic'
    case 'GEMINI': return 'Google Gemini'
    case 'AZURE_OPENAI': return 'Azure OpenAI'
    case 'OLLAMA': return 'Ollama'
    case 'OPENROUTER': return 'OpenRouter'
    case 'LITELLM': return 'LiteLLM'
    case 'OPENAI_COMPATIBLE': return 'OpenAI-compatible'
    case 'ANTHROPIC_MESSAGES': return 'Anthropic Messages'
    case 'OPENAI_CHAT': return 'OpenAI Chat Completions'
    case 'MARKITDOWN': return 'Markitdown'
    case 'PDFPLUMBER': return 'pdfplumber'
    case 'TESSERACT': return 'Tesseract'
    case 'COMMUNITY': return 'Community'
    case 'ENTERPRISE': return 'Enterprise'
    case 'PERSONAL': return 'Personal'
    case 'PROFESSIONAL': return 'Professional'
    default: return null
  }
}

/**
 * What a guardrail fired on - the CATEGORY the server records as an entry's target and sends with a
 * chat's guardrail notes (GuardrailRules: secret, pii, prompt-injection, server-path, deny-list,
 * system-prompt-leak; GuardrailService: repeated-attempts, guardrail-unavailable) - in the words
 * Settings > Guardrails uses for it, sentence case, as a label or a badge. The one mapping: the Logs
 * page and the chat both read it.
 *
 * Anything else is not a code of ours but the operator's own words - a classifier category or a
 * policy document's name. One shaped like a code (`self-harm`, `new-kind`) is made readable; the
 * rest is shown as the operator wrote it. Never dropped.
 */
export function guardrailCategoryLabel(category: string, t: Translate): string {
  switch (category) {
    case 'secret': return t('Credentials')
    case 'pii': return t('Personal data')
    case 'prompt-injection': return t('Prompt injection')
    case 'server-path': return t('Server file paths')
    case 'deny-list': return t('Blocked terms')
    case 'system-prompt-leak': return t('Leaked system prompt')
    case 'repeated-attempts': return t('Repeated attempts')
    case 'guardrail-unavailable': return t('Guardrail unavailable')
    default: return CATEGORY_CODE.test(category) ? words(category) : category
  }
}

/** Every category the server itself names - each has words in guardrailCategoryLabel. */
export const GUARDRAIL_CATEGORIES = [
  'secret', 'pii', 'prompt-injection', 'server-path', 'deny-list', 'system-prompt-leak',
  'repeated-attempts', 'guardrail-unavailable',
] as const

// A lowercase code: words joined by hyphens, dots or underscores, as the rules name a category.
const CATEGORY_CODE = /^[a-z0-9]+(?:[._-][a-z0-9]+)*$/

/**
 * A category inside a sentence - "Redacted by a guardrail: credentials, server file paths": the
 * label with its first letter lowered, unless it opens with an acronym ("PII ...") or is the
 * operator's own words, which stay exactly as written.
 */
export function guardrailCategoryInSentence(category: string, t: Translate): string {
  const label = guardrailCategoryLabel(category, t)
  if (label === category) return label
  const first = label.split(/\s/, 1)[0]
  if (first.length > 1 && first === first.toUpperCase()) return label
  return label.charAt(0).toLocaleLowerCase() + label.slice(1)
}

/** Several categories inside a sentence, as one list. */
export function guardrailReasons(categories: readonly string[], t: Translate): string {
  return categories.map(c => guardrailCategoryInSentence(c, t)).join(', ')
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
 * code the server wrote in capitals (ALLOW, PROFESSIONAL) in the words the screen offering it uses
 * (codeLabel), else made readable. A setting change's group and field are named as settingLabel
 * names them, and the value it was set to as that setting's own control words it. Everything else -
 * names, numbers, sentences, arguments - is left exactly as recorded.
 */
export function readableDetail(value: unknown, t: Translate, key?: string): unknown {
  if (value === true) return t('Yes')
  if (value === false) return t('No')
  if (typeof value === 'string') {
    if (!CODE.test(value)) return value
    // A source's type: MONDAY is monday.com there, never the day.
    const known = key === 'type' ? productName(value) ?? codeLabel(value, t) : codeLabel(value, t)
    return known ?? words(value)
  }
  if (Array.isArray(value)) return value.map(v => readableDetail(v, t, key))
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>
    const setting = typeof record.group === 'string' && typeof record.field === 'string'
      ? `${record.group}.${record.field}` : null
    return Object.fromEntries(Object.entries(record).map(([k, v]) => {
      if (setting && k === 'group') return [k, settingGroup(record.group as string, t)]
      if (setting && k === 'field') return [k, settingFieldLabel(setting, t) ?? words(record.field as string)]
      if (setting && k === 'value' && typeof v === 'string' && CODE.test(v)) {
        return [k, settingValueLabel(setting, v, t) ?? words(v)]
      }
      return [k, readableDetail(v, t, k)]
    }))
  }
  return value
}

const CODE = /^[A-Z][A-Z0-9_]{1,30}$/

/** A code made readable: `user.role-changed` is "User role changed". */
export function readable(code: string): string {
  const words = code.replace(/[._-]+/g, ' ').trim().toLowerCase()
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : code
}

/**
 * A duration as a reader takes it in: milliseconds under a second, then seconds, then minutes.
 * "ms" and "s" are the same in every language; the minutes are a word, so they go through `t` -
 * optional, so a caller without one still gets English.
 */
export function durationText(ms: number | null | undefined, t: Translate = english): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)} s`
  const minutes = Math.floor(ms / 60_000)
  const seconds = Math.round((ms % 60_000) / 1000)
  return seconds
    ? t('{minutes} min {seconds} s', { minutes, seconds })
    : t('{minutes} min', { minutes })
}

/** The English itself, with its values filled in - for a caller with no translation function. */
const english: Translate = (text, params) => text.replace(/\{(\w+)\}/g, (_, k: string) => String(params?.[k] ?? ''))
