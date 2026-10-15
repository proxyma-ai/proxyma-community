import { test } from 'node:test'
import assert from 'node:assert/strict'
import { COMMUNITY_SOURCE_URL, communitySource } from './openSource.ts'

// The AGPL section 13 offer in Settings > About links the source of the version it describes: a
// release is the public repository's tag, and a development build - never exported - is not said
// to have its source published (release review 2026-10-15, W14).

test("a release links its own tag, not the repository's moving root", () => {
  assert.deepEqual(communitySource('0.36.0'), { kind: 'release', url: `${COMMUNITY_SOURCE_URL}/tree/v0.36.0` })
  assert.deepEqual(communitySource(' 1.2.10 '), { kind: 'release', url: `${COMMUNITY_SOURCE_URL}/tree/v1.2.10` })
})

test('a development build is not a published version', () => {
  assert.deepEqual(communitySource('0.36.1-dev12'), { kind: 'development', url: COMMUNITY_SOURCE_URL })
  assert.deepEqual(communitySource('0.36.0-SNAPSHOT'), { kind: 'development', url: COMMUNITY_SOURCE_URL })
  assert.equal(communitySource('dev').kind, 'development')
})

test('a version not known yet links the repository and claims nothing about it', () => {
  assert.deepEqual(communitySource(undefined), { kind: 'unknown', url: COMMUNITY_SOURCE_URL })
  assert.deepEqual(communitySource(null), { kind: 'unknown', url: COMMUNITY_SOURCE_URL })
  assert.deepEqual(communitySource(''), { kind: 'unknown', url: COMMUNITY_SOURCE_URL })
})
