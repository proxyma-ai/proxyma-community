// Run: node --test apps/app/frontend/src/components/ui/markdownPreview.test.ts
//
// What a markdown box draws rendered and what it shows as typed. The nodes are written out as the
// Lezer markdown parser produces them (its node names and offsets, checked against @lezer/markdown
// 1.8), since this path has no node_modules.
import assert from 'node:assert/strict'
import { describe, test } from 'node:test'
import { lineAt, lineStarts, previewParts, type MdNode, type PreviewPart } from './markdownPreview.ts'

const n = (name: string, from: number, to: number): MdNode => ({ name, from, to })
const kinds = (parts: PreviewPart[], kind: PreviewPart['kind']) => parts.filter(p => p.kind === kind)
const NONE = new Set<number>()

describe('lines', () => {
  test('a position is on the line whose start it has passed', () => {
    const starts = lineStarts('ab\ncd\n\nef')
    assert.deepEqual(starts, [0, 3, 6, 7])
    assert.equal(lineAt(starts, 0), 0)
    assert.equal(lineAt(starts, 2), 0)
    assert.equal(lineAt(starts, 3), 1)
    assert.equal(lineAt(starts, 6), 2)
    assert.equal(lineAt(starts, 9), 3)
  })
})

describe('a heading', () => {
  const text = '# Hi'
  const nodes = [n('Document', 0, 4), n('ATXHeading1', 0, 4), n('HeaderMark', 0, 1)]

  test('is drawn as a heading with its "# " hidden', () => {
    const parts = previewParts(text, nodes, NONE)
    assert.deepEqual(kinds(parts, 'line'), [{ kind: 'line', from: 0, look: 'h1' }])
    assert.deepEqual(kinds(parts, 'hide'), [{ kind: 'hide', from: 0, to: 2 }])
  })

  test('shows its mark, dimmed, while its line is being edited', () => {
    const parts = previewParts(text, nodes, new Set([0]))
    assert.deepEqual(kinds(parts, 'hide'), [])
    assert.deepEqual(kinds(parts, 'style'), [{ kind: 'style', from: 0, to: 2, look: 'mark' }])
    assert.deepEqual(kinds(parts, 'line'), [{ kind: 'line', from: 0, look: 'h1' }], 'still a heading')
  })

  test('a fifth level looks like a fourth', () => {
    const parts = previewParts('##### x', [n('ATXHeading5', 0, 7), n('HeaderMark', 0, 5)], NONE)
    assert.deepEqual(kinds(parts, 'line'), [{ kind: 'line', from: 0, look: 'h4' }])
  })
})

describe('inline marks', () => {
  // "**a** _b_ `c` [l](http://x) ~~d~~", as a paragraph.
  const text = '**a** _b_ `c` [l](http://x) ~~d~~'
  const nodes = [
    n('Document', 0, 33), n('Paragraph', 0, 33),
    n('StrongEmphasis', 0, 5), n('EmphasisMark', 0, 2), n('EmphasisMark', 3, 5),
    n('Emphasis', 6, 9), n('EmphasisMark', 6, 7), n('EmphasisMark', 8, 9),
    n('InlineCode', 10, 13), n('CodeMark', 10, 11), n('CodeMark', 12, 13),
    n('Link', 14, 27), n('LinkMark', 14, 15), n('LinkMark', 16, 17), n('LinkMark', 17, 18), n('URL', 18, 26), n('LinkMark', 26, 27),
    n('Strikethrough', 28, 33), n('StrikethroughMark', 28, 30), n('StrikethroughMark', 31, 33),
  ]

  test('are styled, and every mark and the link address are hidden', () => {
    const parts = previewParts(text, nodes, NONE)
    assert.deepEqual(kinds(parts, 'style').map(p => p.kind === 'style' && p.look), ['strong', 'em', 'code', 'link', 'del'])
    assert.deepEqual(kinds(parts, 'hide').map(p => p.kind === 'hide' && text.slice(p.from, p.to)),
      ['**', '**', '_', '_', '`', '`', '[', ']', '(', 'http://x', ')', '~~', '~~'])
  })

  test('come back while their line is edited, and the text stays styled', () => {
    const parts = previewParts(text, nodes, new Set([0]))
    assert.deepEqual(kinds(parts, 'hide'), [])
    const looks = kinds(parts, 'style').map(p => p.kind === 'style' && p.look)
    assert.equal(looks.filter(l => l === 'mark').length, 13)
    for (const look of ['strong', 'em', 'code', 'link', 'del']) assert.ok(looks.includes(look as never), look)
  })

  test('a bare address is a link, its words shown', () => {
    const parts = previewParts('see http://y.com', [n('Document', 0, 16), n('Paragraph', 0, 16), n('URL', 4, 16)], NONE)
    assert.deepEqual(parts, [{ kind: 'style', from: 4, to: 16, look: 'link' }])
  })

  test("an image's address is left alone", () => {
    const parts = previewParts('![a](b.png)', [
      n('Paragraph', 0, 11), n('Image', 0, 11), n('LinkMark', 0, 2), n('LinkMark', 3, 4), n('LinkMark', 4, 5), n('URL', 5, 10), n('LinkMark', 10, 11),
    ], NONE)
    assert.deepEqual(parts, [])
  })
})

describe('lists', () => {
  // "- a\n- [ ] b\n1. c"
  const text = '- a\n- [ ] b\n1. c'
  const nodes = [
    n('Document', 0, 16),
    n('BulletList', 0, 11),
    n('ListItem', 0, 3), n('ListMark', 0, 1), n('Paragraph', 2, 3),
    n('ListItem', 4, 11), n('ListMark', 4, 5), n('Task', 6, 11), n('TaskMarker', 6, 9),
    n('OrderedList', 12, 16), n('ListItem', 12, 16), n('ListMark', 12, 14), n('Paragraph', 15, 16),
  ]

  test('a bullet list\'s mark is a bullet, a number stays a number', () => {
    const parts = previewParts(text, nodes, NONE)
    assert.deepEqual(kinds(parts, 'bullet'), [{ kind: 'bullet', from: 0, to: 1 }, { kind: 'bullet', from: 4, to: 5 }])
    assert.deepEqual(kinds(parts, 'style'), [
      { kind: 'style', from: 6, to: 9, look: 'task' },
      { kind: 'style', from: 12, to: 14, look: 'number' },
    ])
  })

  test('the item being edited shows its "-"', () => {
    const parts = previewParts(text, nodes, new Set([1]))
    assert.deepEqual(kinds(parts, 'bullet'), [{ kind: 'bullet', from: 0, to: 1 }])
    assert.ok(parts.some(p => p.kind === 'style' && p.from === 4 && p.look === 'mark'))
  })
})

describe('blocks', () => {
  test('a quote is a bar on every line, its "> " hidden', () => {
    const text = '> a\n> b'
    const parts = previewParts(text, [
      n('Document', 0, 7), n('Blockquote', 0, 7), n('QuoteMark', 0, 1), n('Paragraph', 2, 7), n('QuoteMark', 4, 5),
    ], NONE)
    assert.deepEqual(kinds(parts, 'line'), [{ kind: 'line', from: 0, look: 'quote' }, { kind: 'line', from: 4, look: 'quote' }])
    assert.deepEqual(kinds(parts, 'hide'), [{ kind: 'hide', from: 0, to: 2 }, { kind: 'hide', from: 4, to: 6 }])
  })

  test('a fenced block is code on every line, fences dimmed but never hidden', () => {
    const text = '```js\nx\n```'
    const parts = previewParts(text, [
      n('Document', 0, 11), n('FencedCode', 0, 11), n('CodeMark', 0, 3), n('CodeInfo', 3, 5), n('CodeText', 6, 7), n('CodeMark', 8, 11),
    ], NONE)
    assert.deepEqual(kinds(parts, 'line').map(p => p.from), [0, 6, 8])
    assert.deepEqual(kinds(parts, 'hide'), [])
    assert.equal(kinds(parts, 'style').length, 3)
  })

  test('a rule is drawn as a rule until its line is edited', () => {
    const nodes = [n('Document', 0, 3), n('HorizontalRule', 0, 3)]
    assert.deepEqual(previewParts('---', nodes, NONE), [{ kind: 'rule', from: 0, to: 3 }])
    assert.deepEqual(previewParts('---', nodes, new Set([0])), [{ kind: 'style', from: 0, to: 3, look: 'mark' }])
  })

  test('a table is laid out in a fixed-width face, line by line', () => {
    const text = '|a|\n|-|\n|b|'
    const parts = previewParts(text, [n('Document', 0, 11), n('Table', 0, 11)], NONE)
    assert.deepEqual(kinds(parts, 'line').map(p => p.from), [0, 4, 8])
  })
})

describe('plain prose', () => {
  test('a paragraph draws nothing of its own, so the text is exactly as typed', () => {
    const text = 'One line\nand the next'
    assert.deepEqual(previewParts(text, [n('Document', 0, 21), n('Paragraph', 0, 21)], NONE), [])
  })
})
