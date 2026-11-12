import { test } from 'node:test'
import assert from 'node:assert/strict'
import { setupGuideUrl } from './setupGuide.ts'

test('a service names its manual section, in the reader language where the manual has one', () => {
  assert.equal(setupGuideUrl('google-cloud-setup', 'en'), 'https://proxyma.ai/docs/manual/#google-cloud-setup')
  assert.equal(setupGuideUrl('google-cloud-setup', 'vi'), 'https://proxyma.ai/vi/docs/manual/#google-cloud-setup')
  assert.equal(setupGuideUrl('youtube', 'fr'), 'https://proxyma.ai/docs/manual/#youtube')
})

test('no section, or one that is not an anchor, links nowhere', () => {
  assert.equal(setupGuideUrl(null, 'en'), null)
  assert.equal(setupGuideUrl('', 'en'), null)
  assert.equal(setupGuideUrl('x"><script>', 'en'), null)
})
