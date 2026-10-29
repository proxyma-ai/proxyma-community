/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/CodeBlock.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { cn } from './cn'
import { CopyButton } from './CopyButton'
import { HScroll } from './HScroll'

/**
 * CODE AS A BLOCK - a command, a token, a JSON document, a log. `font-mono` (the one place the
 * code face belongs; `xs` is the type scale's code role, 13/20), `bg-surface-alt` with `shadow-well` (a sunken well, since 2026-10-29), `border-line/50`,
 * `rounded-md`. With `copyLabel` a copy button sits at the top right; a command a reader is told
 * to run always has one (the Docker commands on Updates had none).
 *
 * A single line scrolls sideways in the kit's `HScroll` (no scrollbar, a fade where it is cut);
 * `wrap` wraps instead; `maxHeight` caps a long block, which then scrolls inside itself.
 */
/**
 * THE FRAME OF SOMETHING THAT IS NOT PAGE TEXT - a hole in the surface, as a field is: the code
 * block's own box, for content that is not code (a tool call's fields, a transcript). One class,
 * so the boxes cannot drift from the code block beside them.
 */
export const FRAME_CLASS = 'rounded-md border border-line/50 bg-surface-alt shadow-well'

export function Frame({ children, padded = true, className }: { children: ReactNode; padded?: boolean; className?: string }) {
  return <div className={cn(FRAME_CLASS, padded && 'px-3 py-2', className)}>{children}</div>
}

export function CodeBlock({ children, text, copyLabel, copiedLabel, wrap, maxHeight, fill, size = 'xs', font = 'mono', className }: {
  /** What is shown. Defaults to `text`. */
  children?: ReactNode
  /** What is copied. */
  text?: string
  copyLabel?: string
  copiedLabel?: string
  wrap?: boolean
  /** A Tailwind max-height class, e.g. `max-h-64`. */
  maxHeight?: string
  /**
   * Take the rest of a flex column and scroll inside it - a transcript filling a detail dialog's
   * fixed-height side. Instead of `maxHeight`.
   */
  fill?: boolean
  size?: 'xs' | 'sm'
  /**
   * `ui` sets a block that is preformatted but not code - a chat transcript, where the line breaks
   * matter and a code face would make every sentence read as a log.
   */
  font?: 'mono' | 'ui'
  className?: string
}) {
  const body = children ?? text
  const pre = (
    <pre className={cn(
      font === 'mono' ? 'font-mono' : 'font-ui',
      'text-ink-primary px-3 py-2',
      size === 'xs' ? 'text-xs leading-5' : 'text-sm',
      wrap ? 'whitespace-pre-wrap break-words' : 'whitespace-pre w-max min-w-full',
      copyLabel && 'pr-10',
    )}>
      {body}
    </pre>
  )
  return (
    <div className={cn('relative', FRAME_CLASS, fill && 'flex-1 min-h-0 flex flex-col', className)}>
      <div className={cn(maxHeight && cn(maxHeight, 'overflow-y-auto'), fill && 'flex-1 min-h-0 overflow-y-auto')}>
        {wrap ? pre : <HScroll>{pre}</HScroll>}
      </div>
      {copyLabel && text !== undefined && (
        <div className="absolute top-1 right-1">
          <CopyButton getText={() => text} label={copyLabel} copiedLabel={copiedLabel ?? copyLabel} size="md" />
        </div>
      )}
    </div>
  )
}
