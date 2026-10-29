import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dueIn, soonestFirst, type PendingAlarm } from './alarms.ts'

const NOW = Date.parse('2026-10-28T10:00:00Z')

test('how long until an alarm goes off is rounded up, in the largest unit that fits', () => {
  assert.equal(dueIn('2026-10-28T10:00:30Z', NOW), '30s')
  assert.equal(dueIn('2026-10-28T10:01:30Z', NOW), '2m')
  assert.equal(dueIn('2026-10-28T10:59:00Z', NOW), '59m')
  assert.equal(dueIn('2026-10-28T12:30:00Z', NOW), '3h')
  assert.equal(dueIn('2026-10-31T10:00:00Z', NOW), '3d')
})

test('an alarm already due, or with no readable time, has no countdown', () => {
  assert.equal(dueIn('2026-10-28T10:00:00Z', NOW), null)
  assert.equal(dueIn('2026-10-28T09:00:00Z', NOW), null)
  assert.equal(dueIn('not a date', NOW), null)
})

test('alarms are listed soonest first, an unreadable one last', () => {
  const a = (alarmId: string, dueAt: string): PendingAlarm => ({ alarmId, dueAt, note: null })
  const sorted = soonestFirst([a('late', '2026-10-28T12:00:00Z'), a('bad', 'x'), a('soon', '2026-10-28T10:05:00Z')])
  assert.deepEqual(sorted.map(x => x.alarmId), ['soon', 'late', 'bad'])
})
