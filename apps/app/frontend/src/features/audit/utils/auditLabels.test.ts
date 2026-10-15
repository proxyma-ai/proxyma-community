import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  actionLabel, actorText, categoryLabel, durationText, outcomeLabel, outcomeTone, readable, readableDetail, serviceLabel,
  settingLabel, words,
} from './auditLabels.ts'

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

test('who: a person by name, the local user as you, the system as Proxyma', () => {
  assert.equal(actorText('USER', 'u-1', 'Ana', t), 'Ana')
  assert.equal(actorText('USER', 'u-1', null, t), 'u-1')
  assert.equal(actorText('USER', '_local', null, t), 'You')
  assert.equal(actorText('SYSTEM', 'system', null, t), 'Proxyma')
  assert.equal(actorText('ANONYMOUS', 'anonymous', null, t), 'Guest')
})

test('a setting reads as Settings names it, its group first', () => {
  assert.equal(settingLabel('guardrail.detect-pii', t), 'Guardrails: Detect PII')
  assert.equal(settingLabel('retention.audit-days', t), 'Data Retention: Audit days')
  assert.equal(settingLabel('something-new.max-items', t), 'Something new: Max items')
  assert.equal(settingLabel(null, t), '')
})

test('services and rule categories read as words, acronyms kept', () => {
  assert.equal(serviceLabel('EMBEDDING', t), 'Indexing and search')
  assert.equal(serviceLabel('BRAND_NEW', t), 'Brand new')
  assert.equal(words('pii'), 'PII')
  assert.equal(words('secret'), 'Secret')
  assert.equal(words('PROFESSIONAL'), 'Professional')
})

test('recorded detail: yes and no for flags, words for capital codes, everything else untouched', () => {
  assert.deepEqual(readableDetail({ enforced: true, verdict: 'ALLOW', stage: 'output', n: 3, q: 'AKIA key', list: [false, 'BLOCK'] }, t),
    { enforced: 'Yes', verdict: 'Allow', stage: 'output', n: 3, q: 'AKIA key', list: ['No', 'Block'] })
})
