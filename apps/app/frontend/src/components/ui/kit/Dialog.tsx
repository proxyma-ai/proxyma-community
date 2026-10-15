/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Dialog.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ComponentType, HTMLAttributes, ReactNode } from 'react'
import { useCallback, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { AlertTriangle, Trash2 } from 'lucide-react'
import { Button } from './Button'
import { cn } from './cn'
import { Input } from './Input'
import { TEXT } from './typeScale'
import { useExitGhost } from './motion'
import { Presence } from './Presence'
import { useModalLayer } from './useModalLayer'

/**
 * THE DIALOG, for every surface in four apps: the behaviour (`DialogShell`) and the parts inside
 * it (`DialogHeader`, `DialogBody`, `DialogFooter`), plus the two whole dialogs every app needs
 * (`ConfirmDialog`, `DialogNotice`).
 *
 * It was the app's `ModalShell` plus a panel class string retyped forty times, beside a kit `Modal`
 * the consoles used, whose title was 14px where every app dialog's was 18, whose footer gap was
 * 2 where the app's was 3, and which had no "Discard unsaved changes?" guard because that guard
 * needed `t()`. The guard's words come in as props now, so the guard lives here once.
 */

/**
 * THE SCRIM UNDER EVERY DIALOG: it darkens AND blurs what is behind it - the one place backdrop
 * blur is used anywhere (maintainer's request, 2026-09-27). A dialog opened over a dialog lays a
 * second one on top, which is what sets the lower one aside. Status screens (restart, quit) and
 * the image lightbox take the darker `MODAL_SCRIM_DARK`.
 */
export const MODAL_SCRIM = 'fixed inset-0 bg-black/50 backdrop-blur-sm animate-fade-in'
export const MODAL_SCRIM_DARK = 'fixed inset-0 bg-black/75 backdrop-blur-sm animate-fade-in'

/**
 * The full-screen container. NO horizontal padding below `sm` (maintainer, 2026-10-02): the
 * panel's own `mx-4` is the backdrop margin, and there is no close X, so the backdrop is the way
 * out and must show on every side. The OVERLAY scrolls, as the last resort for a genuinely short
 * window - never the panel, which would clip every dropdown inside it that is not portaled.
 */
export const DIALOG_OVERLAY = 'fixed inset-0 z-dialog flex justify-center overflow-y-auto overscroll-contain px-0 py-4 sm:px-4 short:p-2'

/**
 * A dialog that is a BOTTOM SHEET on a phone - the conversation history: pinned to the bottom edge,
 * the full width, square at the bottom, and a centred dialog again from `sm`. `sheet` on the shell.
 */
export const DIALOG_SHEET_OVERLAY = 'fixed inset-0 z-dialog flex justify-center overflow-y-auto overscroll-contain p-0 sm:p-4 short:p-2'
const DIALOG_SHEET_PANEL = 'origin-bottom sm:origin-center mx-0 sm:mx-4 mt-auto mb-0 sm:my-auto rounded-b-none sm:rounded-lg max-h-[85dvh] sm:max-h-[70dvh] short:max-h-[calc(100dvh-1rem)] overflow-hidden'

export type DialogSize = 'sm' | 'md' | 'lg' | 'xl' | 'form' | 'wide' | 'full' | 'viewport'

/**
 * How wide a dialog may grow. `sm` confirmations, `md` a short form or a notice, `lg` a form,
 * `xl` a detail or a list, `form` a two-column create/edit form (the provider and connection
 * dialogs disagreed at 5xl and 4xl), `wide` a three-column editor, `full` a viewer. Never a
 * `min-w-*`: the one minimum is the global, phone-safe one in proxyma-primitives.css.
 */
export const DIALOG_WIDTH: Record<DialogSize, string> = {
  sm: 'max-w-sm',
  md: 'max-w-md',
  lg: 'max-w-lg',
  xl: 'max-w-3xl',
  form: 'max-w-2xl lg:max-w-5xl',
  wide: 'max-w-3xl lg:max-w-6xl',
  full: 'max-w-3xl lg:max-w-7xl',
  // A canvas viewer (the knowledge graph): the whole window but a margin, at every width.
  viewport: 'max-w-none h-[calc(100dvh-1rem)] sm:h-[calc(100dvh-3rem)]',
}

/**
 * The panel itself. `fill` gives a dialog a fixed height (a list, an editor) so ONE region inside
 * it scrolls and the dialog does not change size as content loads.
 */
export function dialogPanel(size: DialogSize, opts: { fill?: boolean; cap?: boolean; className?: string } = {}): string {
  return cn(
    'relative surface-float border border-line/45 rounded-lg shadow-card w-full mx-4 animate-slide-up flex flex-col',
    DIALOG_WIDTH[size],
    opts.fill && 'h-[min(760px,calc(100dvh-2rem))] short:h-[calc(100dvh-1rem)]',
    // CAPPED, not fixed: the dialog is as tall as its content up to the window's height, and then its
    // one `DialogBody scroll` region scrolls. Five dialogs typed this cap with five values.
    opts.cap && !opts.fill && 'max-h-[min(760px,calc(100dvh-2rem))] short:max-h-[calc(100dvh-1rem)] overflow-hidden',
    opts.className,
  )
}

export interface DiscardLabels {
  title: string
  body: string
  keep: string
  discard: string
}

const ENGLISH_DISCARD: DiscardLabels = {
  title: 'Discard unsaved changes?',
  body: 'Your changes have not been saved.',
  keep: 'Keep editing',
  discard: 'Discard',
}

export interface DialogShellProps {
  /** Closes the dialog. Omit for a dialog nothing may dismiss (a restart, a mandatory notice). */
  onClose?: () => void
  /** False while a close must be refused - typically `!saving`. Defaults to true. */
  closable?: boolean
  /** Whether closing now would lose work: every close request then asks first. */
  dirty?: boolean
  /** The "Discard unsaved changes?" words, translated by the app. English when omitted. */
  discardLabels?: DiscardLabels
  /** Enter submits, for simple confirmations only - never for a multi-line editor. */
  onSubmit?: () => void
  initialFocus?: 'panel' | 'first-field'
  /**
   * Which layer gets the keys when several are open: a higher level wins over a dialog opened after
   * it. 0 for every ordinary dialog; 1 for the onboarding tour; 2 for the restart and maintenance
   * screens. Match it to where the dialog is DRAWN.
   */
  level?: number
  /** The standard panel at this width. Omit and pass `panelClassName` for a panel of your own. */
  size?: DialogSize
  fill?: boolean
  /**
   * As tall as its content up to the window, then its `DialogBody scroll` region scrolls - for
   * content of unknown length (an event's details, a feedback's attachments). `fill` is the fixed
   * height of a list or an editor.
   */
  cap?: boolean
  /** A bottom sheet below `sm`, a centred dialog from it (`DIALOG_SHEET_OVERLAY`). */
  sheet?: boolean
  overlayClassName?: string
  /** The scrim. `null` for a dialog whose container carries its own backdrop. */
  scrimClassName?: string | null
  closeOnBackdrop?: boolean
  panelClassName?: string
  panelProps?: HTMLAttributes<HTMLDivElement>
  role?: 'dialog' | 'alertdialog'
  ariaLabel?: string
  ariaLabelledBy?: string
  /** As a function it receives `requestClose` - the GUARDED close - for Cancel buttons. */
  children: ReactNode | ((requestClose: () => void) => ReactNode)
}

/**
 * EVERY DIALOG'S BEHAVIOUR, in one place:
 *
 * - ESCAPE CLOSES THE TOP DIALOG ONLY, through the kit's one layer stack (`useModalLayer`).
 * - FOCUS IS TRAPPED, and returns to the opener on close.
 * - UNSAVED WORK IS ASKED ABOUT. With `dirty`, Escape, the scrim and any Cancel wired to the
 *   `requestClose` handed to `children` open "Discard unsaved changes?" on top. A SAVE closes
 *   through `onClose` directly and is never asked about.
 * - A DIALOG THAT MUST NOT CLOSE SAYS SO (`closable={false}`, or no `onClose`), and still swallows
 *   Escape so the key cannot fall through to whatever lies beneath.
 * - A DRAG STOPS AT ITS EDGE: React bubbles portal events through the COMPONENT tree, so a dialog
 *   opened inside a page with a full-page drop target handed every drag over it to the page.
 * - IT LEAVES AS IT ARRIVED: the scrim fades in and the panel rises, and when the caller unmounts
 *   it an inert copy fades out in its place (`useExitGhost`) - the scrim marked `data-scrim`, the
 *   panel `data-motion-panel`. Callers keep opening it as `{open && <Dialog/>}`.
 */
export function DialogShell({
  onClose, closable = true, dirty = false, discardLabels = ENGLISH_DISCARD, onSubmit, initialFocus, level = 0,
  size, fill, cap, sheet, overlayClassName, scrimClassName = MODAL_SCRIM, closeOnBackdrop = true,
  panelClassName, panelProps, role = 'dialog', ariaLabel, ariaLabelledBy, children,
}: DialogShellProps) {
  const overlayRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [confirming, setConfirming] = useState(false)
  useExitGhost(overlayRef)

  const canClose = closable && !!onClose
  const requestClose = useCallback(() => {
    if (!canClose) return
    if (dirty) setConfirming(true)
    else onClose?.()
  }, [canClose, dirty, onClose])

  useModalLayer(panelRef, { onRequestClose: onClose ? requestClose : undefined, closable, onSubmit, initialFocus, level })

  const stopDrag = (handler?: (e: React.DragEvent<HTMLDivElement>) => void) =>
    (e: React.DragEvent<HTMLDivElement>) => { handler?.(e); e.stopPropagation() }

  return createPortal(
    <div ref={overlayRef} className={overlayClassName ?? (sheet ? DIALOG_SHEET_OVERLAY : DIALOG_OVERLAY)}>
      {scrimClassName !== null && <div data-scrim="" className={scrimClassName} onClick={closeOnBackdrop ? requestClose : undefined} />}
      <div
        {...panelProps}
        onDragEnter={stopDrag(panelProps?.onDragEnter)}
        onDragOver={stopDrag(panelProps?.onDragOver)}
        onDragLeave={stopDrag(panelProps?.onDragLeave)}
        onDrop={stopDrag(panelProps?.onDrop)}
        ref={panelRef}
        // The hook for the common minimum size in proxyma-primitives.css.
        data-dialog-panel=""
        data-motion-panel=""
        tabIndex={-1}
        role={role}
        aria-modal="true"
        aria-label={ariaLabel}
        aria-labelledby={ariaLabelledBy}
        className={cn('my-auto outline-none', size && dialogPanel(size, { fill, cap }), sheet && DIALOG_SHEET_PANEL, panelClassName)}
      >
        {typeof children === 'function' ? children(requestClose) : children}
      </div>
      {confirming && (
        <DialogShell
          onClose={() => setConfirming(false)}
          level={level}
          role="alertdialog"
          ariaLabel={discardLabels.title}
          // Above every ordinary dialog and the ones opened from inside them, below the tour.
          overlayClassName="fixed inset-0 z-alert flex justify-center overflow-y-auto px-0 py-4 sm:px-4 short:p-2"
          size="sm"
        >
          <DialogBody>
            <DialogTitle>{discardLabels.title}</DialogTitle>
            <DialogDescription className="mt-2">{discardLabels.body}</DialogDescription>
          </DialogBody>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setConfirming(false)} data-autofocus>{discardLabels.keep}</Button>
            <Button variant="danger" onClick={() => { setConfirming(false); onClose?.() }}>{discardLabels.discard}</Button>
          </DialogFooter>
        </DialogShell>
      )}
    </div>,
    document.body,
  )
}

/** A dialog's name - the type scale's title, 18/600. One size for every dialog in four apps. */
export function DialogTitle({ children, id, className }: { children: ReactNode; id?: string; className?: string }) {
  return <h2 id={id} className={cn(TEXT.title, className)}>{children}</h2>
}

/**
 * The paragraph under a title. `body` (14px secondary) for a confirmation, where it IS the content;
 * `caption` (13px) for a form, where it only introduces the fields.
 */
export function DialogDescription({ children, tone = 'body', className }: { children: ReactNode; tone?: 'body' | 'caption'; className?: string }) {
  return <div className={cn(tone === 'body' ? TEXT.body : TEXT.caption, className)}>{children}</div>
}

/**
 * The divided header: an optional 20px mark, the title, a subtitle under it, and actions at the
 * right (a "more" menu, a status badge). Every dialog has one - three styles (in-body titles,
 * divided bands, header rows) collapsed into this.
 */
export function DialogHeader({ title, icon, subtitle, actions, titleId, wrapTitle, className }: {
  title: ReactNode
  icon?: ReactNode
  subtitle?: ReactNode
  actions?: ReactNode
  titleId?: string
  /** Let a long title wrap rather than end in an ellipsis - a question asked as the title. */
  wrapTitle?: boolean
  className?: string
}) {
  return (
    <div className={cn('flex items-start gap-3 px-4 sm:px-6 py-4 short:py-2.5 border-b border-line/45 shrink-0', className)}>
      {icon && <div className="shrink-0 h-7 flex items-center [&>svg]:w-5 [&>svg]:h-5">{icon}</div>}
      <div className="flex-1 min-w-0">
        <DialogTitle id={titleId} className={wrapTitle ? 'break-words' : 'truncate'}>{title}</DialogTitle>
        {subtitle && <div className={cn(TEXT.caption, 'mt-0.5')}>{subtitle}</div>}
      </div>
      {actions && <div className="shrink-0 min-h-7 flex items-center gap-2">{actions}</div>}
    </div>
  )
}

/**
 * The body. `scroll` makes it the dialog's one scrolling region (with `fill` on the shell);
 * `centered` is the confirmation layout (glyph, title, text, centred). `flush` drops the padding,
 * for a body that is a table or a list padding its own rows.
 */
export function DialogBody({ children, scroll, centered, flush, className }: {
  children: ReactNode
  scroll?: boolean
  centered?: boolean
  flush?: boolean
  className?: string
}) {
  return (
    <div
      data-dialog-body=""
      className={cn(
        !flush && (centered ? 'px-4 sm:px-6 pt-6 pb-5' : 'px-4 sm:px-6 py-5 short:py-3'),
        centered && 'flex flex-col items-center text-center',
        scroll && 'flex-1 min-h-0 overflow-y-auto',
        className,
      )}
    >
      {children}
    </div>
  )
}

/**
 * The action row: Cancel then the primary action, pushed right, `gap-3`. `start` holds a status
 * line or a secondary link at the left (a "Saved." note, a "Leave feedback" link). A destructive
 * text action goes LAST on the right with `ml-2`, by the Settings rule. `wrap` lets a row of three
 * or four actions break onto a second line on a phone instead of overflowing it.
 */
export function DialogFooter({ children, start, wrap, className }: { children: ReactNode; start?: ReactNode; wrap?: boolean; className?: string }) {
  return (
    // Fades in: a dialog that moves through steps swaps its footer, and the new one should arrive.
    <div className={cn('flex items-center justify-end gap-3 px-4 sm:px-6 py-4 short:py-2.5 border-t border-line/45 shrink-0 animate-fade-in', wrap && 'flex-wrap gap-y-2', className)}>
      {/* A status line comes and goes by fading (Presence), keeping its place at the left. */}
      <Presence show={!!start} collapse={false} className="mr-auto min-w-0">
        <div className="min-w-0 flex items-center gap-3 text-xs">{start}</div>
      </Presence>
      {children}
    </div>
  )
}

/**
 * A dialog body that is a WARNING rather than a form: the icon centred on top (bare, no tinted
 * circle - maintainer, 2026-09-29), the explanation centred under it, and the one thing to do
 * about it under that.
 */
export function DialogNotice({ children, title, detail, action, icon: Icon = AlertTriangle, tone = 'warning' }: {
  children?: ReactNode
  /** The warning in one line, in the label's weight ("This folder is already indexed"). */
  title?: ReactNode
  /** A smaller line under the text - a path, a limit. */
  detail?: ReactNode
  action?: ReactNode
  icon?: ComponentType<{ size?: number | string; className?: string }>
  tone?: 'warning' | 'danger' | 'brand' | 'neutral'
}) {
  return (
    <div className="flex flex-col items-center text-center gap-4 py-2">
      <Icon size={28} className={cn(tone === 'warning' && 'text-warning', tone === 'danger' && 'text-danger', tone === 'brand' && 'text-brand-legible', tone === 'neutral' && 'text-ink-caption')} aria-hidden="true" />
      <div className="text-sm text-ink-secondary leading-relaxed max-w-sm space-y-2">
        {title && <p className={TEXT.label}>{title}</p>}
        {children}
        {detail && <p className={cn(TEXT.caption, 'break-words')}>{detail}</p>}
      </div>
      {action}
    </div>
  )
}

export interface ConfirmDialogProps {
  title: ReactNode
  /** What will happen, in a sentence. */
  description?: ReactNode
  /** What will be lost, one item a line, in the tone's ink. */
  consequences?: ReactNode[]
  confirmLabel: string
  cancelLabel: string
  /** Shown on the confirm button while `pending`. */
  pendingLabel?: string
  pending?: boolean
  onConfirm: () => void
  onCancel: () => void
  tone?: 'danger' | 'warning'
  icon?: ComponentType<{ size?: number | string; className?: string }>
  /**
   * Type a value to enable Confirm - for an action that cannot be undone and has a name (a
   * deployment's data, a project). `label` explains what to type.
   */
  typedConfirmation?: { value: string; label: ReactNode; placeholder?: string }
  /** A refusal from the server, shown under the text. */
  error?: ReactNode
  level?: number
  /**
   * WHAT the action is done to - a source's name, a skill's - set in bold after `description`
   * ("This removes the source and its index." **Product wiki**). Six dialogs built "Are you sure
   * you want to {verb} <strong>name</strong>?" by hand; a sentence and its subject apart is the
   * shape every language can order.
   */
  subject?: ReactNode
}

/**
 * THE CONFIRMATION, centred: a glyph in the tone, the title, what happens, what is lost, and
 * Cancel beside the toned Confirm. Never `window.confirm` (Administration used it for maintenance
 * mode) and never a button whose label turns into "Confirm delete" on the first click (two dialogs
 * did) - one confirmation, everywhere.
 */
export function ConfirmDialog({
  title, description, consequences, confirmLabel, cancelLabel, pendingLabel, pending, onConfirm, onCancel,
  tone = 'danger', icon: Icon = tone === 'danger' ? Trash2 : AlertTriangle, typedConfirmation, error, level, subject,
}: ConfirmDialogProps) {
  const [typed, setTyped] = useState('')
  const titleId = useId()
  const typedId = useId()
  const ready = !typedConfirmation || typed.trim() === typedConfirmation.value
  return (
    <DialogShell
      onClose={onCancel}
      closable={!pending}
      level={level}
      role="alertdialog"
      // Named by its title whatever the title is - a string or a sentence with a name in bold.
      ariaLabelledBy={titleId}
      size="sm"
      onSubmit={ready && !pending && !typedConfirmation ? onConfirm : undefined}
    >
      <DialogBody centered>
        <Icon size={32} className={cn('mb-4', tone === 'danger' ? 'text-danger' : 'text-warning')} aria-hidden="true" />
        <DialogTitle id={titleId}>{title}</DialogTitle>
        {(description || subject !== undefined) && <DialogDescription className="mt-2">{description}{subject !== undefined && <> <strong className="font-semibold text-ink-primary break-words">{subject}</strong></>}</DialogDescription>}
        {consequences && consequences.length > 0 && (
          <ul className="mt-3 space-y-1.5 text-left self-stretch">
            {consequences.map((c, i) => (
              <li key={i} className="flex items-start gap-2 text-xs leading-relaxed text-ink-secondary">
                <span className={cn('mt-1.5 w-1.5 h-1.5 rounded-full shrink-0', tone === 'danger' ? 'bg-danger' : 'bg-warning')} aria-hidden="true" />
                <span>{c}</span>
              </li>
            ))}
          </ul>
        )}
        {typedConfirmation && (
          <div className="mt-4 self-stretch text-left">
            <label htmlFor={typedId} className={cn(TEXT.label, 'block mb-2')}>{typedConfirmation.label}</label>
            <Input
              id={typedId}
              value={typed}
              onChange={e => setTyped(e.target.value)}
              placeholder={typedConfirmation.placeholder ?? typedConfirmation.value}
              autoComplete="off"
              data-autofocus
            />
          </div>
        )}
        {error && <p role="alert" className="mt-3 text-xs text-danger">{error}</p>}
      </DialogBody>
      <DialogFooter>
        <Button variant="secondary" onClick={onCancel} disabled={pending} data-autofocus={typedConfirmation ? undefined : true}>{cancelLabel}</Button>
        <Button variant={tone === 'danger' ? 'danger' : 'primary'} onClick={onConfirm} disabled={!ready} loading={pending}>
          {pending && pendingLabel ? pendingLabel : confirmLabel}
        </Button>
      </DialogFooter>
    </DialogShell>
  )
}
