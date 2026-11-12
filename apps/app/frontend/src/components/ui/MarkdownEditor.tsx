import { useEffect, useRef } from 'react'
import { EditorState, Transaction, type Extension } from '@codemirror/state'
import {
  Decoration, EditorView, ViewPlugin, WidgetType, keymap, placeholder as placeholderText,
  type DecorationSet, type ViewUpdate,
} from '@codemirror/view'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { Language, defineLanguageFacet, syntaxTree } from '@codemirror/language'
import { GFM, parser as markdownParser } from '@lezer/markdown'
import { previewParts, type InlineLook, type LineLook, type MdNode } from './markdownPreview'

/**
 * THE MARKDOWN BOX'S EDITOR - CodeMirror 6 with a live preview (`markdownPreview.ts` decides what is
 * drawn). Loaded on its own when a markdown box first opens (`MarkdownField`), so a page without one
 * never fetches it.
 *
 * Why CodeMirror rather than a contentEditable of our own: an input method composes a Vietnamese
 * word inside the box, and CodeMirror is built to leave a composition alone; undo, selection and
 * the caret across hidden marks are its, not ours. Only its core is used - no language packs beyond
 * Lezer's markdown parser (GitHub's tables, task lists and strikethrough), whose HTML and code
 * languages would add a third of a megabyte to say what a fenced block's language is.
 */

/** Markdown as GitHub writes it, parsed incrementally as the person types. */
const markdown = new Language(defineLanguageFacet(), markdownParser.configure(GFM), [], 'markdown')

class Bullet extends WidgetType {
  eq() { return true }
  toDOM() {
    const span = document.createElement('span')
    span.className = 'cm-md-bullet'
    span.textContent = '•'
    span.setAttribute('aria-hidden', 'true')
    return span
  }
}

class Rule extends WidgetType {
  eq() { return true }
  toDOM() {
    const span = document.createElement('span')
    span.className = 'cm-md-rule'
    span.setAttribute('aria-hidden', 'true')
    return span
  }
}

const BULLET = Decoration.replace({ widget: new Bullet() })
const RULE = Decoration.replace({ widget: new Rule() })
const HIDE = Decoration.replace({})
const LINE: Record<LineLook, Decoration> = {
  h1: Decoration.line({ class: 'cm-md-h1' }),
  h2: Decoration.line({ class: 'cm-md-h2' }),
  h3: Decoration.line({ class: 'cm-md-h3' }),
  h4: Decoration.line({ class: 'cm-md-h4' }),
  quote: Decoration.line({ class: 'cm-md-quote' }),
  code: Decoration.line({ class: 'cm-md-codeblock' }),
  table: Decoration.line({ class: 'cm-md-table' }),
}
const STYLE: Record<InlineLook, Decoration> = {
  strong: Decoration.mark({ class: 'cm-md-strong' }),
  em: Decoration.mark({ class: 'cm-md-em' }),
  del: Decoration.mark({ class: 'cm-md-del' }),
  code: Decoration.mark({ class: 'cm-md-code' }),
  link: Decoration.mark({ class: 'cm-md-link' }),
  mark: Decoration.mark({ class: 'cm-md-mark' }),
  number: Decoration.mark({ class: 'cm-md-number' }),
  task: Decoration.mark({ class: 'cm-md-task' }),
}

/** The lines the caret or a selection is on - none while the box has no focus or cannot be edited. */
function editingLines(view: EditorView): Set<number> {
  const lines = new Set<number>()
  if (!view.hasFocus || !view.state.facet(EditorView.editable)) return lines
  const doc = view.state.doc
  for (const range of view.state.selection.ranges) {
    const last = doc.lineAt(range.to).number
    for (let line = doc.lineAt(range.from).number; line <= last; line++) lines.add(line - 1)
  }
  return lines
}

function decorate(view: EditorView): DecorationSet {
  const text = view.state.doc.toString()
  const nodes: MdNode[] = []
  syntaxTree(view.state).iterate({ enter: node => { nodes.push({ name: node.name, from: node.from, to: node.to }) } })
  const ranges = previewParts(text, nodes, editingLines(view)).map(part => {
    switch (part.kind) {
      case 'line': return LINE[part.look].range(part.from)
      case 'style': return STYLE[part.look].range(part.from, part.to)
      case 'hide': return HIDE.range(part.from, part.to)
      case 'bullet': return BULLET.range(part.from, part.to)
      case 'rule': return RULE.range(part.from, part.to)
    }
  })
  return Decoration.set(ranges, true)
}

const livePreview = ViewPlugin.fromClass(class {
  decorations: DecorationSet
  constructor(view: EditorView) { this.decorations = decorate(view) }
  update(update: ViewUpdate) {
    if (update.docChanged || update.selectionSet || update.focusChanged || update.viewportChanged
      || syntaxTree(update.startState) !== syntaxTree(update.state)) {
      this.decorations = decorate(update.view)
    }
  }
}, { decorations: plugin => plugin.decorations })

/**
 * The editor's look, in the app's tokens. CodeMirror's own base theme is scoped to the editor at a
 * higher specificity than a stylesheet's class, so its look is overridden here, where it holds.
 * The sizes are the chat's (`PROSE_CHAT`): `#` 14px, the rest 13px semibold.
 */
const look = EditorView.theme({
  '&': { color: 'inherit', backgroundColor: 'transparent', fontSize: 'inherit', height: '100%' },
  '&.cm-focused': { outline: 'none' },
  '.cm-scroller': { fontFamily: 'inherit', lineHeight: '20px', overflow: 'auto' },
  '.cm-content': { padding: '6px 0', caretColor: 'rgb(var(--ink-primary))' },
  '.cm-line': { padding: '0 12px' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'rgb(var(--ink-primary))' },
  '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: 'rgb(var(--brand-solid) / 0.35)',
  },
  '.cm-placeholder': { color: 'rgb(var(--ink-disabled))' },
  '.cm-md-h1': { fontSize: '14px', fontWeight: '600', color: 'rgb(var(--ink-primary))', paddingTop: '8px' },
  '.cm-md-h2': { fontWeight: '600', color: 'rgb(var(--ink-primary))', paddingTop: '6px' },
  '.cm-md-h3, .cm-md-h4': { fontWeight: '600', color: 'rgb(var(--ink-primary))', paddingTop: '4px' },
  '.cm-md-strong': { fontWeight: '600', color: 'rgb(var(--ink-primary))' },
  '.cm-md-em': { fontStyle: 'italic' },
  '.cm-md-del': { textDecoration: 'line-through', color: 'rgb(var(--ink-caption))' },
  '.cm-md-code': {
    fontFamily: 'var(--font-mono)', fontSize: 'max(12px, 0.9em)',
    backgroundColor: 'rgb(var(--sunk) / var(--sunk-alpha, 1))', borderRadius: '4px', padding: '1px 2px',
  },
  '.cm-md-link': { color: 'rgb(var(--brand-legible))' },
  '.cm-md-mark': { color: 'rgb(var(--ink-disabled))', fontWeight: '400', fontStyle: 'normal' },
  '.cm-md-number': { color: 'rgb(var(--ink-caption))' },
  '.cm-md-task': { fontFamily: 'var(--font-mono)', color: 'rgb(var(--ink-caption))' },
  '.cm-md-bullet': { color: 'rgb(var(--ink-caption))' },
  '.cm-md-quote': {
    borderLeft: '2px solid rgb(var(--line))', paddingLeft: '10px', marginLeft: '12px', color: 'rgb(var(--ink-caption))',
  },
  '.cm-md-codeblock': {
    fontFamily: 'var(--font-mono)', fontSize: '12px', lineHeight: '18px',
    backgroundColor: 'rgb(var(--sunk) / var(--sunk-alpha, 1))',
  },
  '.cm-md-table': { fontFamily: 'var(--font-mono)', fontSize: '12px' },
  '.cm-md-rule': {
    display: 'inline-block', width: '100%', verticalAlign: 'middle', borderTop: '1px solid rgb(var(--line))',
  },
})

export interface MarkdownEditorProps {
  value: string
  onChange?: (value: string) => void
  placeholder?: string
  /** Not editable, every line drawn rendered. */
  readOnly?: boolean
  maxLength?: number
  autoFocus?: boolean
  /** On the editable region: id, aria-label, aria-labelledby, aria-describedby, aria-invalid. */
  contentAttributes?: Record<string, string>
  /** Handed the focus function, so a label can move focus into the box. */
  focusRef?: { current: (() => void) | null }
}

/** A markdown box, drawn as it reads. The text is kept exactly as typed. */
export function MarkdownEditor({ value, onChange, placeholder, readOnly, maxLength, autoFocus, contentAttributes, focusRef }: MarkdownEditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  // The latest onChange, read by the listener that was installed once.
  const changed = useRef(onChange)
  changed.current = onChange

  useEffect(() => {
    if (!host.current) return
    const extensions: Extension[] = [
      history(),
      keymap.of([...defaultKeymap, ...historyKeymap]),
      markdown,
      livePreview,
      look,
      EditorView.lineWrapping,
      EditorView.editable.of(!readOnly),
      EditorState.readOnly.of(!!readOnly),
      EditorView.contentAttributes.of({ 'aria-multiline': 'true', role: 'textbox', ...contentAttributes }),
      EditorView.updateListener.of(update => {
        if (update.docChanged && update.transactions.some(tr => !tr.annotation(Transaction.remote))) {
          changed.current?.(update.state.doc.toString())
        }
      }),
    ]
    if (placeholder) extensions.push(placeholderText(placeholder))
    if (maxLength) {
      // A change past the limit is refused whole, as a textarea's maxLength refuses the keystroke.
      extensions.push(EditorState.changeFilter.of(tr => !tr.docChanged || tr.newDoc.length <= maxLength || tr.newDoc.length <= tr.startState.doc.length))
    }
    const created = new EditorView({ parent: host.current, state: EditorState.create({ doc: value, extensions }) })
    view.current = created
    if (focusRef) focusRef.current = () => created.focus()
    if (autoFocus) created.focus()
    return () => {
      created.destroy()
      view.current = null
      if (focusRef) focusRef.current = null
    }
    // Built once per mode; the value follows below without rebuilding (which would lose undo).
  }, [readOnly, placeholder, maxLength, JSON.stringify(contentAttributes)])

  // A value set from outside (a reset, a load) replaces the text - marked remote, so it is neither
  // reported back as the person's edit nor undone by their Ctrl+Z.
  useEffect(() => {
    const current = view.current
    if (!current) return
    const text = current.state.doc.toString()
    if (text === value) return
    current.dispatch({
      changes: { from: 0, to: text.length, insert: value },
      annotations: [Transaction.remote.of(true), Transaction.addToHistory.of(false)],
    })
  }, [value])

  return <div ref={host} className="h-full min-h-0" />
}
