import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pageTitle } from './pageTitle.ts'

test('a page title is the page, a dash, and the product', () => {
  assert.equal(pageTitle('Sales', 'Proxyma License Manager'), 'Sales - Proxyma License Manager')
  assert.equal(pageTitle('Chat', 'Proxyma'), 'Chat - Proxyma')
})

test('no page is the product alone', () => {
  assert.equal(pageTitle('', 'Proxyma'), 'Proxyma')
  assert.equal(pageTitle('  ', 'Proxyma'), 'Proxyma')
  assert.equal(pageTitle(null, 'Proxyma Telemetry'), 'Proxyma Telemetry')
})
