import { FEATURES } from '@/config/features'
import { useEffect, useId, useRef, useState } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { useQuery } from '@tanstack/react-query'
import {
  FileText,
  ListChecks,
} from 'lucide-react'
import { cn } from '@/utils/cn'
import { useTranslation } from '@/i18n'
import { Caption, MenuEmpty, MenuGroup, MenuRow, Popover, Readout, Tooltip, chipClass, type GlyphComponent } from '@/components/ui/kit'
import { mentionIcon } from '../utils/mentionStyle'
import { useMentionSuggestions } from '../hooks/useMentionSuggestions'
import { tokenizeMentionText } from '../utils/tokenizeMentionText'
import { skillsApi } from '@/features/settings/services/skillsApi'
import { workflowsApi } from '@/features/settings/services/workflowsApi'
import { keys } from '@/services/queryKeys'
import type { ConnectorConfig } from '@/features/indexing/types/indexing.types'


const MENTION_DATA_ATTR = 'data-mention-name'
const MENTION_TYPE_ATTR = 'data-mention-type'
const SKILL_MENTION_DATA_ATTR = 'data-skill-mention-name'

// One entry in the "/" dropdown, whichever kind of thing it is. Flattened to a common shape here
// rather than rendering two lists: the person typing "/" is choosing a thing to run and does not
// have to know in advance which of the two registers it lives in.
interface SlashItem {
  id: string
  name: string
  shortDescription: string
  kind: 'skill' | 'workflow'
  // Workflows only - the number of turns running this will take.
  stepCount?: number
}

// Builds one atomic, non-editable chip node (icon + name) and mounts a tiny standalone React root
// into it for the icon - the chip itself is created imperatively (not via React's own reconciler,
// since this whole subtree lives inside a contentEditable region the browser owns), but the icon
// still comes from the exact same lucide-react component every other icon in the app uses, not a
// hand-duplicated SVG. `roots` tracks each mount so the container's MutationObserver (see the
// component below) can unmount it once the chip node is removed (Backspace, rebuild) - shared by
// both document-mention chips and skill-mention chips, which only differ in icon and attribute.
function buildChipElement(
  label: string, Icon: GlyphComponent, attrs: Record<string, string>, roots: Map<HTMLElement, Root>,
): HTMLSpanElement {
  const chip = document.createElement('span')
  chip.contentEditable = 'false'
  for (const [key, val] of Object.entries(attrs)) chip.setAttribute(key, val)
  // The kit chip's classes (`chipClass`), since this chip is a DOM node React does not render: the
  // `solid` variant in the brand fill - one shape and one fill with every other mention chip, the
  // sent message's included (mentionStyle.ts). 12rem at most, as the sent one is.
  chip.className = cn(chipClass('solid'), 'mx-0.5 max-w-48 select-none')
  const iconMount = document.createElement('span')
  iconMount.className = 'inline-flex items-center shrink-0'
  chip.appendChild(iconMount)
  const labelEl = document.createElement('span')
  labelEl.textContent = label
  labelEl.className = 'truncate min-w-0'
  chip.appendChild(labelEl)

  const root = createRoot(iconMount)
  root.render(<Icon size={14} aria-hidden="true" />)
  roots.set(chip, root)
  return chip
}

function createMentionChip(name: string, type: string | undefined, roots: Map<HTMLElement, Root>): HTMLSpanElement {
  return buildChipElement(name, mentionIcon(type), { [MENTION_DATA_ATTR]: name, [MENTION_TYPE_ATTR]: type ?? '' }, roots)
}

// A skill (or a workflow) is a thing to RUN rather than a thing to read: the same chip, with the
// skill glyph.
function createSkillChip(name: string, roots: Map<HTMLElement, Root>): HTMLSpanElement {
  return buildChipElement(name, FileText, { [SKILL_MENTION_DATA_ATTR]: name }, roots)
}

function isMentionChip(node: Node): node is HTMLElement {
  return node instanceof HTMLElement && node.hasAttribute(MENTION_DATA_ATTR)
}

function isSkillChip(node: Node): node is HTMLElement {
  return node instanceof HTMLElement && node.hasAttribute(SKILL_MENTION_DATA_ATTR)
}

// Backspace with the caret at the end of a chip's own auto-inserted trailing space (see
// selectMention) would otherwise take two presses to fully undo a mention: one native press to
// eat the space (landing the caret directly after the chip), a second to delete the now-adjacent
// chip (which browsers do delete as one atomic unit for a `contenteditable="false"` child - that
// part genuinely is native, unassisted behavior). This collapses both into a single press by
// removing the chip and its space together whenever the space is still untouched since insertion
// (its content is exactly one space) - the moment the user types anything else into that space,
// it no longer matches, and Backspace reverts to normal per-character/native-chip-atomic behavior.
function collapseTrailingMentionSpace(container: HTMLElement | null): boolean {
  if (!container) return false
  const sel = window.getSelection()
  if (!sel || !sel.isCollapsed || sel.rangeCount === 0) return false
  const range = sel.getRangeAt(0)
  const node = range.startContainer
  if (node.nodeType !== Node.TEXT_NODE || node.textContent !== ' ' || range.startOffset !== 1) return false
  const chip = node.previousSibling
  if (!chip || (!isMentionChip(chip) && !isSkillChip(chip))) return false

  const parent = container
  const chipIndex = Array.prototype.indexOf.call(parent.childNodes, chip)
  chip.remove()
  parent.removeChild(node)

  const newRange = document.createRange()
  newRange.setStart(parent, Math.min(chipIndex, parent.childNodes.length))
  newRange.collapse(true)
  sel.removeAllRanges()
  sel.addRange(newRange)
  return true
}

// Walks the editor's flat child list (text nodes, mention/skill chips, and the occasional <br>
// from a Shift+Enter linebreak) back into the plain-string wire format ("@Name "/"/Name " tokens)
// the rest of the app already expects (see QueuedMessage.content / ChatRequest.message) - chips
// are purely a display concern, never a new format the backend needs to learn.
function serialize(container: HTMLElement): string {
  let out = ''
  for (const node of container.childNodes) {
    if (node.nodeType === Node.TEXT_NODE) {
      out += node.textContent ?? ''
    } else if (node.nodeName === 'BR') {
      out += '\n'
    } else if (isMentionChip(node)) {
      out += `@${node.getAttribute(MENTION_DATA_ATTR)}`
    } else if (isSkillChip(node)) {
      out += `/${node.getAttribute(SKILL_MENTION_DATA_ATTR)}`
    } else if (node instanceof HTMLElement) {
      // Defensive fallback for a stray <div>/<p> the browser may introduce on some paste paths -
      // treat it as a new line followed by its own text, rather than silently dropping content.
      out += (out && !out.endsWith('\n') ? '\n' : '') + (node.textContent ?? '')
    }
  }
  return out
}

// Rebuilds the editor's DOM from an externally-set `value` (conversation cleared after send, or
// prefilled by "edit last message" - see ChatPage's handleRewind). Only ever called when `value`
// diverges from what this component itself last emitted (see the sync effect below) - never on
// every render, which would fight the user's own typing and reset the caret on each keystroke.
// `typeOf(name)` resolves a chip's icon/color from this session's own local memory of connector
// types seen so far (see nameToTypeRef) - a name whose type was never seen this session (e.g. a
// rewind right after a fresh page load) falls back to a neutral icon rather than guessing wrong.
function rebuildFromValue(
  container: HTMLElement, value: string, mentions: string[], skillMentions: string[],
  roots: Map<HTMLElement, Root>, typeOf: (name: string) => string | undefined,
) {
  const nodes: Node[] = tokenizeMentionText(value, mentions, skillMentions).map(t => {
    if (t.kind === 'text') return document.createTextNode(t.value)
    if (t.kind === 'mention') return createMentionChip(t.name, typeOf(t.name), roots)
    return createSkillChip(t.name, roots)
  })
  container.replaceChildren(...nodes)
}

// Detects an in-progress "@token" (document mention) or "/token" (skill mention) ending at the
// caret, scoped to the single text node the caret sits in - true in the overwhelming common case,
// since a chip always leaves a fresh text node right after itself (see insertChipAndAdvanceCaret)
// and the trigger char is always freshly typed by the user, never something a chip insertion
// leaves mid-node. Returns the exact Range boundaries of "@token"/"/token" too, so selectMention/
// selectSkill can replace precisely that span without re-deriving it from scratch. When both "@"
// and "/" appear before the caret in the same text node, whichever is closer to the caret wins -
// that's the one actually still being typed.
function getCaretMentionQuery(
  container: HTMLElement,
): { trigger: '@' | '/'; query: string; textNode: Text; atOffset: number; caretOffset: number } | null {
  const sel = window.getSelection()
  if (!sel || sel.rangeCount === 0 || !sel.isCollapsed) return null
  const range = sel.getRangeAt(0)
  if (!container.contains(range.startContainer)) return null
  const node = range.startContainer
  if (node.nodeType !== Node.TEXT_NODE) return null
  const text = node.textContent ?? ''
  const caretOffset = range.startOffset
  const before = text.slice(0, caretOffset)
  const atIdx = before.lastIndexOf('@')
  const slashIdx = before.lastIndexOf('/')
  const at = Math.max(atIdx, slashIdx)
  if (at === -1) return null
  const trigger: '@' | '/' = at === atIdx ? '@' : '/'

  if (at > 0) {
    if (!/\s/.test(before[at - 1])) return null
  } else {
    const prev = node.previousSibling
    if (prev && !isMentionChip(prev) && !isSkillChip(prev)) {
      const prevText = prev.textContent ?? ''
      if (prevText.length > 0 && !/\s/.test(prevText[prevText.length - 1])) return null
    }
  }

  const token = before.slice(at + 1)
  if (/\s/.test(token)) return null
  return { trigger, query: token, textNode: node as Text, atOffset: at, caretOffset }
}

// "Empty" means NOTHING A READER CAN SEE: no chip, and no text but line breaks. Checking the DOM's
// shape instead under-detects it, because what an emptied editor holds depends on how it was
// emptied. Deleting typed text leaves a stray `<br>` (Blink keeps one to hold the line height).
// After a Shift+Enter it leaves a bare "\n" TEXT node instead - this editor is pre-wrap, so
// insertLineBreak writes a newline character, not a <br> - and a pasted multi-line block leaves
// `<div>` wrappers. The old check allowed only the first, so the placeholder never came back after
// the other two (found 2026-09-26 by driving the real composer; the maintainer reported it).
function isEditorEmpty(container: HTMLElement): boolean {
  if (container.querySelector(`[${MENTION_DATA_ATTR}],[${SKILL_MENTION_DATA_ATTR}]`)) return false
  return !/[^\r\n\u200B]/.test(container.textContent ?? '')
}

interface Props {
  value: string
  onChange: (v: string) => void
  onSend: () => void
  placeholder?: string
  autoFocus?: boolean
  streaming?: boolean
  mentions: string[]
  onMentionsChange: (mentions: string[]) => void
  skillMentions: string[]
  onSkillMentionsChange: (names: string[]) => void
}

// contentEditable-based replacement for a plain <textarea> - only a contentEditable region can mix
// freely-typed text with atomic, non-text mention chip nodes (real icon + per-type color) in one
// flow, which a <textarea> (plain characters only) cannot render at all. One backspace deleting a
// whole chip is mostly native browser behavior for a `contenteditable="false"` child of an
// editable region, plus one small assist (collapseTrailingMentionSpace above) for the moment right
// after picking a mention, where the caret sits after the chip's own auto-inserted trailing space
// rather than the chip itself. The wire format (`value`, a plain string with literal "@Name "
// tokens) is unchanged from the old textarea, so nothing downstream (useChat, chatApi) needed to
// change - this component only changes how that string is *edited*, not what it *is*.
export function MentionRichInput({
  value, onChange, onSend, placeholder, autoFocus, streaming, mentions, onMentionsChange,
  skillMentions, onSkillMentionsChange,
}: Props) {
  const { t } = useTranslation()
  const editorRef = useRef<HTMLDivElement>(null)
  const rootsRef = useRef<Map<HTMLElement, Root>>(new Map())
  const nameToTypeRef = useRef<Map<string, string>>(new Map())
  // What this component itself last told the parent via onChange - lets the external-value sync
  // effect below tell "the parent's value prop is just an echo of my own change" (skip rebuild)
  // apart from "the parent changed it out from under me" (send-clear, rewind prefill - rebuild).
  // Starts EMPTY, not at `value`: the editor's DOM starts empty, so a mount with a draft already in
  // hand (composerDraftStore, coming back to the chat page) has to be rebuilt from it - seeded with
  // `value`, the sync below took the draft for an echo and left the box blank over a kept draft.
  const lastEmittedRef = useRef('')
  // Which mention "namespace" is currently being typed, if any - "@" for document mentions, "/"
  // for skill mentions - plus the partial query text typed so far after the trigger char.
  const [pending, setPending] = useState<{ trigger: '@' | '/'; query: string } | null>(null)
  const [activeIndex, setActiveIndex] = useState(0)
  const suggestionListRef = useRef<HTMLDivElement>(null)
  // A COMBOBOX (CHAT-12): the editor keeps focus while the arrows move a highlight through the list,
  // so the highlighted row is named to assistive technology by `aria-activedescendant`. The kit's
  // MenuRow takes no id, so each row's id and `aria-selected` are set on its node below.
  const listId = useId()
  const optionId = (i: number) => `${listId}-option-${i}`
  const [isEmpty, setIsEmpty] = useState(value.length === 0)

  const { data: docSuggestions = [] } = useMentionSuggestions(pending?.trigger === '@' ? pending.query : null)
  // Skills and workflows are both small, per-user lists (unlike the document-connector search
  // above, which hits the server per keystroke) - fetched once each and filtered client-side.
  const { data: skills = [] } = useQuery({ queryKey: keys.chatSkills(), queryFn: skillsApi.list })
  // Not fetched at all while workflows are hidden: `enabled` false rather than filtering the
  // result, so the composer makes no call to an endpoint that is not mounted.
  const { data: workflows = [] } = useQuery({
    queryKey: keys.chatWorkflows(), queryFn: workflowsApi.list, enabled: FEATURES.workflows,
  })
  // ONE namespace, two kinds of thing behind it. Two namespaces is already the right number - "@"
  // is a thing to read, "/" is a thing to run - and a workflow is plainly a thing to run, so it
  // joins this list rather than taking a third trigger character. The two are told apart by their
  // icon and by a step count, not by being listed separately: names are unique across both sets
  // (creation of either rejects a name the other has), so the reader never has to know which list
  // to look in. `kind` is for display only - the backend resolves the name against workflows first
  // and treats the rest as skills, which is why both kinds travel in `skillMentions`.
  const slashSuggestions: SlashItem[] = pending?.trigger === '/'
    ? [
        ...workflows.map(w => ({
          id: w.id, name: w.name, shortDescription: w.shortDescription,
          kind: 'workflow' as const, stepCount: w.stepCount,
        })),
        ...skills.map(s => ({
          id: s.id, name: s.name, shortDescription: s.shortDescription, kind: 'skill' as const,
        })),
      ]
        .filter(item => item.name.toLowerCase().includes(pending.query.toLowerCase()))
        .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
    : []
  const suggestionCount = pending?.trigger === '/' ? slashSuggestions.length : docSuggestions.length

  // Unmounts each chip's tiny icon root once its DOM node is actually removed (Backspace, or a
  // full rebuild via replaceChildren) - covers both removal paths with one observer instead of
  // duplicating cleanup at every call site that can remove a chip node.
  useEffect(() => {
    const container = editorRef.current
    if (!container) return
    const observer = new MutationObserver(mutations => {
      for (const m of mutations) {
        m.removedNodes.forEach(n => {
          if (n instanceof HTMLElement) {
            const root = rootsRef.current.get(n)
            if (root) { root.unmount(); rootsRef.current.delete(n) }
          }
        })
      }
    })
    observer.observe(container, { childList: true })
    return () => observer.disconnect()
  }, [])

  // Only rebuilds when `value` diverges from our own last-emitted value (see lastEmittedRef docs
  // above) - never on every render, which would reset the caret to the start on each keystroke.
  useEffect(() => {
    if (value === lastEmittedRef.current) return
    const container = editorRef.current
    if (!container) return
    rebuildFromValue(container, value, mentions, skillMentions, rootsRef.current, name => nameToTypeRef.current.get(name))
    lastEmittedRef.current = value
    setIsEmpty(isEditorEmpty(container))
    const sel = window.getSelection()
    if (sel) {
      const r = document.createRange()
      r.selectNodeContents(container)
      r.collapse(false)
      sel.removeAllRanges()
      sel.addRange(r)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value])

  // FOCUS ON MOUNT, and back to the editor when a turn starts or ends - ONLY when focus is nowhere
  // else (CHAT-02). It re-focused on every flip of `streaming`, unconditionally, and a turn a
  // background deployed agent's report starts by itself flips it too: with a dialog open (the feedback
  // form, the agents map, a tool call's detail, a preview), focus jumped behind the scrim, typing
  // went into the hidden composer and the dialog's Tab trap stopped holding. Not on a touch screen
  // either, where a focus raises the on-screen keyboard at the end of every turn.
  useEffect(() => {
    if (autoFocus) editorRef.current?.focus()
  }, [autoFocus])
  const wasStreaming = useRef(streaming)
  useEffect(() => {
    const flipped = wasStreaming.current !== streaming
    wasStreaming.current = streaming
    if (!autoFocus || !flipped) return
    if (document.querySelector('[data-dialog-panel]')) return
    try { if (window.matchMedia('(pointer: coarse)').matches) return } catch { /* no matchMedia: a desktop */ }
    const active = document.activeElement
    if (active && active !== document.body && !editorRef.current?.contains(active)) return
    editorRef.current?.focus()
  }, [autoFocus, streaming])

  useEffect(() => { setActiveIndex(0) }, [pending, suggestionCount])

  // The arrow keys move the highlight while focus stays in the editor, so nothing scrolls the
  // panel on its own - a highlight past its bottom edge was invisible. Brings the row into view, and
  // names each row for `aria-activedescendant` (see listId).
  useEffect(() => {
    const rows = suggestionListRef.current?.querySelectorAll<HTMLElement>('[role="option"]')
    rows?.forEach((row, i) => {
      row.id = optionId(i)
      row.setAttribute('aria-selected', String(i === activeIndex))
    })
    rows?.[activeIndex]?.scrollIntoView({ block: 'nearest' })
  })

  // A press outside the editor and its suggestions closes them - the kit Popover's own outside
  // press (onOpenChange below). The panel is portaled, so a containment test on the editor alone
  // would close it on a press INSIDE the panel, before the row's click could land.

  // Re-derives everything downstream of a DOM mutation in one place: the plain-string value (and
  // lastEmittedRef, so the sync effect above knows this was our own change), which mentions/
  // skillMentions are still actually present as chips (exact - no more substring-guessing against
  // plain text), and the empty/placeholder state.
  function handleContentChanged() {
    const container = editorRef.current
    if (!container) return
    const text = serialize(container)
    lastEmittedRef.current = text
    onChange(text)
    const present = new Set(
      Array.from(container.querySelectorAll<HTMLElement>(`[${MENTION_DATA_ATTR}]`))
        .map(el => el.getAttribute(MENTION_DATA_ATTR)!),
    )
    const next = mentions.filter(m => present.has(m))
    if (next.length !== mentions.length) onMentionsChange(next)

    const presentSkills = new Set(
      Array.from(container.querySelectorAll<HTMLElement>(`[${SKILL_MENTION_DATA_ATTR}]`))
        .map(el => el.getAttribute(SKILL_MENTION_DATA_ATTR)!),
    )
    const nextSkills = skillMentions.filter(m => presentSkills.has(m))
    if (nextSkills.length !== skillMentions.length) onSkillMentionsChange(nextSkills)

    setIsEmpty(isEditorEmpty(container))
  }

  // Splices `chip` in place of the just-typed "@token"/"/token" span and leaves the caret right
  // after its auto-inserted trailing space - shared by selectMention/selectSkill below, which only
  // differ in how the chip itself is built (see createMentionChip/createSkillChip).
  function insertChipAndAdvanceCaret(info: NonNullable<ReturnType<typeof getCaretMentionQuery>>, chip: HTMLSpanElement) {
    const range = document.createRange()
    range.setStart(info.textNode, info.atOffset)
    range.setEnd(info.textNode, info.caretOffset)
    range.deleteContents()
    range.insertNode(chip)
    const space = document.createTextNode(' ')
    chip.after(space)

    // insertNode() on a range collapsed inside a Text node splits that node at the split point,
    // even when one or both resulting halves are empty (typically true here, since the range just
    // had its "@query"/"/query" contents deleted) - remove any such empty-string artifacts so the
    // DOM doesn't accumulate stray zero-length text nodes (which would make later
    // `childNodes.length === 0` "is the editor empty" checks never true again once any mention has
    // ever been inserted).
    if (chip.previousSibling?.nodeType === Node.TEXT_NODE && chip.previousSibling.textContent === '') {
      chip.previousSibling.remove()
    }
    if (space.nextSibling?.nodeType === Node.TEXT_NODE && space.nextSibling.textContent === '') {
      space.nextSibling.remove()
    }

    const sel = window.getSelection()
    if (sel) {
      const caretRange = document.createRange()
      caretRange.setStart(space, 1)
      caretRange.collapse(true)
      sel.removeAllRanges()
      sel.addRange(caretRange)
    }
  }

  function selectMention(suggestion: ConnectorConfig) {
    const container = editorRef.current
    if (!container) return
    const info = getCaretMentionQuery(container)
    if (!info || info.trigger !== '@') return
    nameToTypeRef.current.set(suggestion.name, suggestion.type)
    const chip = createMentionChip(suggestion.name, suggestion.type, rootsRef.current)
    insertChipAndAdvanceCaret(info, chip)
    if (!mentions.includes(suggestion.name)) onMentionsChange([...mentions, suggestion.name])
    setPending(null)
    handleContentChanged()
    container.focus()
  }

  function selectSlashItem(item: SlashItem) {
    const container = editorRef.current
    if (!container) return
    const info = getCaretMentionQuery(container)
    if (!info || info.trigger !== '/') return
    const chip = createSkillChip(item.name, rootsRef.current)
    insertChipAndAdvanceCaret(info, chip)
    if (!skillMentions.includes(item.name)) onSkillMentionsChange([...skillMentions, item.name])
    setPending(null)
    handleContentChanged()
    container.focus()
  }

  function handleInput() {
    handleContentChanged()
    const container = editorRef.current
    const info = container ? getCaretMentionQuery(container) : null
    setPending(info ? { trigger: info.trigger, query: info.query } : null)
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLDivElement>) {
    // AN INPUT METHOD STILL COMPOSING owns the keys (CHAT-01): the Enter that commits a candidate
    // (Japanese, Chinese, Korean, macOS Vietnamese Telex) arrives here too, flagged as composing,
    // and it sent a half-typed message or put a mention in mid-word. 229 is what some browsers
    // report for a key the IME took.
    if (e.nativeEvent.isComposing || e.keyCode === 229) return
    if (pending !== null && suggestionCount > 0) {
      if (e.key === 'ArrowDown') { e.preventDefault(); setActiveIndex(i => (i + 1) % suggestionCount); return }
      if (e.key === 'ArrowUp') { e.preventDefault(); setActiveIndex(i => (i - 1 + suggestionCount) % suggestionCount); return }
      if ((e.key === 'Enter' || e.key === 'Tab') && !e.shiftKey) {
        e.preventDefault()
        if (pending.trigger === '@') selectMention(docSuggestions[activeIndex])
        else selectSlashItem(slashSuggestions[activeIndex])
        return
      }
      if (e.key === 'Escape') { e.preventDefault(); setPending(null); return }
    }
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onSend(); return }
    if (e.key === 'Enter' && e.shiftKey) {
      // Forces a soft <br> line break rather than contentEditable's default (a new block-level
      // <div> per line in most browsers), which would break serialize()'s flat text/chip/<br>
      // model. execCommand is deprecated but still broadly supported and remains the simplest way
      // to do this without hand-rolling Range/Selection surgery for a rare, low-risk edit op.
      e.preventDefault()
      document.execCommand('insertLineBreak')
      handleContentChanged()
      return
    }
    if (e.key === 'Backspace' && collapseTrailingMentionSpace(editorRef.current)) {
      e.preventDefault()
      handleContentChanged()
    }
  }

  function handlePaste(e: React.ClipboardEvent<HTMLDivElement>) {
    // Plain text only - matches the old <textarea>'s behavior (it physically can't accept rich
    // HTML either) and avoids pasted bold/links/divs from e.g. Confluence/Word corrupting the
    // flat text/chip/<br> structure serialize() assumes.
    e.preventDefault()
    document.execCommand('insertText', false, e.clipboardData.getData('text/plain'))
  }

  // A row takes its press on mousedown's DEFAULT being refused (the wrapper below): the editor
  // keeps focus and its caret, which selectMention / selectSlashItem read to know what to replace.
  const keepCaret = (e: React.MouseEvent) => e.preventDefault()

  return (
    // The suggestions are the kit Popover, anchored to the editor, opening upward (the composer
    // sits at the bottom of the window) and portaled - it was an absolutely placed panel inside the
    // composer, with its own outside-click and none of the menus' Escape contract.
    <Popover
      open={pending !== null}
      onOpenChange={open => { if (!open) setPending(null) }}
      placement="top-start"
      widthClass="w-80"
      panelClassName="max-h-60"
      closeOnScroll={false}
      role="listbox"
      menuKeys={false}
      ariaLabel={pending?.trigger === '/' ? t('Skills') : t('Sources')}
      trigger={({ ref }) => (
        <div ref={ref} className="relative min-w-0">
          {/* Always in the page: it fades back in when the editor empties, and is gone the moment a
              character is typed - a placeholder lingering over the first letters reads as a glitch. */}
          {placeholder && (
            <span
              aria-hidden="true"
              className={cn(
                'pointer-events-none absolute left-1 top-1/2 -translate-y-1/2 text-sm text-ink-disabled truncate max-w-full select-none text-left',
                isEmpty ? 'opacity-100 transition-opacity duration-base' : 'opacity-0',
              )}
            >
              {placeholder}
            </span>
          )}
          <div
            ref={editorRef}
            contentEditable
            suppressContentEditableWarning
            role="textbox"
            aria-multiline="true"
            aria-label={placeholder}
            // A multi-line text box that suggests as it is typed into (ARIA's textbox takes both of
            // these; a combobox is single-line by definition).
            aria-autocomplete="list"
            aria-activedescendant={pending !== null && suggestionCount > 0 ? optionId(activeIndex) : undefined}
            onInput={handleInput}
            onKeyDown={handleKeyDown}
            onPaste={handlePaste}
            // max-h: the box grows with what is typed up to about 8-9 lines, then scrolls inside, so
            // a long pasted paragraph does not push Send off the page.
            className="min-w-0 max-h-[12.5rem] py-3 pl-1 leading-5 text-sm font-ui text-ink-primary bg-transparent outline-none whitespace-pre-wrap break-words overflow-y-auto overflow-x-hidden text-left"
          />
        </div>
      )}
    >
      {() => (
        <div ref={suggestionListRef} onMouseDown={keepCaret}>
          <MenuGroup>
            {pending?.trigger === '@' ? (
              docSuggestions.length === 0 ? (
                <MenuEmpty>{t('No matching sources')}</MenuEmpty>
              ) : docSuggestions.map((r, i) => {
                const Icon = mentionIcon(r.type)
                return (
                  <MenuRow
                    key={r.id}
                    role="option"
                    tone={i === activeIndex ? 'active' : 'default'}
                    // The type's mark, in the row's caption ink as every mark in a menu is.
                    icon={<Icon />}
                    title={r.name}
                    trailing={r.shared && !r.ownedByMe ? <Caption as="span" className="shrink-0">{t('Shared')}</Caption> : undefined}
                    onSelect={() => selectMention(r)}
                  />
                )
              })
            ) : (
              slashSuggestions.length === 0 ? (
                <MenuEmpty>{t('No matching skills or workflows')}</MenuEmpty>
              ) : slashSuggestions.map((item, i) => (
                <Tooltip key={`${item.kind}:${item.id}`} label={item.shortDescription || item.name} fullWidth>
                  {/* The icon is the badge. A workflow's step count sits opposite it, which says
                      the one thing a skill can never say - how many turns picking this will cost -
                      and does it before the reader commits rather than after the third row appears. */}
                  <MenuRow
                    role="option"
                    tone={i === activeIndex ? 'active' : 'default'}
                    icon={item.kind === 'workflow' ? <ListChecks /> : <FileText />}
                    title={item.name}
                    trailing={item.kind === 'workflow' ? (
                      <Readout tone="disabled" className="shrink-0 text-xs animate-fade-in">
                        {t('{count} step|{count} steps', { count: item.stepCount ?? 0 })}
                      </Readout>
                    ) : undefined}
                    onSelect={() => selectSlashItem(item)}
                  />
                </Tooltip>
              ))
            )}
          </MenuGroup>
        </div>
      )}
    </Popover>
  )
}
