// Run: node --test apps/app/frontend/src/features/settings/utils/headerValue.test.ts
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { displayHeaderValue, isSecretHeader } from './headerValue.ts'

describe('which headers carry a credential', () => {
  test('the usual names do, in any case', () => {
    for (const name of ['Authorization', 'authorization', 'X-Api-Key', 'api-key', 'X-API_KEY', 'X-Auth-Token',
      'Cookie', 'X-Session', 'Ocp-Apim-Subscription-Key', 'x-goog-api-key', 'X-Secret', 'Proxy-Authorization']) {
      assert.ok(isSecretHeader(name), name)
    }
  })

  test('attribution and routing headers do not', () => {
    for (const name of ['HTTP-Referer', 'X-Title', 'User-Agent', 'X-Region', 'Accept-Language', 'Keyboard-Layout']) {
      assert.ok(!isSecretHeader(name), name)
    }
  })
})

describe('what the list shows', () => {
  test('an ordinary header in full', () => {
    assert.equal(displayHeaderValue('HTTP-Referer', 'https://proxyma.ai'), 'https://proxyma.ai')
    assert.equal(displayHeaderValue('X-Title', 'Proxyma'), 'Proxyma')
  })

  test('a credential by its first and last characters only', () => {
    assert.equal(displayHeaderValue('X-Api-Key', 'sk-abcdef1234567890'), 'sk-***890')
    assert.equal(displayHeaderValue('X-Api-Key', 'abcdefgh'), 'a***h')
  })

  test('a short credential not even that, so its length is not given away either', () => {
    assert.equal(displayHeaderValue('X-Api-Key', 'abcd'), '***')
    assert.equal(displayHeaderValue('X-Api-Key', 'ab'), '***')
  })

  test('the scheme word of an Authorization value stays, the token after it does not', () => {
    assert.equal(displayHeaderValue('Authorization', 'Bearer sk-live-0123456789abcdef'), 'Bearer sk-***def')
    assert.equal(displayHeaderValue('Authorization', 'Basic dXNlcjpwYXNz'), 'Basic dXN***XNz')
  })

  test('nothing is nothing', () => {
    assert.equal(displayHeaderValue('Authorization', '   '), '')
  })
})
