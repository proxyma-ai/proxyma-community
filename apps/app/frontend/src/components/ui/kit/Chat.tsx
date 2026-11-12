/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Chat.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useState, type ComponentType, type ReactNode, type UIEvent } from 'react'
import { ChevronDown, Square } from 'lucide-react'
import { cardClass } from './Card'
import { cn } from './cn'
import { Collapse, DisclosureChevron } from './Disclosure'
import { iconButton } from './iconButton'
import { Logo } from './Logo'
import { Presence } from './Presence'
import { ProxymaIcon } from './ProxymaIcon'
import { ChoiceMark } from './RadioGroup'
import { Skeleton } from './Skeleton'
import { StateMark, type WorkState } from './StateMark'
import { PageHero } from './Text'
import { Tooltip } from './Tooltip'
import { TEXT, textLink } from './typeScale'
import { workingMotion } from './working'

/**
 * THE CHAT THREAD'S PARTS, presentational. The app's chat feeds them its stores and streams; the
 * landing site draws its product scenes from the SAME parts with static props, so the picture on
 * proxyma.ai cannot drift from the product again - its replica `ToolMark`, sub-agent panel and
 * approval card had each been kept alike by reading the app's source, and the sub-agent panel had
 * already drifted ("Background" a badge there, a dimmed word in the app since 0.27.2).
 */

/** The thread's column - one width for the thread, the composer, the pinned question and the
 *  new-chat screen. It was typed out six times. A READING column since 2026-10-28 (the UI review
 *  measured 99-117 characters a line at 768px): 720px, about 70 characters of the 16px body, at
 *  every width - centered inside the page column (`PAGE_COLUMN`), so it shares the header's center
 *  line without being as wide as it. */
export const THREAD_COLUMN = 'max-w-[45rem] mx-auto w-full'

type Glyph = ComponentType<{ size?: number | string; className?: string }>

/**
 * One message. A user's is a BUBBLE at the column's RIGHT, its text left-aligned inside it (the
 * maintainer, 2026-10-31: "user message bubble aligned on the right, its text aligned on the left of
 * the bubble"), at most 78% wide, in primary ink on `bg-lift` - one step up from the plane it sits on,
 * so it is continuous on the page and in a panel alike. The assistant's runs the column's full width
 * with no bubble, in secondary ink; an error is danger ink. `actions` is the row under it (copy,
 * rewind, rate), at the message's own side, which always takes its height and only fades its buttons
 * in under a mouse - so nothing in the thread moves under the pointer - unless `actionsShown`: the
 * latest question and the latest answer keep theirs in view (2026-10-30). Always shown on a touch screen.
 */
export function ChatMessage({ role, children, footer, actions, actionsShown, queued, error, className }: {
  role: 'user' | 'assistant'
  children: ReactNode
  /** Under the text: attachments, citations, a queued note, a retry link. */
  footer?: ReactNode
  actions?: ReactNode
  /** The actions stay in view rather than fading in under the pointer - the latest of each side. */
  actionsShown?: boolean
  queued?: boolean
  error?: boolean
  className?: string
}) {
  const user = role === 'user'
  return (
    <div className={cn('flex mb-1 group', user ? 'justify-end' : 'justify-start', className)}>
      <div className={cn(
        // A queued message brightens as the agent takes it, rather than in one frame.
        // The thread's reading size, TEXT.body (PROSE_CHAT) - a question as much as an answer.
        'text-base transition-opacity duration-base',
        user ? 'max-w-[78%] min-w-0 flex flex-col items-end pt-2' : 'w-full py-2',
        user ? cn('text-ink-primary', queued && 'opacity-60') : error ? 'text-danger' : 'text-ink-secondary',
      )}>
        {user
          ? <div className="rounded-lg bg-lift px-3 py-2 text-left break-words">{children}</div>
          : children}
        {footer}
        {actions && (
          <div data-message-actions="" className={cn(!actionsShown && 'mouse:opacity-0 mouse:group-hover:opacity-100 focus-within:opacity-100', 'transition-opacity duration-fast')}>
            <div className={cn('flex items-center gap-1 pt-1 min-h-7', user ? 'justify-end' : 'justify-start')}>{actions}</div>
          </div>
        )}
      </div>
    </div>
  )
}

/** How long a piece of work took, after its words - disabled ink, figures. */
export function Elapsed({ children }: { children?: ReactNode }) {
  if (children === undefined || children === null || children === '') return null
  return <span className="text-ink-disabled font-normal data-readout shrink-0">{children}</span>
}

/**
 * ONE LINE OF ACTIVITY: a mark, the words (a wave across them while it runs - working.ts), how
 * long it took, and - for a group that opens - the chevron at the end, so the line reads as a
 * sentence with its affordance last. The thinking line, the waiting-for-the-model line, a tool
 * group's header and a sub-agent's working line are all this.
 */
export function ActivityLine({ icon, state = 'done', label, note, noteTone = 'warning', elapsed, open, onToggle, tone = 'caption', maxWidth = 'max-w-[420px]', className }: {
  icon?: Glyph
  state?: WorkState
  label: ReactNode
  /** A short toned word after the label - "retry 2" while the model is being asked again. */
  note?: ReactNode
  noteTone?: 'warning' | 'danger' | 'caption'
  elapsed?: ReactNode
  /** Set for a line that opens what is under it. */
  open?: boolean
  onToggle?: () => void
  /** `warning` for "Waiting for you". */
  tone?: 'caption' | 'warning'
  maxWidth?: string
  className?: string
}) {
  const running = state === 'running'
  const body = (
    <>
      {icon && <StateMark icon={icon} state={state} blink={false} />}
      <span className={cn('truncate', maxWidth, tone === 'warning' && 'text-warning', workingMotion('text', running))}>{label}</span>
      {note && (
        <span className={cn('shrink-0 data-readout', noteTone === 'warning' && 'text-warning', noteTone === 'danger' && 'text-danger', noteTone === 'caption' && 'text-ink-caption')}>{note}</span>
      )}
      <Elapsed>{elapsed}</Elapsed>
      {onToggle && <DisclosureChevron open={!!open} />}
    </>
  )
  const cls = cn('flex items-center gap-2 max-w-full min-w-0 text-xs text-ink-caption', className)
  return onToggle
    ? <button type="button" onClick={onToggle} aria-expanded={!!open} className={cn(cls, 'mouse:hover:text-ink-primary transition-colors focus-ring rounded-sm')}>{body}</button>
    : <div className={cls}>{body}</div>
}

/**
 * ONE TOOL CALL: its mark (what it is, and by colour how it stands), its words (a button that opens
 * the call's detail, underlined under a mouse once done, a wave across them while it runs), and
 * the time. A failed call shows the start of its error on the same ONE line, in danger ink, and the
 * whole of it behind the same button - the call's detail (the UI review, 2026-10-28: a guessed
 * argument's error was four red lines inline, louder than the answer under it).
 */
export function ToolCallRow({ icon, state, label, error, elapsed, onOpen, outcome, className }: {
  icon: Glyph
  state: WorkState
  label: ReactNode
  error?: ReactNode
  elapsed?: ReactNode
  onOpen?: () => void
  /** Which way an approval went, once sent ("Approved", "Denied"). */
  outcome?: ReactNode
  className?: string
}) {
  const failed = state === 'failed'
  const text = failed ? (
    <span className="block truncate max-w-full">
      <span className="font-semibold text-ink-caption">{label}</span>{' '}
      <span className="text-danger">{error}</span>
    </span>
  ) : (
    // The wave on the words' own element, which carries their ink.
    <span className={cn('block truncate max-w-[420px] text-ink-caption', workingMotion('text', state === 'running'))}>{label}</span>
  )
  return (
    <div className={cn('flex items-start gap-2 text-xs min-w-0', className)}>
      <StateMark icon={icon} state={state} blink={false} />
      {onOpen ? (
        <button
          type="button"
          onClick={onOpen}
          className={cn('min-w-0 max-w-full text-left transition-colors focus-ring rounded-sm', state !== 'running' && 'mouse:hover:underline underline-offset-2')}
        >
          {text}
        </button>
      ) : <span className="min-w-0">{text}</span>}
      <Elapsed>{elapsed}</Elapsed>
      {outcome}
    </div>
  )
}

/**
 * WORK AS IT HAPPENS, IN A BOX OF ITS OWN: a tool list, a sub-agent's steps. At most FIVE ROWS tall
 * (a row is 18px and the gap 4px - measured in Edge, 106px), scrolling inside itself, so however much arrives the thread
 * around it moves by that ceiling once (maintainer's 0.29.2 feedback). Once anything is scrolled
 * off its top, the top fades out - the pinned question's fade, as a mask so it needs no knowledge
 * of the background (a panel's surface, the page's canvas).
 *
 * Which line it shows is the caller's: the app follows the newest one by the thread's own rule.
 */
export function ActivityFeed({ children, boxRef, onScroll, className }: {
  children: ReactNode
  boxRef?: (el: HTMLDivElement | null) => void
  onScroll?: (e: UIEvent<HTMLDivElement>) => void
  className?: string
}) {
  const [cut, setCut] = useState(false)
  return (
    <div
      ref={boxRef}
      onScroll={e => { setCut(e.currentTarget.scrollTop > 0); onScroll?.(e) }}
      data-cut-start={cut || undefined}
      className={cn('feed-fade max-h-[6.625rem] overflow-y-auto space-y-1', className)}
    >
      {children}
    </div>
  )
}

/**
 * A RUN OF TOOL CALLS (maintainer's request, 2026-10-13). Each row says whether it is drawn
 * (`shown`, from toolFold): folded, the calls still running - all of them, however many run in
 * parallel - and, once none is, the last call alone; opened, every call. A row comes and goes by
 * folding (Collapse), so the list closes up call by call as the calls finish.
 *
 * The chevron sits beside the last row drawn, pointing down to open and up to fold, and is drawn
 * only while folding hides something (`onToggle` absent otherwise). Nothing here scrolls: it was a
 * box five rows tall that did, and a burst of parallel calls showed three of them under a fade.
 *
 * Each row is a ToolCallRow, or the app's own row around it. Which calls are in the group at all
 * (a call waiting on an approval is drawn under it instead) is the caller's.
 */
export function ToolCallGroup({ rows, open, onToggle, toggleLabels }: {
  rows: { key: string; node: ReactNode; shown: boolean }[]
  open: boolean
  /** Absent while folding would hide nothing. */
  onToggle?: () => void
  toggleLabels?: { show: string; hide: string }
}) {
  let lastShown = -1
  rows.forEach((r, i) => { if (r.shown) lastShown = i })
  // The gap is each row's own bottom padding, inside its fold, so a folded row leaves no gap behind;
  // the group takes the last one back.
  return (
    <div className="-mb-1">
      {rows.map((r, i) => (
        <Collapse key={r.key} open={r.shown} className="pb-1">
          <div className="flex items-start gap-2 min-w-0">
            <div className="min-w-0">{r.node}</div>
            {onToggle && i === lastShown && (
              <button
                type="button"
                onClick={onToggle}
                aria-expanded={open}
                aria-label={open ? toggleLabels?.hide : toggleLabels?.show}
                className="shrink-0 h-4 flex items-center text-ink-caption mouse:hover:text-ink-primary transition-colors focus-ring rounded-sm animate-fade-in"
              >
                <ChevronDown size={14} aria-hidden="true" className={cn('transition-transform duration-fast ease-in-out', open && 'rotate-180')} />
              </button>
            )}
          </div>
        </Collapse>
      ))}
    </div>
  )
}

/**
 * AN AGENT'S PANEL: the agent's mark coloured by how it stands, its name (which opens what it was
 * asked, with a wave across it while it works), a dimmed WORD beside it ("Background", or where it
 * stands), the time, the caller's own `actions` (the app's Open, Pause and Stop), a red Stop while
 * it runs, and the chevron. The body is its steps or report, capped and scrolling inside.
 *
 * `highlight` lights the box up once - its border and a faint tint of the brand easing in and
 * fading out (`.animate-reveal`) - for a panel the reader was just sent to (the app's agents map).
 */
export function SubAgentPanel({ icon, title, state, backgroundLabel, elapsed, actions, onOpenTitle, onStop, stopLabel, stopping, open, highlight, onToggle, toggleLabels, children, bodyRef, onBodyScroll }: {
  icon: Glyph
  title: ReactNode
  state: WorkState
  /** "Background", for an agent the main one did not wait for. */
  backgroundLabel?: string
  elapsed?: ReactNode
  /** Icon buttons of the caller's, before the Stop and the chevron - each an iconButton('sm') with its Tooltip. */
  actions?: ReactNode
  onOpenTitle?: () => void
  onStop?: () => void
  stopLabel?: string
  stopping?: boolean
  open: boolean
  /** Lit up once, as a place the reader was just sent to. */
  highlight?: boolean
  onToggle?: () => void
  toggleLabels?: { show: string; hide: string }
  children?: ReactNode
  bodyRef?: (el: HTMLDivElement | null) => void
  onBodyScroll?: (e: UIEvent<HTMLDivElement>) => void
}) {
  const running = state === 'running'
  return (
    // A LINE OF THE THREAD'S ACTIVITY, not a box (since 2026-10-28, the UI review: the panel was a
    // bordered card, its report a caption line and the mail line a third style). The mark, the
    // words and the time are a tool row's and a mail line's; only the controls are its own. Its
    // report folds out under it on a hairline, as an answer's sources do.
    <div className={cn('rounded-md', highlight && 'animate-reveal')}>
      <div className="flex items-center gap-2 min-h-6 text-xs">
        <StateMark icon={icon} state={state} blink={false} />
        {onOpenTitle ? (
          <button type="button" onClick={onOpenTitle} className={cn('min-w-0 truncate text-left font-medium text-ink-caption mouse:hover:text-ink-primary mouse:hover:underline underline-offset-2 transition-colors focus-ring rounded-sm', workingMotion('text', running))}>
            {title}
          </button>
        ) : <span className={cn('min-w-0 truncate font-medium text-ink-caption', workingMotion('text', running))}>{title}</span>}
        {backgroundLabel && <span className="shrink-0 text-ink-disabled">{backgroundLabel}</span>}
        <Elapsed>{elapsed}</Elapsed>
        <span className="flex-1" />
        {actions && <span className="shrink-0 flex items-center animate-fade-in">{actions}</span>}
        {running && onStop && (
          <Tooltip label={stopLabel ?? ''}>
            <button type="button" onClick={onStop} disabled={stopping} aria-label={stopLabel} className={iconButton('sm', 'danger')}>
              <Square size={12} aria-hidden="true" />
            </button>
          </Tooltip>
        )}
        {onToggle && (
          <button type="button" onClick={onToggle} aria-expanded={open} aria-label={open ? toggleLabels?.hide : toggleLabels?.show} className={iconButton('sm')}>
            <DisclosureChevron open={open} />
          </button>
        )}
      </div>
      {/* Five rows and its padding, like a tool list (ActivityFeed) - it was 256px until the
          0.29.2 feedback asked for one ceiling for both. */}
      {children && (
        <Collapse open={open}>
          <ActivityFeed boxRef={bodyRef} onScroll={onBodyScroll} className="max-h-[7.625rem] ml-2 mt-1 mb-2 border-l-2 border-line/40 pl-3 py-1">
            {children}
          </ActivityFeed>
        </Collapse>
      )}
    </div>
  )
}

/**
 * A DECISION THE TURN IS WAITING ON - approve a write, answer a question, go on past the step
 * limit. A 16px glyph in its tone beside the title; the body (arguments, choices); and the actions
 * RIGHT-aligned, secondary then primary, the way every dialog footer runs - the approval card had
 * them left under an indent with the primary first. A status ("Denied automatically in 0:42",
 * "Sending your answer") sits at the left of the action row. Deny is `danger-quiet`, last.
 */
export function PromptCard({ icon: Icon, tone = 'brand', eyebrow, title, onTitleClick, caption, children, actions, status, busy, ariaLabel }: {
  icon: Glyph
  tone?: 'brand' | 'warning'
  eyebrow?: ReactNode
  title: ReactNode
  onTitleClick?: () => void
  caption?: ReactNode
  children?: ReactNode
  actions?: ReactNode
  status?: ReactNode
  busy?: boolean
  ariaLabel: string
}) {
  return (
    <div role="group" aria-label={ariaLabel} aria-busy={busy || undefined} className={cardClass({ padding: 'none', className: 'px-4 py-3 space-y-3' })}>
      <div className="flex items-start gap-3">
        <Icon size={16} className={cn('shrink-0 mt-0.5', tone === 'warning' ? 'text-warning' : 'text-brand-legible')} aria-hidden="true" />
        <div className="min-w-0 flex-1">
          {eyebrow && <p className="text-xs text-ink-caption mb-0.5">{eyebrow}</p>}
          {onTitleClick ? (
            <button type="button" onClick={onTitleClick} className="min-w-0 text-left text-sm font-medium text-ink-primary break-words mouse:hover:underline underline-offset-2 focus-ring rounded-sm">
              {title}
            </button>
          ) : <p className="text-sm font-medium text-ink-primary break-words">{title}</p>}
          {caption && <p className="text-xs text-ink-caption mt-1 break-words">{caption}</p>}
        </div>
      </div>
      {children && <div className="pl-7">{children}</div>}
      {(actions || status) && (
        <div className="flex flex-wrap items-center justify-end gap-x-3 gap-y-2">
          {status && <span className="mr-auto flex items-center gap-2 text-xs text-ink-caption">{status}</span>}
          {actions}
        </div>
      )}
    </div>
  )
}

/**
 * A FURTHER QUESTION IN THE SAME CARD - askUser may ask several at once, answered by one Send. The
 * first is the card's own title; each one after it is this: its eyebrow, the question at the
 * title's weight, a caption, then its choices.
 */
export function PromptSection({ eyebrow, title, caption, children }: {
  eyebrow?: ReactNode
  title: ReactNode
  caption?: ReactNode
  children: ReactNode
}) {
  return (
    <div className="space-y-2">
      <div>
        {eyebrow && <p className="text-xs text-ink-caption mb-0.5">{eyebrow}</p>}
        <p className="text-sm font-medium text-ink-primary break-words">{title}</p>
        {caption && <p className="text-xs text-ink-caption mt-1">{caption}</p>}
      </div>
      {children}
    </div>
  )
}

/**
 * ONE ANSWER TO A QUESTION: its mark (round for one, square for several), the label and a line
 * describing it. `children` puts a free-text field in the row ("Other - type your own").
 */
export function ChoiceRow({ checked, multi, label, description, disabled, onToggle, children, ariaLabel }: {
  checked: boolean
  multi?: boolean
  label?: ReactNode
  description?: ReactNode
  disabled?: boolean
  onToggle: () => void
  children?: ReactNode
  ariaLabel?: string
}) {
  const box = cn(
    // Chosen: the brand's border and mark on the neutral selected fill - never a teal fill.
    'w-full flex items-start gap-3 rounded-md border px-3 py-2 text-left transition-colors',
    checked ? 'border-brand-solid bg-surface-alt' : 'border-line/60 mouse:hover:border-line',
    disabled && 'opacity-60 cursor-default',
  )
  if (children) {
    return (
      // The field inside draws no ring of its own - in a row this tight it landed on the row's border
      // (the maintainer, 2026-11-04) - so the ROW shows where focus is: its border takes the brand.
      <div className={cn(box, 'items-center py-1 focus-within:border-brand-solid')}>
        <button type="button" role={multi ? 'checkbox' : 'radio'} aria-checked={checked} aria-label={ariaLabel} disabled={disabled} onClick={onToggle} className="focus-ring rounded-sm">
          <ChoiceMark checked={checked} multi={multi} />
        </button>
        {children}
      </div>
    )
  }
  return (
    <button type="button" role={multi ? 'checkbox' : 'radio'} aria-checked={checked} disabled={disabled} onClick={onToggle} className={cn(box, 'focus-ring')}>
      <ChoiceMark checked={checked} multi={multi} className="mt-0.5" />
      <span className="min-w-0">
        <span className="block text-sm text-ink-primary">{label}</span>
        {description && <span className="block text-xs text-ink-caption mt-0.5">{description}</span>}
      </span>
    </button>
  )
}

/**
 * THE PRODUCT DOING WHAT IT WAS SET UP TO DO - a guardrail block, a step limit reached. Quiet, no
 * Retry: the same box as a `PromptCard` (`rounded-lg px-4 py-3`; these were `rounded-md px-3`),
 * the glyph in caption ink. `inline` is a one-line note under a message (a redaction).
 */
export function NoticeCard({ icon: Icon, title, caption, children, inline }: {
  icon: Glyph
  title: ReactNode
  caption?: ReactNode
  children?: ReactNode
  inline?: boolean
}) {
  if (inline) {
    return (
      <p className="mt-2 flex items-start gap-2 text-xs text-ink-caption">
        <Icon size={14} className="shrink-0 mt-0.5" aria-hidden="true" />
        <span>{title}</span>
      </p>
    )
  }
  return (
    <div className={cardClass({ padding: 'none', className: 'px-4 py-3' })}>
      <div className="flex items-start gap-3">
        <Icon size={16} className="shrink-0 mt-0.5 text-ink-caption" aria-hidden="true" />
        <div className="min-w-0">
          <p className="text-sm font-medium text-ink-primary">{title}</p>
          {caption && <p className="text-xs text-ink-caption mt-1">{caption}</p>}
          {children && <div className="mt-2">{children}</div>}
        </div>
      </div>
    </div>
  )
}

/**
 * The composer's light on a send - keyed by the send, so each runs from the start: one light charges at
 * the top-left corner and shoots along the top edge to the top-right one, fading as it goes. Nothing
 * before the first send.
 */
function ComposerLights({ pulse }: { pulse: number }) {
  if (pulse <= 0) return null
  return <span key={`run-${pulse}`} className="composer-spot" />
}

/** A press on the frame outside any control puts the caret in the editor, at its end. */
function focusEditor(e: React.MouseEvent<HTMLDivElement>) {
  const target = e.target as HTMLElement
  if (target.closest('button, a, input, textarea, select, label, [contenteditable="true"], [role="button"], [role="menu"], [role="listbox"]')) return
  const editor = e.currentTarget.querySelector<HTMLElement>('[contenteditable="true"], textarea')
  if (!editor) return
  e.preventDefault()
  editor.focus()
  if (editor.isContentEditable) {
    const range = document.createRange()
    range.selectNodeContents(editor)
    range.collapse(false)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
  }
}

/**
 * THE PROMPT BOX's frame: attachments over the editor, the editor, and a toolbar of controls
 * at the start and the end. `rounded-lg`, the card radius. A raised card (the float shadow,
 * `.composer-sweep` in proxyma-primitives.css) with no border and no light at rest or focused; each
 * send charges a light at the top-left corner and shoots it along the top edge to the top-right - see `.composer-light`. A press anywhere on the frame that is not a control puts the caret in the
 * editor, so the whole box reads as one text field.
 */
export function ComposerShell({ attachments, editor, toolbarStart, toolbarEnd, working, lit = true, alwaysLit, pulse = 0, className, after }: {
  attachments?: ReactNode
  editor: ReactNode
  toolbarStart?: ReactNode
  toolbarEnd?: ReactNode
  working?: boolean
  /** Whether the light is wanted at all (a picture of the composer may not want it turning). */
  lit?: boolean
  /**
   * Lit without focus. The app lights it on `:focus-within`; a picture of the composer has no
   * focus, so the site's scenes say so here (`.is-lit` in proxyma-primitives.css).
   */
  alwaysLit?: boolean
  /**
   * A count of the messages sent: each new value runs the light once along the top edge, from the
   * top-left corner to the top-right one (0, the default, runs nothing).
   */
  pulse?: number
  className?: string
  /** Outside the frame, inside the light's box - a hidden file input. */
  after?: ReactNode
}) {
  return (
    <div className={cn('relative', lit && 'composer-light', lit && working && 'is-working', lit && alwaysLit && 'is-lit', className)}>
      {/* The one light, drawn twice: blurred behind the frame (the halo) and sharp in a band on its edge. */}
      {lit && <span aria-hidden="true" className="composer-halo"><ComposerLights pulse={pulse} /></span>}
      {lit && <span aria-hidden="true" className="composer-band"><ComposerLights pulse={pulse} /></span>}
      <div className="relative flex flex-col rounded-lg border surface-float focus-border composer-sweep" onMouseDown={focusEditor}>
        {attachments && <div className="flex flex-wrap gap-2 px-4 pt-3">{attachments}</div>}
        <div className="px-4 pt-3 pb-1 short:pt-2">{editor}</div>
        <div className="flex items-center justify-between gap-2 px-2 pb-2 short:pb-1">
          <div className="flex items-center gap-1 min-w-0 flex-1">{toolbarStart}</div>
          <div className="flex items-center gap-1 shrink-0">{toolbarEnd}</div>
        </div>
      </div>
      {after}
    </div>
  )
}

/**
 * THE NEW CHAT'S HERO. Two headings, by edition (maintainer's review, 2026-10-15):
 *
 * - COMMUNITY (Desktop, Home Server): the kit `Logo`, the mark with the name BESIDE it, centered
 *   (since 2026-11-09, when the app went back to its centered new chat - the maintainer: "Bring the
 *   original chat interface back with the Proxyma logo and Proxyma title on the right of the logo
 *   instead of below the logo"). It was the stacked lockup until 2026-11-08, then for a day the
 *   wordmark alone on the stage; `onStage` still draws that, for the stage's own pictures.
 * - ENTERPRISE SERVER: `greeting` - "Hi, {name}" for a signed-in person, the question for a guest. A
 *   signed-in person is greeted by name, not shown the product they already chose.
 *
 * `onStage` (the new chat, on `ChatStartStage`): left-aligned, as the draft sets its words left of
 * the mark - the wordmark at the headline's size (36px on a phone, 48px, 64px on a wide pane via
 * `.chat-stage-title`), the greeting a step under it in semibold secondary ink (the maintainer found
 * the greeting "too harsh" in primary ink, 2026-10-29), the fact a lead paragraph. Off the stage - the app's new chat since
 * 2026-11-09 - it is centered.
 *
 * Under either, the astronomy fact when the reader has it on. The heading is the page's heading
 * (`as`; `p` in a picture of the page on the site) and rises in on mount. The fact arrives late (a
 * query) and eases in under it with `Presence`, so nothing below jumps; its own markup decides its
 * alignment. The space from here to the composer is the page's, not the hero's.
 */
export function ChatStartHero({ fact, greeting, as: As = 'h1', productName = 'Proxyma', onStage = false, className }: {
  /** The astronomy fact, with its source linked; absent while it is off or not yet loaded. */
  fact?: ReactNode
  /** Enterprise Server's greeting, drawn instead of the name. */
  greeting?: ReactNode
  as?: 'h1' | 'h2' | 'p'
  productName?: string
  /** Drawn on the new chat's stage: left-aligned, at the headline's size. */
  onStage?: boolean
  className?: string
}) {
  return (
    <div className={cn('flex flex-col', onStage ? 'items-start text-left' : 'items-center', className)}>
      {greeting ? (
        onStage ? (
          <As className="text-3xl sm:text-4xl font-semibold text-ink-secondary text-balance animate-fade-up">{greeting}</As>
        ) : (
          <PageHero as={As} centered title={greeting} className="w-full animate-fade-up" />
        )
      ) : onStage ? (
        // The wordmark's own rules (Logo.tsx): Orbitron 700, tracking 0.05em, brand-legible ink,
        // never upper case.
        <As className="font-brand font-bold text-brand-legible tracking-wider animate-fade-up text-4xl sm:text-5xl chat-stage-title">
          {productName}
        </As>
      ) : (
        // THE LOGO WITH THE NAME BESIDE IT (the maintainer, 2026-11-09: "Bring the original chat
        // interface back with the Proxyma logo and Proxyma title on the right of the logo instead of
        // below the logo"): the horizontal lockup every other surface uses, at the hero's size - a
        // 56px mark beside the 30px name (Logo sizes the name from the mark), the mark turning as
        // every mark does.
        <As className="flex justify-center animate-fade-up">
          <Logo size={HERO_MARK} productName={productName} />
        </As>
      )}
      <Presence show={!!fact} className="w-full">
        {onStage ? (
          <div className={cn('pt-4 short:pt-2', TEXT.lead)}>{fact}</div>
        ) : (
          // The greeting's own bottom margin is its space to the fact, as it always was.
          <div className={cn('sm:max-w-md mx-auto', !greeting && 'pt-6 short:pt-3', TEXT.body)}>{fact}</div>
        )}
      </Presence>
    </div>
  )
}

// The hero's mark: 56px beside the 30px name - about the header's own ratio (28 beside 16).
const HERO_MARK = 56

/**
 * THE NEW CHAT'S STAGE (since 2026-11-08 - the maintainer: "based on shot-b-dark.png, redesign the
 * chat home as well", the landing hero's "B - Stage"; reshaped the same day: "Light must be real
 * light", and the draft's rounded screen is "the entire screen", not a card). The stage IS the chat
 * pane - its full width and height, no margin, corner, border or background of its own - and its
 * layers are drawn on the page sheet in the reader's appearance. Behind the content, aria-hidden:
 *
 * - THE NAME, "PROXYMA", huge in Orbitron across the top, in the primary ink at a few percent and
 *   fading downward - the one place the name is written in capitals, because it is texture here,
 *   not the wordmark;
 * - THE MARK at the pane's right edge, its left half whole and its right half fading out (a mask
 *   from 50% to 100%), in its own colors, turning at the one speed every mark turns at (the kit's
 *   `.mark-turn`, `--mark-turn`; once in two minutes until the maintainer, 2026-11-08: "the same
 *   speed everywhere");
 * - A SOFT WASH of the accent behind the mark.
 *
 * `children` are the content, in the pane's padding: on the left the title (`ChatStartHero
 * onStage`), the fact, the composer, and the links along the bottom (`ChatStartLinks onStage`).
 * Wrap the title, the fact and the composer in `.chat-stage-column`: from an 880px pane it stops
 * 24px short of the mark, which is as large as the room right of a 480px column allows (up to
 * 760px); narrower, the mark is small and faint in the top right corner, behind the text. The pane
 * is a size container (`chat-pane`), so a scaled picture on the site composes as the app does. It
 * fills its parent's column (`flex-1`); `bodyClassName` reaches the content's box.
 */
export function ChatStartStage({ children, className, bodyClassName }: {
  children?: ReactNode
  className?: string
  bodyClassName?: string
}) {
  return (
    <div className={cn('chat-stage-pane', className)}>
      <div aria-hidden="true" className="chat-stage">
        <div className="chat-stage-wash" />
        <div className="chat-stage-word">PROXYMA</div>
        <div className="chat-stage-mark">
          <ProxymaIcon className="chat-stage-mark-turn mark-turn" />
        </div>
      </div>
      <div className={cn('chat-stage-body', bodyClassName)}>{children}</div>
    </div>
  )
}

/**
 * The links of the new-chat screen: the one conditional action first (in the brand ink, with its
 * glyph), then the fixed links in caption ink. Off the stage: under the composer from `sm`, on a
 * phone under the hero, left-aligned on a phone and centered from `sm` - nothing to tap sits in the
 * middle of a phone screen. `onStage`: along the bottom of the stage from `sm` (where the draft
 * has its row of facts), on a phone under the title - left-aligned at every width, at the body
 * size. Text links, the kit's own (`textLink`), never pills: the draft's pill-shaped chips are not
 * Proxyma's shape (the maintainer, 2026-11-08).
 */
export function ChatStartLinks({ primary, links, onStage = false }: {
  primary?: { label: ReactNode; icon?: Glyph; render: (className: string, body: ReactNode) => ReactNode }
  links: { key: string; render: (className: string) => ReactNode }[]
  /** On the new chat's stage: left-aligned, a step larger. */
  onStage?: boolean
}) {
  // The kit's text link (textLink), at the caption size: quiet for the fixed links, the brand's for
  // the one conditional action. They were hand-written at another weight than every other link.
  // On glass each is a 44px target (the UI review measured 18px): the line keeps its look and the
  // box around it grows, so the rows touch rather than leaving a gap.
  const tap = 'inline-flex items-center touch:min-h-tap'
  const size = onStage ? 'text-sm' : 'text-xs'
  const quiet = cn(textLink('quiet'), size, tap)
  const P = primary?.icon
  return (
    <div className={cn(
      'flex flex-wrap items-center gap-y-2 touch:gap-y-0 justify-start',
      onStage ? 'gap-x-6' : 'gap-x-4 sm:justify-center',
    )}>
      {primary && primary.render(
        cn(textLink('brand'), size, tap),
        <>{P && <P size={14} aria-hidden="true" />}{primary.label}</>,
      )}
      {links.map(l => <span key={l.key}>{l.render(quiet)}</span>)}
    </div>
  )
}

/** The fold under an answer that lists where it came from: "4 sources >" over a hairline. */
export function CitationsFold({ label, open, onToggle, children }: { label: ReactNode; open: boolean; onToggle: () => void; children?: ReactNode }) {
  return (
    <div className="mt-3 pt-3 border-t border-line/40">
      <button type="button" onClick={onToggle} aria-expanded={open} className="flex items-center gap-2 text-xs text-ink-caption mouse:hover:text-ink-primary transition-colors focus-ring rounded-sm">
        <span>{label}</span>
        <DisclosureChevron open={open} />
      </button>
      {children && <Collapse open={open} className="mt-2 space-y-1">{children}</Collapse>}
    </div>
  )
}

/** The list of an answer's sources inside `CitationsFold`, hung off a hairline at the left. */
export function CitationList({ children }: { children: ReactNode }) {
  return <ul className="space-y-2 border-l-2 border-line/40 pl-3">{children}</ul>
}

/**
 * ONE SOURCE AN ANSWER CAME FROM, in a `CitationList`: its type's mark, the document's name (a
 * link when the reader can open it - `name` is then the app's link), the type in words and, where
 * the search scored it, how well it matched. The app and the site's scenes drew this list twice.
 */
export function CitationRow({ mark, name, typeLabel, score }: {
  mark?: ReactNode
  /** The document's name - plain text, or the app's link to it. */
  name: ReactNode
  /** The source type in words ("Confluence page") - read out, the mark standing for it on screen. */
  typeLabel?: ReactNode
  /** The match, already formatted ("0.82"). */
  score?: ReactNode
}) {
  return (
    <li className="flex items-center gap-2 min-w-0 text-xs text-ink-caption animate-fade-in">
      {mark && <span className="shrink-0 flex items-center text-ink-disabled [&>svg]:w-3.5 [&>svg]:h-3.5" aria-hidden="true">{mark}</span>}
      {typeLabel && <span className="sr-only">{typeLabel}</span>}
      <span className="min-w-0 truncate">{name}</span>
      {score !== undefined && score !== null && <span className="shrink-0 data-readout text-ink-disabled">{score}</span>}
    </li>
  )
}

/**
 * A MESSAGE STILL LOADING, on `ChatMessage`'s own geometry: a short bubble at the right for the question, three lines of the column's width for the answer - so a conversation that opens does
 * not jump when its words arrive. The thread's placeholder was app-local and matched neither.
 */
export function ChatMessageSkeleton({ role }: { role: 'user' | 'assistant' }) {
  if (role === 'user') {
    return (
      <div className="flex justify-end mb-1 py-2" aria-hidden="true">
        <Skeleton className="h-9 w-48 max-w-[78%] rounded-lg" />
      </div>
    )
  }
  return (
    <div className="mb-1 py-2 space-y-2" aria-hidden="true">
      <Skeleton className="h-3.5 w-full" />
      <Skeleton className="h-3.5 w-11/12" />
      <Skeleton className="h-3.5 w-2/3" />
    </div>
  )
}
