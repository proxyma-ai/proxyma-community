import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  CONNECTION_SIGN_IN_CODES,
  connectionSignInCode,
  connectionSignInMessage,
  isConnectionSignInFailure,
  withoutConnectionSignInParam,
} from './connectionSignIn.ts'

/**
 * A connection's OAuth2 sign-in callback, code to message, under `node --test`: a pure function of
 * the query string, so the sentence a user reads is pinned here with no DOM.
 */

test('every code the server sends maps to a message', () => {
  for (const code of CONNECTION_SIGN_IN_CODES) assert.ok(connectionSignInMessage(code).length > 0, code)
})

test('no parameter is nothing to report; an unknown value is a failure, never silence', () => {
  assert.equal(connectionSignInCode('?tab=chat'), null)
  assert.equal(connectionSignInCode(''), null)
  assert.equal(connectionSignInCode('?connection-oauth=connected'), 'connected')
  assert.equal(connectionSignInCode('connection-oauth=NOT_CONFIGURED'), 'not_configured')
  assert.equal(connectionSignInCode('?connection-oauth=<script>'), 'failed')
  assert.equal(connectionSignInCode('?connection-oauth='), 'failed')
})

test('only a connection is a success', () => {
  assert.equal(isConnectionSignInFailure('connected'), false)
  for (const code of CONNECTION_SIGN_IN_CODES.filter(c => c !== 'connected')) {
    assert.equal(isConnectionSignInFailure(code), true, code)
  }
})

test('the parameter is taken out and every other one kept, in order', () => {
  assert.equal(withoutConnectionSignInParam('?a=1&connection-oauth=connected&b=2'), 'a=1&b=2')
  assert.equal(withoutConnectionSignInParam('?connection-oauth=failed'), '')
  assert.equal(withoutConnectionSignInParam('?a=1'), 'a=1')
  // The Microsoft 365 notice's parameter is not this one's to remove.
  assert.equal(withoutConnectionSignInParam('?microsoft=connected&connection-oauth=denied'), 'microsoft=connected')
})
