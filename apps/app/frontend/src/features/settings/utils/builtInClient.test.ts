import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fieldsForSignIn, hasOwnClient, usesBuiltInClient } from './builtInClient.ts'

const fields = [{ key: 'baseUrl' }, { key: 'clientId' }, { key: 'clientSecret' }]

test('with a built-in client offered and no own app chosen, the client fields are left out', () => {
  assert.equal(usesBuiltInClient({ builtInClient: true }, false), true)
  assert.deepEqual(fieldsForSignIn(fields, true).map(f => f.key), ['baseUrl'])
})

test('choosing an own app, or a service with no built-in client, shows them as before', () => {
  assert.equal(usesBuiltInClient({ builtInClient: true }, true), false)
  assert.equal(usesBuiltInClient({ builtInClient: false }, false), false)
  assert.equal(usesBuiltInClient({}, false), false)
  assert.equal(usesBuiltInClient(undefined, false), false)
  assert.deepEqual(fieldsForSignIn(fields, false).map(f => f.key), ['baseUrl', 'clientId', 'clientSecret'])
})

test('a saved connection has its own client when a Client ID is stored on it', () => {
  assert.equal(hasOwnClient({ clientId: 'abc.apps.googleusercontent.com' }), true)
  assert.equal(hasOwnClient({ clientId: '  ' }), false)
  assert.equal(hasOwnClient({}), false)
  assert.equal(hasOwnClient(null), false)
})
