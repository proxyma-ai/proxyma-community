import { test } from 'node:test'
import assert from 'node:assert/strict'
import { httpUrl, mailtoHref, safeExternalHref, safeLocalPath } from './safeUrl.ts'

test('an external link keeps a whole http, https or mailto address as it was given', () => {
  assert.equal(safeExternalHref('https://proxyma.ai/docs/'), 'https://proxyma.ai/docs/')
  assert.equal(safeExternalHref('http://jira.example:8080/browse/PROJ-1'), 'http://jira.example:8080/browse/PROJ-1')
  assert.equal(safeExternalHref('HTTPS://EXAMPLE.COM'), 'HTTPS://EXAMPLE.COM')
  assert.equal(safeExternalHref('mailto:someone@example.com'), 'mailto:someone@example.com')
})

test('an external link refuses every scheme that runs or reads something on this page', () => {
  for (const href of [
    'javascript:alert(1)',
    'JavaScript:alert(1)',
    ' javascript:alert(1)',
    '\u0001javascript:alert(1)',
    'java\tscript:alert(1)',
    'java\nscript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
    'blob:https://proxyma.ai/0b8f',
  ]) {
    assert.equal(safeExternalHref(href), undefined, JSON.stringify(href))
  }
})

test('an external link refuses a relative or empty address, which would pose as a server path', () => {
  for (const href of ['/api/v1/settings', 'report.pdf', '//evil.example', '', '   ', null, undefined]) {
    assert.equal(safeExternalHref(href), undefined, JSON.stringify(href))
  }
})

test('httpUrl parses only absolute http(s) addresses, and names their host', () => {
  assert.equal(httpUrl('https://cdn.example.com:8443/a.png?d=1')?.host, 'cdn.example.com:8443')
  assert.equal(httpUrl('  http://example.com/x  ')?.hostname, 'example.com')
  assert.equal(httpUrl('/api/v1/chat/conversations/x/media/a.png'), null)
  assert.equal(httpUrl('javascript:alert(1)'), null)
  assert.equal(httpUrl('ftp://example.com/a.png'), null)
  assert.equal(httpUrl(undefined), null)
})

test('a mailto link is built only for one plain address', () => {
  assert.equal(mailtoHref('real@customer.example'), 'mailto:real@customer.example')
  assert.equal(mailtoHref('  first.last+tag@sub.example.co.uk '), 'mailto:first.last+tag@sub.example.co.uk')
  for (const address of [
    'real@customer.example?bcc=attacker@evil.example',
    'real@customer.example&bcc=attacker@evil.example',
    'real@customer.example,attacker@evil.example',
    'real@customer.example;attacker@evil.example',
    'real%3Fbcc=x@customer.example',
    'real@customer.example#x',
    'two words@example.com',
    'no-at-sign.example.com',
    'nodomain@localhost',
    'javascript:alert(1)//@example.com',
    '',
    null,
  ]) {
    assert.equal(mailtoHref(address), undefined, JSON.stringify(address))
  }
})

test('a local path stays on this origin', () => {
  assert.equal(safeLocalPath('/shared/abc123'), '/shared/abc123')
  assert.equal(safeLocalPath('/chat/1?tab=x#y'), '/chat/1?tab=x#y')
  assert.equal(safeLocalPath('/'), '/')
})

test('a local path refuses another host however it is spelled', () => {
  for (const path of [
    '//evil.example',
    '/\\evil.example',
    '/\\/evil.example',
    '\\\\evil.example',
    '/\tevil',
    'https://evil.example',
    'javascript:alert(1)',
    'shared/abc',
    '',
    null,
  ]) {
    assert.equal(safeLocalPath(path), null, JSON.stringify(path))
  }
})
