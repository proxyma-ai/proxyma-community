import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isConversationFileLink, mediaKind, mediaSource } from './mediaSource.ts'

const CONV = '/api/v1/chat/conversations/3f2a9c1e-7b4d-4e2a-9f1c-0a1b2c3d4e5f'

test("this deployment's conversation media, files and attachments are drawn inline", () => {
  for (const path of [
    `${CONV}/media/chart_1a2b.png`,
    `${CONV}/files/revenue%20by%20month.png`,
    `${CONV}/attachments/photo.jpg`,
    `  ${CONV}/media/clip.mp4  `,
  ]) {
    assert.deepEqual(mediaSource(path), { kind: 'own', path: path.trim() }, path)
  }
})

test('any other API path is not media - an <img> GET carries the cookies', () => {
  for (const path of [
    '/api/v1/settings/mcp-tokens',
    '/api/v1/config-transfer/export',
    `${CONV}`,
    `${CONV}/media/`,
    `${CONV}/media/a/b.png`,
    `${CONV}/media/..`,
    `${CONV}/media/%2e%2E`,
    `${CONV}/files/.`,
    `${CONV}/media/a.png?x=1`,
    `${CONV}/media/a.png#x`,
    `${CONV}/secrets/a.png`,
    '/api/v1/chat/conversations/../settings/media/a.png',
  ]) {
    assert.deepEqual(mediaSource(path), { kind: 'none' }, path)
  }
})

test('media on another host waits for a click, and names the host', () => {
  assert.deepEqual(
    mediaSource('https://attacker.example/p.png?d=the%20question'),
    { kind: 'remote', url: 'https://attacker.example/p.png?d=the%20question', host: 'attacker.example' },
  )
  assert.deepEqual(
    mediaSource('HTTP://Cdn.Example.com:8080/a.gif'),
    { kind: 'remote', url: 'http://cdn.example.com:8080/a.gif', host: 'cdn.example.com:8080' },
  )
})

test('anything else is text: other schemes, bare names, local paths', () => {
  for (const src of [
    'javascript:alert(1)',
    'data:image/svg+xml,<svg onload=alert(1)>',
    'blob:https://app.example/1234',
    'file:///C:/Users/me/a.png',
    'C:\\Users\\me\\a.png',
    'chart.png',
    '//attacker.example/p.png',
    'media-unavailable',
    'https://',
  ]) {
    assert.deepEqual(mediaSource(src), { kind: 'none' }, src)
  }
})

test("a link to a conversation's file opens the preview only in the exact shape", () => {
  assert.equal(isConversationFileLink(`${CONV}/files/report.docx`), true)
  assert.equal(isConversationFileLink(`${CONV}/attachments/contract%20v2.pdf`), true)
  for (const href of [
    `${CONV}/media/a.png`,
    `${CONV}/files/..`,
    `${CONV}/files/%2E%2e`,
    `${CONV}/files/a/b.docx`,
    `${CONV}/files/a.docx?x=1`,
    '/api/v1/chat/conversations/../files/a.docx',
    '/api/v1/chat/conversations/%2e%2e/files/a.docx',
    'https://example.com/api/v1/chat/conversations/x/files/a.docx',
  ]) {
    assert.equal(isConversationFileLink(href), false, href)
  }
})

test('the kind follows the extension, a query string allowed', () => {
  assert.equal(mediaKind(`${CONV}/media/clip.MP4`), 'video')
  assert.equal(mediaKind('https://x.example/a.webm?t=3'), 'video')
  assert.equal(mediaKind(`${CONV}/media/memo.m4a`), 'audio')
  assert.equal(mediaKind('https://x.example/song.flac'), 'audio')
  assert.equal(mediaKind(`${CONV}/media/chart.png`), 'image')
  assert.equal(mediaKind('https://x.example/no-extension'), 'image')
})
