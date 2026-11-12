import { test } from 'node:test'
import assert from 'node:assert/strict'
import { heldStatus, updateNotice, type UpdateNoticeInput, type UpdateNoticeStatus } from './updateNotice.ts'

function status(overrides: Partial<UpdateNoticeStatus> = {}): UpdateNoticeStatus {
  return { status: 'READY', availableVersion: 'v0.41.0', canApplyInPlace: true, installKind: 'PARTS', ...overrides }
}

function input(overrides: Partial<UpdateNoticeInput> = {}): UpdateNoticeInput {
  return { inWindow: true, update: status(), dismissedVersion: null, wide: true, ...overrides }
}

test('a staged update in the desktop window is "Restart to update", with its version', () => {
  assert.deepEqual(updateNotice(input()), { kind: 'ready', version: 'v0.41.0', compact: false })
})

test('a narrow window folds the control to its icon', () => {
  assert.deepEqual(updateNotice(input({ wide: false })), { kind: 'ready', version: 'v0.41.0', compact: true })
})

test('an update found but not downloaded (auto-update off) is the quieter "Update available"', () => {
  assert.deepEqual(updateNotice(input({ update: status({ status: 'AVAILABLE' }) })),
    { kind: 'available', version: 'v0.41.0', compact: false })
})

test('downloading, checking, up to date and a failure show nothing on the title row', () => {
  for (const s of ['DOWNLOADING', 'CHECKING', 'UP_TO_DATE', 'ERROR', 'SOMETHING_NEW']) {
    assert.deepEqual(updateNotice(input({ update: status({ status: s }) })), { kind: 'none' }, s)
  }
})

test('nothing until the status has been read', () => {
  assert.deepEqual(updateNotice(input({ update: undefined })), { kind: 'none' })
})

test('nothing outside Proxyma\'s own window - a browser has no title row', () => {
  assert.deepEqual(updateNotice(input({ inWindow: false })), { kind: 'none' })
  assert.deepEqual(updateNotice(input({ inWindow: false, update: status({ status: 'AVAILABLE' }) })), { kind: 'none' })
})

test('an installation that cannot install in place - a server, a jar run by hand - shows nothing', () => {
  const detectOnly = status({ status: 'AVAILABLE', canApplyInPlace: false, installKind: 'DETECT_ONLY' })
  assert.deepEqual(updateNotice(input({ update: detectOnly })), { kind: 'none' })
  assert.deepEqual(updateNotice(input({ update: status({ canApplyInPlace: false }) })), { kind: 'none' })
  assert.deepEqual(updateNotice(input({ update: status({ installKind: 'DETECT_ONLY' }) })), { kind: 'none' })
})

test('"Later" hides that version, and a newer one staged shows again', () => {
  assert.deepEqual(updateNotice(input({ dismissedVersion: 'v0.41.0' })), { kind: 'none' })
  assert.deepEqual(updateNotice(input({ dismissedVersion: 'v0.41.0', update: status({ availableVersion: 'v0.42.0' }) })),
    { kind: 'ready', version: 'v0.42.0', compact: false })
})

test('"Later" is about restarting: the same version found again while not downloaded still says so', () => {
  assert.deepEqual(updateNotice(input({ dismissedVersion: 'v0.41.0', update: status({ status: 'AVAILABLE' }) })),
    { kind: 'available', version: 'v0.41.0', compact: false })
})

test('no version, no control', () => {
  assert.deepEqual(updateNotice(input({ update: status({ availableVersion: '' }) })), { kind: 'none' })
})

test('a check does not take the control away: CHECKING keeps what was there before it', () => {
  const ready = status()
  assert.equal(heldStatus(ready, status({ status: 'CHECKING' })), ready)
  assert.deepEqual(updateNotice(input({ update: heldStatus(ready, status({ status: 'CHECKING' })) })),
    { kind: 'ready', version: 'v0.41.0', compact: false })
})

test('anything else that is read replaces what was there', () => {
  const after = status({ status: 'UP_TO_DATE', availableVersion: '' })
  assert.equal(heldStatus(status(), after), after)
  assert.equal(heldStatus(undefined, status({ status: 'CHECKING' }))?.status, 'CHECKING')
  const ready = status()
  assert.equal(heldStatus(ready, undefined), ready)
})
