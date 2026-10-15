import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseCsv, sniffDelimiter } from './parseCsv.ts'

test('plain rows, CRLF and LF alike, no phantom last row', () => {
  assert.deepEqual(parseCsv('a,b\r\n1,2\n3,4\n'), [['a', 'b'], ['1', '2'], ['3', '4']])
})

test('quoted fields keep commas, line breaks and escaped quotes', () => {
  assert.deepEqual(parseCsv('name,note\n"Doe, J.","said ""hi""\nthen left"'),
    [['name', 'note'], ['Doe, J.', 'said "hi"\nthen left']])
})

test('empty fields survive', () => {
  assert.deepEqual(parseCsv('a,,c\n,,\n'), [['a', '', 'c'], ['', '', '']])
})

test('the delimiter is sniffed from the first line, outside quotes', () => {
  assert.equal(sniffDelimiter('a;b;c\n1;2;3'), ';')
  assert.equal(sniffDelimiter('a\tb\n1\t2'), '\t')
  assert.equal(sniffDelimiter('"x,y";z\n'), ';')
  assert.equal(sniffDelimiter('single'), ',')
  assert.deepEqual(parseCsv('a;b\n1;2'), [['a', 'b'], ['1', '2']])
})

test('maxRows stops early', () => {
  assert.deepEqual(parseCsv('1\n2\n3\n4\n', 2), [['1'], ['2']])
})
