import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseBlocks } from './parseMarkdownBlocks.ts'

// The parser runs on every streamed delta, over half-written text. Each of these is a state a live
// answer passes through; the first three hung the desktop window until 2026-09-30.
test('a heading with no text yet ends, and reads as text', () => {
  for (const text of ['Intro\n\n## ', 'Intro\n\n### ', 'Intro\n\n###\t', 'a\n### \nmore', '## ']) {
    const blocks = parseBlocks(text)
    assert.ok(blocks.length > 0 && blocks.length <= 3, `${JSON.stringify(text)} -> ${blocks.length} blocks`)
    assert.ok(blocks.every(b => b.type !== 'heading'), JSON.stringify(text))
  }
})

test('the heading it becomes is a heading once its text arrives', () => {
  assert.deepEqual(parseBlocks('Intro\n\n## Track 1: FM'), [
    { type: 'paragraph', content: 'Intro' },
    { type: 'heading', content: 'Track 1: FM', level: 2 },
  ])
})

test('every other half-written block marker also ends', () => {
  for (const text of ['- ', '1. ', '> ', '```', '| a | b |\n|', '---', '#', '#######  x', '* ', '1) ']) {
    assert.ok(Array.isArray(parseBlocks(text)), JSON.stringify(text))
  }
})

test('a paragraph runs up to the next heading', () => {
  assert.deepEqual(parseBlocks('one\ntwo\n# Title'), [
    { type: 'paragraph', content: 'one\ntwo' },
    { type: 'heading', content: 'Title', level: 1 },
  ])
})

test('a table needs its separator row', () => {
  const [table] = parseBlocks('| Key | Summary |\n|---|---|\n| SAI-1 | Fix |')
  assert.equal(table.type, 'table')
  assert.deepEqual(table.headers, ['Key', 'Summary'])
  assert.deepEqual(table.rows, [['SAI-1', 'Fix']])
})
