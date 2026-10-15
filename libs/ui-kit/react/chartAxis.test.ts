// Run: node --test libs/ui-kit/react/chartAxis.test.ts
//
// Plain `.ts` with no JSX, so Node's own type stripping runs it. ColumnChart draws the labels; which
// ones it can afford to draw is decided here.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { axisGroups, axisTicks } from './chartAxis.ts'

// 30 days from Sep 6: "6", "7" ... "30", "1" ... "5".
const days = Array.from({ length: 30 }, (_, i) => String(((5 + i) % 30) + 1))
const months = Array.from({ length: 30 }, (_, i) => (i < 25 ? 'Sep' : 'Oct'))

describe('the day row', () => {
  test('a 30-day chart with room labels every day', () => {
    const shown = axisTicks(days, 720).map(t => t.index)
    assert.equal(shown.length, 30)
    assert.deepEqual(shown.slice(0, 3), [0, 1, 2])
  })

  test('narrow columns thin to every 2nd, then 3rd, then 7th day - never overlapping', () => {
    assert.deepEqual(axisTicks(days, 400).map(t => t.index).slice(0, 3), [0, 2, 4])
    assert.deepEqual(axisTicks(days, 250).map(t => t.index).slice(0, 3), [0, 3, 6])
    assert.deepEqual(axisTicks(days, 120).map(t => t.index), [0, 7, 14, 21, 28])
  })

  test('no two labels drawn touch, at any width', () => {
    for (const width of [60, 90, 150, 300, 500, 900, 1400]) {
      const ticks = axisTicks(days, width)
      for (let k = 1; k < ticks.length; k++) {
        const prevRight = ticks[k - 1].left + ticks[k - 1].label.length * 6
        assert.ok(prevRight + 6 <= ticks[k].left, `width ${width}: ${ticks[k - 1].label} touches ${ticks[k].label}`)
      }
    }
  })

  test('a label stays inside the plot at both ends', () => {
    const ticks = axisTicks(['10', '11', '12'], 30)
    assert.equal(ticks[0].left, 0)
    const last = axisTicks(['Oct', 'Nov', 'Dec'], 600)
    const end = last[last.length - 1]
    assert.ok(end.left + end.label.length * 6 <= 600)
  })

  test('nothing to label is no labels', () => {
    assert.deepEqual(axisTicks([], 500), [])
  })

  test('a plot too narrow for any rhythm still names the first column', () => {
    assert.deepEqual(axisTicks(days, 5, { steps: [1, 2] }).map(t => t.index), [0])
  })
})

describe('the month row', () => {
  test('each month is written once, where it starts', () => {
    assert.deepEqual(axisGroups(months, 720).map(g => [g.index, g.label]), [[0, 'Sep'], [25, 'Oct']])
  })

  test('a month too short for its name gives way to the next one', () => {
    // Two days of September, then October: "Sep" at column 0 would run into "Oct" at column 2.
    const cramped = ['Sep', 'Sep', 'Oct', 'Oct', 'Oct', 'Oct', 'Oct', 'Oct', 'Oct', 'Oct']
    assert.deepEqual(axisGroups(cramped, 100).map(g => g.label), ['Oct'])
    assert.deepEqual(axisGroups(cramped, 400).map(g => g.label), ['Sep', 'Oct'])
  })

  test('a group starting at the last column ends where the plot does', () => {
    const g = axisGroups(['2025', '2025', '2026'], 60)
    const last = g[g.length - 1]
    assert.equal(last.label, '2026')
    assert.equal(last.left, 60 - 4 * 6)
  })
})
