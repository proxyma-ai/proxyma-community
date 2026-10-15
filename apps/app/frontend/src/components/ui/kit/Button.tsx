/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Button.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { forwardRef, useId } from 'react'
import { SquareArrowOutUpRight } from 'lucide-react'
import { cn } from './cn'
import { Spinner } from './Spinner'
import { Tooltip } from './Tooltip'

import { buttonClass, type ButtonSize, type ButtonVariant } from './buttonClass'

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  /**
   * An action in flight: the kit `Spinner` before the label, `aria-busy`, and the button disabled.
   * Replaces the `RefreshCw animate-spin` loaders the upload and folder dialogs drew by hand,
   * which the Spinner rule forbids.
   */
  loading?: boolean
  /**
   * WHY the button cannot be used now, translated - "Only an Owner can share this account". With
   * `disabled`, the button stays FOCUSABLE (`aria-disabled`, clicks refused) so a keyboard and a
   * screen reader reach the reason: a tooltip under a mouse and on keyboard focus, and the sentence
   * itself under the button on a touch screen, where there is no hover to ask with. A plain
   * `disabled` button says nothing at all, which is why the key-account pages forked this.
   */
  disabledReason?: string
}

/**
 * The one Proxyma button.
 *
 * Sizes. `sm` is the default and the baseline every other control (Input, Select, SearchInput,
 * Badge) is dimensioned to match - most buttons never pass `size`. `md` is a deliberate
 * call-to-action. `lg` is for the marketing site's hero, not application chrome.
 *
 * Hover is `opacity-90`, NOT a colour change, and only under a mouse (`mouse:hover:`) - a bare
 * hover sticks on glass after the tap. The secondary button answers with its BORDER only; it had a
 * `bg-surface` fill under the pointer, which on a card already drawn in `bg-surface/40` made it
 * look pressed rather than pointed at.
 *
 * Every filled button carries the same floor width, so a Save/Cancel pair renders at matching
 * widths and a label that changes on click ("Save" -> "Saving...") does not resize the row.
 *
 * The text variants - `hyperlink`, `quiet` (caption ink: "Start fresh", "View 12 more") and
 * `danger-quiet` (Delete, Revoke, Deny: put it LAST in its row, set apart with `ml-2`) - are the
 * kit's text link on a `<button>`. The confirmation a `danger-quiet` opens carries the `danger`
 * fill, because that is the moment something is actually lost.
 *
 * `type` is `button` unless said otherwise: a kit Button inside a `<form>` no longer submits it by
 * accident (one download button on the site did). The ref reaches the `<button>`, so a Button can
 * anchor a Popover or take focus back from a dialog without a wrapper span.
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'sm', loading, disabledReason, className, children, disabled, type = 'button', onClick, ...props },
  ref,
) {
  const reasonId = useId()
  const gated = !!disabled && !!disabledReason && !loading
  const button = (
    <button
      {...props}
      ref={ref}
      type={type}
      className={buttonClass(variant, size, className)}
      // A gated button keeps its focus (aria-disabled) so the reason can be reached; clicks stop here.
      disabled={gated ? undefined : disabled || loading}
      aria-disabled={gated || undefined}
      aria-describedby={gated ? reasonId : props['aria-describedby']}
      aria-busy={loading || undefined}
      onClick={gated ? e => e.preventDefault() : onClick}
    >
      {loading && <Spinner size={14} className="text-inherit" />}
      {children}
    </button>
  )
  if (!gated) return button
  return (
    <span className="inline-flex flex-col items-start gap-1">
      <Tooltip label={disabledReason}>{button}</Tooltip>
      {/* The reason in words where there is no hover to ask with; read out by its id everywhere. */}
      <span id={reasonId} className="sr-only touch:not-sr-only touch:text-xs touch:leading-relaxed touch:text-ink-caption">
        {disabledReason}
      </span>
    </span>
  )
})

export interface ButtonLinkProps extends React.AnchorHTMLAttributes<HTMLAnchorElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  /**
   * A destination OUTSIDE Proxyma: opens in a new tab with `rel="noopener noreferrer"` and carries
   * the kit's outbound glyph after the label, as `ExternalLink` does. Five landing sections wrote
   * the glyph and the target/rel pair by hand.
   */
  external?: boolean
}

/**
 * The same treatment on an <a>. The marketing site's calls to action are links, not buttons - a
 * download and an external checkout both need anchor semantics (middle click, copy link address,
 * crawlability) that a <button onClick={navigate}> throws away.
 */
export const ButtonLink = forwardRef<HTMLAnchorElement, ButtonLinkProps>(function ButtonLink(
  { variant = 'primary', size = 'sm', external, className, children, ...props },
  ref,
) {
  return (
    <a
      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
      {...props}
      ref={ref}
      className={buttonClass(variant, size, className)}
    >
      {children}
      {external && <ExternalMark size={size === 'sm' ? 12 : 14} />}
    </a>
  )
})

/**
 * THE OUTBOUND GLYPH, alone - the square with an arrow `ExternalLink` and `ButtonLink external`
 * end with, for a link drawn some other way (a card, a render prop). One glyph means "this leaves
 * Proxyma" everywhere; lucide's `ExternalLink` icon is not it.
 */
export function ExternalMark({ size = 12, className }: { size?: number; className?: string }) {
  return <SquareArrowOutUpRight size={size} aria-hidden="true" className={cn('shrink-0', className)} />
}
