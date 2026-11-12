/**
 * WHAT A MARKDOWN BOX SHOWS RENDERED, AND WHAT IT SHOWS AS TYPED (the maintainer, 2026-10-31: "make
 * all of these kind of box live markdown render support, just like the chat interface. The content
 * is rendered as the user type it").
 *
 * The box keeps the text exactly as written - an agent's instructions, a skill, a prompt are sent to
 * a model as markdown, so nothing here may rewrite them. What changes is how the text is DRAWN:
 * headings larger, bold bold, a list's "-" a bullet, a link its words, and the marks that say so
 * hidden. On the lines the caret or the selection is on, the marks come back (dimmed), so the person
 * can see and edit what they typed - the live preview Obsidian and Typora use. A box without focus
 * shows every line rendered.
 *
 * Pure, so `node --test` reaches it: it reads the parser's nodes in document order (as a Lezer tree
 * iterates them) and the text, and returns what to draw. `MarkdownEditor` turns that into
 * CodeMirror decorations.
 */

/** One node of the markdown parse, as a Lezer tree's `iterate` visits them: parents before children. */
export interface MdNode {
  name: string
  from: number
  to: number
}

/** A whole line's look. */
export type LineLook = 'h1' | 'h2' | 'h3' | 'h4' | 'quote' | 'code' | 'table'

/** A run of text's look. `mark` is a markdown mark shown on a line being edited. */
export type InlineLook = 'strong' | 'em' | 'del' | 'code' | 'link' | 'mark' | 'number' | 'task'

export type PreviewPart =
  /** The line starting at `from` takes this look. */
  | { kind: 'line'; from: number; look: LineLook }
  | { kind: 'style'; from: number; to: number; look: InlineLook }
  /** Drawn as nothing - a mark on a line not being edited. */
  | { kind: 'hide'; from: number; to: number }
  /** A bullet list's "-", "*" or "+", drawn as a bullet. */
  | { kind: 'bullet'; from: number; to: number }
  /** A "---" line, drawn as a rule. */
  | { kind: 'rule'; from: number; to: number }

/** The start offset of every line, for `lineAt`. */
export function lineStarts(text: string): number[] {
  const starts = [0]
  for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) starts.push(i + 1)
  return starts
}

/** The 0-based line `pos` is on. */
export function lineAt(starts: number[], pos: number): number {
  let lo = 0
  let hi = starts.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (starts[mid] <= pos) lo = mid
    else hi = mid - 1
  }
  return lo
}

const HEADING = /^(?:ATX|Setext)Heading([1-6])$/

/**
 * What to draw. `editing` holds the 0-based lines the caret or a selection is on - empty for a box
 * without focus or one that cannot be edited.
 */
export function previewParts(text: string, nodes: readonly MdNode[], editing: ReadonlySet<number>): PreviewPart[] {
  const starts = lineStarts(text)
  const parts: PreviewPart[] = []
  const shown = (pos: number) => editing.has(lineAt(starts, pos))
  const eachLine = (node: MdNode, look: LineLook) => {
    const last = lineAt(starts, Math.max(node.from, node.to - 1))
    for (let line = lineAt(starts, node.from); line <= last; line++) parts.push({ kind: 'line', from: starts[line], look })
  }
  /** A mark: dimmed on a line being edited, else drawn as nothing. */
  const mark = (from: number, to: number) => {
    if (to <= from) return
    if (shown(from)) parts.push({ kind: 'style', from, to, look: 'mark' })
    else parts.push({ kind: 'hide', from, to })
  }
  const spacesAfter = (pos: number) => {
    let end = pos
    while (end < text.length && (text[end] === ' ' || text[end] === '\t')) end++
    return end
  }

  // The open ancestors of the node being read, innermost last.
  const stack: MdNode[] = []
  for (const node of nodes) {
    while (stack.length > 0) {
      const top = stack[stack.length - 1]
      if (top.from <= node.from && node.to <= top.to) break
      stack.pop()
    }
    const parent = stack[stack.length - 1]
    const grand = stack[stack.length - 2]
    stack.push(node)

    const heading = HEADING.exec(node.name)
    if (heading) {
      eachLine(node, `h${Math.min(4, Number(heading[1]))}` as LineLook)
      continue
    }
    switch (node.name) {
      case 'HeaderMark': {
        if (!parent?.name.startsWith('ATX')) {
          // A setext heading's underline: there is no line to give it back to, so it stays, dimmed.
          parts.push({ kind: 'style', from: node.from, to: node.to, look: 'mark' })
        } else if (node.from === parent.from) {
          // "# " - the mark and the space after it.
          mark(node.from, spacesAfter(node.to))
        } else {
          // A closing "##", and the spaces before it.
          let start = node.from
          while (start > parent.from && text[start - 1] === ' ') start--
          mark(start, node.to)
        }
        break
      }
      case 'StrongEmphasis': parts.push({ kind: 'style', from: node.from, to: node.to, look: 'strong' }); break
      case 'Emphasis': parts.push({ kind: 'style', from: node.from, to: node.to, look: 'em' }); break
      case 'Strikethrough': parts.push({ kind: 'style', from: node.from, to: node.to, look: 'del' }); break
      case 'EmphasisMark':
      case 'StrikethroughMark':
        mark(node.from, node.to)
        break
      case 'InlineCode': parts.push({ kind: 'style', from: node.from, to: node.to, look: 'code' }); break
      case 'CodeMark':
      case 'CodeInfo':
        if (parent?.name === 'InlineCode') mark(node.from, node.to)
        else parts.push({ kind: 'style', from: node.from, to: node.to, look: 'mark' })
        break
      case 'Link': parts.push({ kind: 'style', from: node.from, to: node.to, look: 'link' }); break
      case 'Autolink': break
      case 'LinkMark':
      case 'LinkTitle':
        if (parent?.name === 'Link' || parent?.name === 'Autolink') mark(node.from, node.to)
        break
      case 'URL':
        if (parent?.name === 'Link') mark(node.from, node.to)
        // A bare address, or one in angle brackets: the address IS the words.
        else if (parent?.name !== 'Image' && parent?.name !== 'LinkReference') {
          parts.push({ kind: 'style', from: node.from, to: node.to, look: 'link' })
        }
        break
      case 'ListMark':
        if (grand?.name === 'BulletList' && !shown(node.from)) parts.push({ kind: 'bullet', from: node.from, to: node.to })
        else parts.push({ kind: 'style', from: node.from, to: node.to, look: grand?.name === 'OrderedList' ? 'number' : 'mark' })
        break
      case 'TaskMarker': parts.push({ kind: 'style', from: node.from, to: node.to, look: 'task' }); break
      case 'Blockquote': eachLine(node, 'quote'); break
      case 'QuoteMark':
        // "> " - the mark and one space after it; the quote's own bar says what it was.
        mark(node.from, text[node.to] === ' ' ? node.to + 1 : node.to)
        break
      case 'FencedCode':
      case 'CodeBlock':
        eachLine(node, 'code')
        break
      case 'Table': eachLine(node, 'table'); break
      case 'HorizontalRule':
        if (shown(node.from)) parts.push({ kind: 'style', from: node.from, to: node.to, look: 'mark' })
        else parts.push({ kind: 'rule', from: node.from, to: node.to })
        break
    }
  }
  return parts
}
