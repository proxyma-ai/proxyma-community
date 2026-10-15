import assert from 'node:assert/strict'
import { test } from 'node:test'

import { mayAskForCatalog } from './catalogQuery.ts'

/**
 * When the page asks for the connector catalog, under `node --test` (see graph3d.test.ts for the
 * arrangement). Found by KeyAccountsUiIT: the signed-out page of an Enterprise Server asked for it
 * and logged a 401.
 */

test('an edition without accounts asks at once', () => {
  assert.equal(mayAskForCatalog(false, false), true)
})

test('an edition with accounts does not ask for a visitor', () => {
  assert.equal(mayAskForCatalog(true, false), false)
})

test('an edition with accounts asks once somebody has signed in', () => {
  assert.equal(mayAskForCatalog(true, true), true)
})
