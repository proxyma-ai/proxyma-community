import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CHUNK_RELOAD_KEY, claimChunkReload, isChunkLoadError } from './errorRecovery.ts'

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial))
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value) },
    data,
  }
}

test('a dynamic import that could not be fetched is a chunk error, in every browser wording', () => {
  for (const message of [
    'Failed to fetch dynamically imported module: https://app.example/assets/ChatPage-1a2b3c.js',
    'error loading dynamically imported module: https://app.example/assets/ChatPage-1a2b3c.js',
    'Importing a module script failed.',
    'Unable to preload CSS for /assets/ChatPage-1a2b3c.css',
    'Loading chunk 42 failed.',
    'Loading CSS chunk app-page failed.',
  ]) {
    assert.equal(isChunkLoadError(new TypeError(message)), true, message)
  }
  assert.equal(isChunkLoadError({ name: 'ChunkLoadError', message: '' }), true)
})

test('any other error is not a chunk error', () => {
  assert.equal(isChunkLoadError(new TypeError("Cannot read properties of undefined (reading 'map')")), false)
  assert.equal(isChunkLoadError(new Error('Request failed with status code 500')), false)
  assert.equal(isChunkLoadError('Failed to fetch dynamically imported module'), false)
  assert.equal(isChunkLoadError(null), false)
  assert.equal(isChunkLoadError(undefined), false)
})

test('the first chunk failure reloads, and records when', () => {
  const storage = memoryStorage()
  assert.equal(claimChunkReload(storage, 1_000_000), true)
  assert.equal(storage.data.get(CHUNK_RELOAD_KEY), '1000000')
})

test('a second failure inside the window is shown instead of reloading again', () => {
  const storage = memoryStorage({ [CHUNK_RELOAD_KEY]: '1000000' })
  assert.equal(claimChunkReload(storage, 1_000_000 + 59_999), false)
  // ...and the window is not extended by the refusal.
  assert.equal(storage.data.get(CHUNK_RELOAD_KEY), '1000000')
})

test('a failure after the window has passed - the next deploy - reloads once more', () => {
  const storage = memoryStorage({ [CHUNK_RELOAD_KEY]: '1000000' })
  assert.equal(claimChunkReload(storage, 1_000_000 + 60_000), true)
  assert.equal(storage.data.get(CHUNK_RELOAD_KEY), String(1_000_000 + 60_000))
})

test('a stored value that makes no sense does not block the reload', () => {
  assert.equal(claimChunkReload(memoryStorage({ [CHUNK_RELOAD_KEY]: 'garbage' }), 5_000), true)
  // A clock that moved backwards: the stored time is in the future.
  assert.equal(claimChunkReload(memoryStorage({ [CHUNK_RELOAD_KEY]: '9000000' }), 5_000), true)
})

test('with no storage, or storage that throws, it never reloads - a reload it cannot count could loop', () => {
  assert.equal(claimChunkReload(null, 1), false)
  const throwing = {
    getItem: () => { throw new Error('SecurityError') },
    setItem: () => { throw new Error('SecurityError') },
  }
  assert.equal(claimChunkReload(throwing, 1), false)
})
