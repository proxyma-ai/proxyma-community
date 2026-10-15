/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Modal.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useId, type FormEvent } from 'react'
import { DialogBody, DialogFooter, DialogHeader, DialogShell, type DialogSize, type DiscardLabels } from './Dialog'

interface ModalProps {
  title: string
  onClose: () => void
  children: React.ReactNode
  /** Widest the panel grows - see `DIALOG_WIDTH`. */
  size?: DialogSize
  /**
   * Rendered against the bottom edge, below a divider. As a function it receives the form's id, for
   * the primary button: `<Button type="submit" form={formId}>` (with `onSubmit`).
   */
  footer?: React.ReactNode | ((formId: string) => React.ReactNode)
  /**
   * A FORM DIALOG: the body is a `<form>` and this runs on its submit - Enter in a field, or the
   * footer's `type="submit"` button. Two console dialogs hid a `<button type="submit" hidden>` in
   * their body to get Enter to submit. `preventDefault` is called for you.
   */
  onSubmit?: () => void
  /** Rendered at the left of the footer - a status line. */
  footerStart?: React.ReactNode
  /** Corner slot, top-right of the header. Typically a role or tier Badge. */
  cornerBadge?: React.ReactNode
  /** A line under the title. */
  subtitle?: React.ReactNode
  /** A fixed-height dialog whose body is its one scrolling region. */
  fill?: boolean
  dirty?: boolean
  discardLabels?: DiscardLabels
  closable?: boolean
  className?: string
}

/**
 * The whole standard dialog in one call: a divided header with the title, a padded body, and a
 * footer when there is one - `DialogShell` and its three parts, for the dialogs that need nothing
 * else. The consoles' dialogs are this; the app's go through its `ModalShell`, which supplies the
 * translated discard words, and compose the same parts.
 *
 * No close X: a dialog closes on its backdrop, on Escape, and on the Cancel or Close its footer
 * carries (maintainer's decision, 2026-09-26).
 */
export function Modal({
  title, onClose, children, size = 'md', footer, footerStart, cornerBadge, subtitle, fill, dirty, discardLabels, closable, onSubmit, className,
}: ModalProps) {
  const formId = useId()
  const submit = (e: FormEvent) => { e.preventDefault(); onSubmit?.() }
  const footerNode = typeof footer === 'function' ? footer(formId) : footer
  return (
    <DialogShell
      onClose={onClose}
      closable={closable}
      dirty={dirty}
      discardLabels={discardLabels}
      size={size}
      fill={fill}
      ariaLabel={title}
      panelClassName={className}
    >
      <DialogHeader title={title} subtitle={subtitle} actions={cornerBadge} />
      <DialogBody scroll={fill}>
        {onSubmit ? <form id={formId} onSubmit={submit} noValidate>{children}</form> : children}
      </DialogBody>
      {footerNode && <DialogFooter start={footerStart}>{footerNode}</DialogFooter>}
    </DialogShell>
  )
}
