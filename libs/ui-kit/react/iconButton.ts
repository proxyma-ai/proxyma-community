/**
 * THE ONE ICON-ONLY BUTTON RECIPE, for every surface (maintainer's rule, 2026-10-05).
 *
 * A button that shows only a glyph - copy, history, new chat, close, a row's delete - was drawn
 * five ways across the product: round and turning brand on hover in the chat toolbar, round and
 * turning to primary ink in the header, small and square in rows, and seven with a hover FILL
 * against the header's own rule. Side by side, the history button went white under the pointer
 * and the new-chat button beside it went teal.
 *
 * The rules, so the next one needs no thought:
 *
 * - NO HOVER FILL. An icon-only control answers a pointer with its COLOR, and only that: caption ink
 *   at rest, primary ink under a mouse. `danger` turns red instead, for a control that removes
 *   something. A control's own STATE (liked, on, warning) is its color at rest, set by the caller.
 * - THE TARGET GROWS, THE GLYPH DOES NOT: 24, 32 or 36px on a mouse, 44px on glass (`touch:`), with
 *   the 14 or 16px icon centered in it.
 * - `focus-ring` for the keyboard, on a rounded-md box so the ring has corners like the fields'.
 *
 * A function rather than a component: the buttons it styles are plain buttons, motion buttons and
 * links in four apps, and a class string fits all of them. Plain .ts with no imports.
 */
export type IconButtonSize = 'sm' | 'md' | 'lg'
export type IconButtonTone = 'default' | 'danger'

const SIZE: Record<IconButtonSize, string> = {
  // A row's own actions: copy, rewind, a row's delete.
  sm: 'w-6 h-6',
  // Toolbars: the chat composer's controls.
  md: 'w-8 h-8',
  // The header's controls.
  lg: 'w-9 h-9',
}

const TONE: Record<IconButtonTone, string> = {
  default: 'text-ink-caption mouse:hover:text-ink-primary mouse:disabled:text-ink-caption',
  danger: 'text-ink-caption mouse:hover:text-danger mouse:disabled:text-ink-caption',
}

export const ICON_BUTTON_BASE =
  'inline-flex shrink-0 items-center justify-center rounded-md bg-transparent transition-colors ' +
  'duration-fast focus-ring disabled:opacity-40 disabled:cursor-not-allowed touch:w-11 touch:h-11'

/** The classes for an icon-only button. Pass `tone: null` to set the colors yourself (a state). */
export function iconButton(size: IconButtonSize = 'md', tone: IconButtonTone | null = 'default'): string {
  return [ICON_BUTTON_BASE, SIZE[size], tone ? TONE[tone] : ''].filter(Boolean).join(' ')
}
