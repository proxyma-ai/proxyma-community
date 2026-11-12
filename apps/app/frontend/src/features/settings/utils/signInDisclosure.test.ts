import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  disclosureBeforeSignIn, GOOGLE_PERMISSIONS_URL, GOOGLE_PRIVACY_URL, PROXYMA_PRIVACY_URL, YOUTUBE_TERMS_URL,
} from './signInDisclosure.ts'

test('a YouTube sign-in is disclosed first; every other service goes straight to its sign-in', () => {
  assert.equal(disclosureBeforeSignIn('YOUTUBE'), 'youtube')
  for (const other of ['GOOGLE', 'MICROSOFT_365', 'JIRA', '', null, undefined]) {
    assert.equal(disclosureBeforeSignIn(other), null, String(other))
  }
})

test('the links are the ones YouTube asks for, on their own hosts, over https', () => {
  assert.equal(new URL(YOUTUBE_TERMS_URL).href, 'https://www.youtube.com/t/terms')
  assert.equal(new URL(GOOGLE_PRIVACY_URL).host, 'policies.google.com')
  assert.equal(new URL(GOOGLE_PERMISSIONS_URL).host, 'myaccount.google.com')
  assert.equal(new URL(PROXYMA_PRIVACY_URL).href, 'https://proxyma.ai/privacy')
  for (const url of [YOUTUBE_TERMS_URL, GOOGLE_PRIVACY_URL, GOOGLE_PERMISSIONS_URL, PROXYMA_PRIVACY_URL]) {
    assert.equal(new URL(url).protocol, 'https:')
  }
})
