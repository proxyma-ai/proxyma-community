/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/typeScale.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * THE TYPE SCALE - one for every surface in the four apps, since the 2026-10 kit rebuild.
 *
 * It began as the Settings window's scale (`SETTINGS_TYPE`, 2026-09-30), written because one name
 * did two jobs there and a field label outranked the section it sat in. Everything else then had
 * its own: dialogs titled at 14/600 (the kit `Modal`), 16/700 and 18/700 (the source dialogs),
 * sections at 14/600 (Administration), field labels at 12/600 (the kit `FIELD_LABEL`, which both
 * consoles used). Side by side a dialog opened from Sources and one opened from Settings looked
 * like two products. The Settings scale won because it was the one written down and argued for.
 *
 * - `title`   18/600 - a page's, a Settings tab's or a dialog's own name.
 * - `section` 16/600 - a sub-section of a page, tab or dialog.
 * - `label`   14/500 - the name of a field, a switch row, a list item.
 * - `option`  14/400 - a radio or checkbox choice; the quieter body line of a row.
 * - `body`    15/400 - running text in a dialog or a card, and a chat answer (`PROSE_CHAT`).
 * - `lead`    17/400 - the paragraph that opens a page or a site section, a step up from body
 *   (since 2026-10-14: the site set it by hand sixty times in six combinations).
 * - `caption` 13/400 caption ink - everything that explains. `leading-relaxed` always: half the
 *   captions had it and half did not, and two lines of small text without it read as one smudge.
 * - `eyebrow` 13/500 caption ink, sentence case - the label inside a card naming what it shows.
 *
 * ONE STEP UP, every role but the title, since 2026-10-15 (the readability pass: "improve the text
 * readiness - font size, thickness, text color - in all apps as well as docs"). It was caption 12,
 * label and option 13, body 14, section 15, lead 16. Measured in Edge, two thirds of a Settings
 * tab's characters were 12px caption ink, and a chat answer - the one thing in the product read at
 * length - was 14px. The ORDER is the old one and so is the reasoning behind it: a label stays a
 * step above the caption that explains it and a step below the section it sits in, so neither
 * reads as the other. The leading did not move where a row's height depends on it (label and
 * option are still 20px lines), so no list, rail or dialog got taller.
 *
 * 15 and 17 are not steps of the Tailwind scale, deliberately - body has to sit between the UI's 14
 * and the heading's 16, and lead a clear step above body. Use these strings (or the components in
 * `Text.tsx`) rather than retyping a size. Plain .ts with no imports, so `node --test` and any app
 * can read it.
 */
export const TEXT = {
  title: 'text-lg font-semibold text-ink-primary',
  section: 'text-base leading-6 font-semibold text-ink-primary',
  label: 'text-sm leading-5 font-medium text-ink-primary',
  option: 'text-sm leading-5 text-ink-secondary',
  body: 'text-[15px] leading-relaxed text-ink-secondary',
  lead: 'text-[17px] leading-relaxed text-ink-secondary',
  caption: 'text-xs leading-relaxed text-ink-caption',
  eyebrow: 'text-xs font-medium text-ink-caption',
} as const

export type TextRole = keyof typeof TEXT

/**
 * THE TEXT LINK - an action written as words, on a button, an anchor or a router link alike.
 *
 * A class string rather than a component for the same reason as `iconButton`: the kit cannot
 * import a router, and the same look has to sit on a react-router `Link`, a Next.js `Link`, a
 * plain `<a>` and a `<button>`. There were 37 hand-made ones in the app alone, in six inks, three
 * weights and two underline offsets.
 *
 * - `brand` is the default (`text-brand-legible`), `danger` removes something, `quiet` is the
 *   caption-ink action that should not draw the eye ("Start fresh", "View 12 more").
 * - SEMIBOLD, one weight for every standalone link. The kit's `hyperlink` button was bold and every
 *   call site that wrote its own was lighter; semibold is where they met.
 * - Underline under a MOUSE only (`mouse:hover:`), at 3px - a bare `hover:` sticks after a tap.
 * - A link INSIDE prose is not this: it takes the paragraph's weight (see `.prose-doc a`).
 */
export type TextLinkTone = 'brand' | 'danger' | 'quiet' | 'warning'

const LINK_TONE: Record<TextLinkTone, string> = {
  brand: 'text-brand-legible',
  danger: 'text-danger',
  warning: 'text-warning',
  quiet: 'text-ink-caption mouse:hover:text-ink-primary',
}

export const TEXT_LINK_BASE =
  'inline-flex items-center gap-1.5 font-semibold bg-transparent border-none p-0 cursor-pointer ' +
  'underline-offset-[3px] mouse:hover:underline transition-colors focus-ring rounded-sm ' +
  'disabled:opacity-40 disabled:cursor-not-allowed disabled:no-underline'

/**
 * `inline: true` for a link INSIDE a sentence the kit draws (a footnote's "read the manual"): no
 * flex box and no gap, so it wraps with the words around it. Two site files defined that by hand.
 */
export function textLink(tone: TextLinkTone = 'brand', opts: { inline?: boolean } = {}): string {
  const base = opts.inline ? TEXT_LINK_BASE.replace('inline-flex items-center gap-1.5 ', 'inline ') : TEXT_LINK_BASE
  return `${base} ${LINK_TONE[tone]}`
}
