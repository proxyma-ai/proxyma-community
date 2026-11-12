import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  GUARDRAIL_CATEGORIES, actionLabel, actorText, categoryLabel, durationText, guardrailCategoryInSentence,
  guardrailCategoryLabel, guardrailReasons, outcomeLabel, outcomeTone, readable, readableDetail, serviceLabel,
  settingLabel, targetText, words,
} from './auditLabels.ts'
import { vi } from '../../../i18n/catalogs/vi.ts'

const t = (s: string) => s

test('a known action reads as its label', () => {
  assert.equal(actionLabel('tool.call', t), 'Tool call')
  assert.equal(actionLabel('diagnostics.sent', t), 'Diagnostics sent')
})

test('an action the page does not know is still shown, made readable', () => {
  assert.equal(actionLabel('widget.frame-rotated', t), 'Widget frame rotated')
  assert.equal(readable('signin.failed'), 'Signin failed')
  assert.equal(readable(''), '')
})

test('every outcome has a tone, and an unknown one is neutral rather than alarming', () => {
  assert.equal(outcomeTone('OK'), 'success')
  assert.equal(outcomeTone('FAILED'), 'danger')
  assert.equal(outcomeTone('BLOCKED'), 'danger')
  assert.equal(outcomeTone('DENIED'), 'warning')
  assert.equal(outcomeTone('ALLOWED'), 'neutral')
  assert.equal(outcomeTone('SOMETHING_NEW'), 'neutral')
  assert.equal(outcomeLabel('DENIED', t), 'Refused')
  assert.equal(outcomeLabel('SOMETHING_NEW', t), 'Something new')
})

test('categories have names, and an unknown one falls back to its code', () => {
  assert.equal(categoryLabel('SIGN_IN', t), 'Sign-in')
  assert.equal(categoryLabel('NEW_ONE', t), 'New one')
})

test('a duration reads in the unit that suits it', () => {
  assert.equal(durationText(null), '')
  assert.equal(durationText(0), '0 ms')
  assert.equal(durationText(999), '999 ms')
  assert.equal(durationText(1500), '1.5 s')
  assert.equal(durationText(42_000), '42 s')
  assert.equal(durationText(60_000), '1 min')
  assert.equal(durationText(125_000), '2 min 5 s')
})

test('the minutes in a duration are a word, and go through the translation', () => {
  const vi = (s: string, p?: Record<string, string | number>) =>
    ({ '{minutes} min': '{minutes} phut', '{minutes} min {seconds} s': '{minutes} phut {seconds} giay' } as Record<string, string>)[s]
      .replace(/\{(\w+)\}/g, (_, k: string) => String(p?.[k]))
  assert.equal(durationText(60_000, vi), '1 phut')
  assert.equal(durationText(125_000, vi), '2 phut 5 giay')
  assert.equal(durationText(42_000, vi), '42 s')
})

test('what a guardrail check fired on reads as Settings names it, and an unknown category made readable', () => {
  assert.equal(guardrailCategoryLabel('secret', t), 'Credentials')
  assert.equal(guardrailCategoryLabel('pii', t), 'Personal data')
  assert.equal(guardrailCategoryLabel('prompt-injection', t), 'Prompt injection')
  assert.equal(guardrailCategoryLabel('server-path', t), 'Server file paths')
  assert.equal(guardrailCategoryLabel('deny-list', t), 'Blocked terms')
  assert.equal(guardrailCategoryLabel('system-prompt-leak', t), 'Leaked system prompt')
  assert.equal(guardrailCategoryLabel('repeated-attempts', t), 'Repeated attempts')
  assert.equal(guardrailCategoryLabel('guardrail-unavailable', t), 'Guardrail unavailable')
  assert.equal(guardrailCategoryLabel('new-kind', t), 'New kind')
})

test('every category the server names has words in English and in Vietnamese', () => {
  for (const category of GUARDRAIL_CATEGORIES) {
    // Through a translation that marks what it was asked, so a label not written as a literal t()
    // call (a code made readable) shows up as unmarked.
    const label = guardrailCategoryLabel(category, s => `<${s}>`)
    assert.match(label, /^<.+>$/, `${category} has no words of its own`)
    assert.ok(vi[label.slice(1, -1)], `${category}: "${label}" has no Vietnamese`)
  }
})

test("an unknown category still shows: a code made readable, the operator's own words as written", () => {
  assert.equal(guardrailCategoryLabel('self-harm', t), 'Self harm')
  assert.equal(guardrailCategoryLabel('brand_new.kind', t), 'Brand new kind')
  assert.equal(guardrailCategoryLabel('sexual content involving minors', t), 'sexual content involving minors')
  assert.equal(guardrailCategoryLabel('GDPR Policy', t), 'GDPR Policy')
  assert.equal(guardrailCategoryLabel('', t), '')
})

test("a category inside a sentence starts lowercase, an acronym and the operator's words apart", () => {
  assert.equal(guardrailCategoryInSentence('secret', t), 'credentials')
  assert.equal(guardrailCategoryInSentence('server-path', t), 'server file paths')
  assert.equal(guardrailCategoryInSentence('self-harm', t), 'self harm')
  assert.equal(guardrailCategoryInSentence('pii-extra', t), 'PII extra')
  assert.equal(guardrailCategoryInSentence('Data Handling Policy', t), 'Data Handling Policy')
  const tv = (s: string) => vi[s] ?? s
  assert.equal(guardrailCategoryInSentence('server-path', tv), vi['Server file paths'].charAt(0).toLocaleLowerCase() + vi['Server file paths'].slice(1))
  assert.equal(guardrailReasons(['prompt-injection', 'deny-list'], t), 'prompt injection, blocked terms')
  assert.equal(guardrailReasons([], t), '')
})

test('who: a person by name, the local user as you, the system as Proxyma', () => {
  assert.equal(actorText('USER', 'u-1', 'Ana', t), 'Ana')
  assert.equal(actorText('USER', 'u-1', null, t), 'u-1')
  assert.equal(actorText('USER', '_local', null, t), 'You')
  assert.equal(actorText('SYSTEM', 'system', null, t), 'Proxyma')
  assert.equal(actorText('ANONYMOUS', 'anonymous', null, t), 'Guest')
})

test('a setting reads as Settings names it, its group first', () => {
  assert.equal(settingLabel('guardrail.detect-pii', t), 'Guardrails: Personal data')
  assert.equal(settingLabel('retention.audit-days', t), 'Data Retention: Logs (days)')
  assert.equal(settingLabel('cost-centers.rerank', t), 'Usage & Cost: Reranking')
  assert.equal(settingLabel('jira.sync-interval-seconds', t), 'Jira: Sync interval (seconds)')
  assert.equal(settingLabel('something-new.max-items', t), 'Something new: Max items')
  assert.equal(settingLabel('guardrail.brand-new', t), 'Guardrails: Brand new')
  assert.equal(settingLabel(null, t), '')
})

test('a setting\'s name goes through the translation, its group and its field alike', () => {
  const vi = (s: string) => `<${s}>`
  assert.equal(settingLabel('guardrail.detect-pii', vi), '<Guardrails>: <Personal data>')
})

test('services and rule categories read as words, acronyms kept', () => {
  assert.equal(serviceLabel('EMBEDDING', t), 'Indexing and search')
  assert.equal(serviceLabel('BRAND_NEW', t), 'Brand new')
  assert.equal(words('pii'), 'PII')
  assert.equal(words('secret'), 'Secret')
  assert.equal(words('PROFESSIONAL'), 'Professional')
})

test('recorded detail: yes and no for flags, named capital codes, everything else untouched', () => {
  assert.deepEqual(readableDetail({ enforced: true, verdict: 'ALLOW', stage: 'output', n: 3, q: 'AKIA key', list: [false, 'BLOCK'] }, t),
    { enforced: 'Yes', verdict: 'Allowed', stage: 'output', n: 3, q: 'AKIA key', list: ['No', 'Blocked'] })
  assert.deepEqual(readableDetail({ stage: 'RETRIEVAL', role: 'SUPER_ADMIN', status: 'REVOKED', protocol: 'OPENAI_COMPATIBLE' }, t),
    { stage: 'Retrieval', role: 'Super admin', status: 'Revoked', protocol: 'OpenAI-compatible' })
  assert.equal(readableDetail('SOMETHING_NEW', t), 'Something new')
})

test('every code a detail can carry is a whole phrase through the translation, or a product\'s own name', () => {
  const vi = (s: string) => `<${s}>`
  for (const code of ['ALLOW', 'REDACT', 'BLOCK', 'INPUT', 'OUTPUT', 'RETRIEVAL', 'ENFORCE', 'MONITOR', 'ANY', 'ONLY',
    'EXCEPT', 'DAILY', 'MONDAY', 'SUNDAY', 'SUPER_ADMIN', 'ADMIN', 'VIEWER', 'VALID', 'INVALID', 'EXPIRED', 'REVOKED',
    'UNREACHABLE', 'LOCAL_FOLDER', 'UPLOADED_FILES', 'NETWORK_SHARE', 'WEB_PAGE', 'CUSTOM', 'TEAMS_CHAT']) {
    assert.match(String(readableDetail(code, vi)), /^<.+>$/, code)
  }
  assert.equal(readableDetail('GOOGLE_DRIVE', vi), 'Google Drive')
  assert.equal(readableDetail('ANTHROPIC', vi), 'Anthropic')
})

test('a source of type MONDAY is monday.com, and the day is a day', () => {
  assert.deepEqual(readableDetail({ type: 'MONDAY', day: 'MONDAY' }, t), { type: 'monday.com', day: 'Monday' })
})

test('a setting change names its group, its field, and the choice in that setting\'s own words', () => {
  assert.deepEqual(readableDetail({ group: 'guardrail', field: 'on-retrieval-violation', value: 'BLOCK' }, t),
    { group: 'Guardrails', field: 'When something the agent read matches', value: 'Withhold the whole source' })
  assert.deepEqual(readableDetail({ group: 'guardrail', field: 'on-input-violation', value: 'BLOCK' }, t),
    { group: 'Guardrails', field: 'When a message matches', value: 'Block the turn' })
  assert.deepEqual(readableDetail({ group: 'doc-conversion', field: 'indexing-method', value: 'AUTO' }, t),
    { group: 'Documents', field: 'Method for indexing', value: 'Auto (extract text, OCR if scanned)' })
  assert.deepEqual(readableDetail({ group: 'chat', field: 'prompt-cache', value: 'AUTO' }, t),
    { group: 'Agents', field: 'Prompt cache', value: 'Auto' })
  assert.deepEqual(readableDetail({ group: 'chat', field: 'model', value: 'gpt-4o' }, t),
    { group: 'Agents', field: 'Agent model', value: 'gpt-4o' })
})

test('a setting change\'s target is the setting\'s name, any other target as recorded', () => {
  assert.equal(targetText({ action: 'setting.saved', target: 'proxy.no-proxy' }, t), 'Network: No proxy')
  assert.equal(targetText({ action: 'source.added', target: 'Release notes' }, t), 'Release notes')
  assert.equal(targetText({ action: 'source.added', target: null }, t), '')
})
