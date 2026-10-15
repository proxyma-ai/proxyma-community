// The chat's markdown parser: text to blocks, with no React and no DOM, so `node --test` reaches it
// (parseMarkdownBlocks.test.ts). It runs on EVERY streamed delta of a live answer, over whatever
// half-written text has arrived so far - so every branch must consume at least one line whatever
// the input, or the page hangs. One did not until 2026-09-30: a line that was only a heading's
// hashes and a space ("## ", the moment a delta ends just after it) was neither a heading nor a
// paragraph, and the loop pushed empty paragraphs forever. The desktop window froze mid-answer and
// came back only when its page ran out of memory.

// One list item, which may itself contain nested lists. Nesting is derived from source
// indentation (see parseListItems) rather than being flattened away, so a nested bullet renders as
// a nested bullet - the renderer's job is to reproduce the markdown it was given, not to constrain
// what the agent may write. What we do or don't WANT the agent to emit belongs in the system
// prompt, not here.
export interface ListItem {
  text: string
  children?: ListBlock[]
}

export interface ListBlock {
  ordered: boolean
  items: ListItem[]
}

export interface Block {
  type: 'paragraph' | 'code' | 'heading' | 'list' | 'blockquote' | 'hr' | 'table'
  content: string
  level?: number
  lang?: string
  ordered?: boolean
  list?: ListBlock
  headers?: string[]
  rows?: string[][]
}

const UL_MARKER = /^(\s*)[-*+]\s+/
const OL_MARKER = /^(\s*)\d+[.)]\s+/

interface RawListItem {
  indent: number
  ordered: boolean
  text: string
}

function isListLine(line: string): boolean {
  return UL_MARKER.test(line) || OL_MARKER.test(line)
}

function parseListLine(line: string): RawListItem {
  const ol = line.match(OL_MARKER)
  // Ordered checked first: "1. text" also satisfies neither UL pattern, but checking OL first keeps
  // the two mutually exclusive without relying on that.
  if (ol) {
    return { indent: ol[1].replace(/\t/g, '  ').length, ordered: true, text: line.slice(ol[0].length) }
  }
  const ul = line.match(UL_MARKER)!
  return { indent: ul[1].replace(/\t/g, '  ').length, ordered: false, text: line.slice(ul[0].length) }
}

// Recursive descent over a flat run of list lines, grouping by indentation. `start` is the index to
// begin at and the returned `next` is where the caller should resume, so a nested run is consumed
// exactly once. Anything indented MORE than the current level becomes children of the item above
// it; anything indented less ends this level and returns to the caller.
function buildListTree(raw: RawListItem[], start: number): { block: ListBlock; next: number } {
  const level = raw[start].indent
  const block: ListBlock = { ordered: raw[start].ordered, items: [] }
  let i = start
  while (i < raw.length) {
    const item = raw[i]
    if (item.indent < level) break
    if (item.indent > level) {
      // Deeper line with no item above it to attach to (a diff that starts indented) - treat it as
      // belonging to this level rather than dropping it.
      if (block.items.length === 0) {
        block.items.push({ text: item.text })
        i++
        continue
      }
      const nested = buildListTree(raw, i)
      const parent = block.items[block.items.length - 1]
      parent.children = [...(parent.children ?? []), nested.block]
      i = nested.next
      continue
    }
    // Same indent but a different marker type starts a sibling list, not another item of this one.
    if (item.ordered !== block.ordered) break
    block.items.push({ text: item.text })
    i++
  }
  return { block, next: i }
}

function splitTableRow(line: string): string[] {
  let t = line.trim()
  if (t.startsWith('|')) t = t.slice(1)
  if (t.endsWith('|')) t = t.slice(0, -1)
  return t.split('|').map(c => c.trim())
}

function isTableRow(line: string): boolean {
  return line.trim().includes('|')
}

function isTableSeparator(line: string): boolean {
  const t = line.trim()
  if (!t.includes('-') || !t.includes('|')) return false
  const cells = splitTableRow(t)
  return cells.length > 0 && cells.every(c => /^:?-+:?$/.test(c))
}

export function parseBlocks(text: string): Block[] {
  const lines = text.split('\n')
  const blocks: Block[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    if (line.match(/^```/)) {
      const lang = line.slice(3).trim()
      const codeLines: string[] = []
      i++
      while (i < lines.length && !lines[i].match(/^```/)) {
        codeLines.push(lines[i])
        i++
      }
      blocks.push({ type: 'code', content: codeLines.join('\n'), lang })
      i++
      continue
    }

    const headingMatch = line.match(/^(#{1,6})\s+(.+)/)
    if (headingMatch) {
      blocks.push({ type: 'heading', content: headingMatch[2], level: headingMatch[1].length })
      i++
      continue
    }

    if (line.match(/^---+$/) || line.match(/^\*\*\*+$/) || line.match(/^___+$/)) {
      blocks.push({ type: 'hr', content: '' })
      i++
      continue
    }

    if (line.match(/^>\s/)) {
      const quoteLines: string[] = []
      while (i < lines.length && lines[i].match(/^>\s?/)) {
        quoteLines.push(lines[i].replace(/^>\s?/, ''))
        i++
      }
      blocks.push({ type: 'blockquote', content: quoteLines.join('\n') })
      continue
    }

    if (isTableRow(line) && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const headers = splitTableRow(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && lines[i].trim() !== '' && isTableRow(lines[i])) {
        rows.push(splitTableRow(lines[i]))
        i++
      }
      blocks.push({ type: 'table', content: '', headers, rows })
      continue
    }

    if (isListLine(line)) {
      // Consume every consecutive list line regardless of marker type or indent, then build the
      // tree in one pass. The old parser broke out of the loop on a marker change, so a bullet
      // list nested under a numbered step ended the numbered list entirely.
      const rawItems: RawListItem[] = []
      while (i < lines.length && isListLine(lines[i])) {
        rawItems.push(parseListLine(lines[i]))
        i++
      }
      // Loop rather than one call: buildListTree stops at a marker-type change on the same level
      // (an ordered list directly followed by a bullet list is two lists, not one), so the
      // remaining items must be picked up as sibling blocks instead of being discarded.
      let consumed = 0
      while (consumed < rawItems.length) {
        const { block, next } = buildListTree(rawItems, consumed)
        blocks.push({ type: 'list', content: '', list: block })
        consumed = next > consumed ? next : consumed + 1
      }
      continue
    }

    if (line.trim() === '') {
      i++
      continue
    }

    // Everything the branches above take stops a paragraph - tested with THEIR patterns, so a line
    // cannot be refused by both. `^#{1,6}\s` here beside `^(#{1,6})\s+(.+)` above was that mismatch.
    const paraLines: string[] = []
    while (i < lines.length && lines[i].trim() !== '' && !lines[i].match(/^```/) &&
           !lines[i].match(/^#{1,6}\s+\S/) && !lines[i].match(/^[-*+]\s/) &&
           !lines[i].match(/^\d+\.\s/) && !lines[i].match(/^>\s/) &&
           !lines[i].match(/^---+$/) && !lines[i].match(/^\*\*\*+$/) && !lines[i].match(/^___+$/) &&
           !(isTableRow(lines[i]) && i + 1 < lines.length && isTableSeparator(lines[i + 1]))) {
      paraLines.push(lines[i])
      i++
    }
    // And whatever else a line turns out to be, it is taken as text rather than left where it is:
    // a pass that consumes nothing never ends.
    if (paraLines.length === 0) {
      paraLines.push(lines[i])
      i++
    }
    blocks.push({ type: 'paragraph', content: paraLines.join('\n') })
  }

  return blocks
}
