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
 * THE TYPE SCALE - one for every surface in the four apps, in Space Grotesk.
 *
 * It began as the Settings window's scale (`SETTINGS_TYPE`, 2026-09-30), written because one name
 * did two jobs there and a field label outranked the section it sat in, and became every surface's
 * in the 2026-10 kit rebuild. Since 2026-10-28 (the UI review: 31 rendered size/leading/weight
 * combinations in the app, 64 on the site, 66% of the app's text at 13px, 15px and 17px roles off
 * the preset's scale) every role is a step of the preset's `fontSize` with that step's one leading:
 *
 * - `pageTitle` 24/32 600 - a page's own name (`PageHeader`, `PageTitle`).
 * - `title`     18/26 600 - a dialog's or a Settings tab's name, a card's heading.
 * - `section`   16/24 600 - a sub-section of a page, tab or dialog.
 * - `body`      16/26 400 - READING text: a chat answer (`PROSE_CHAT`), a manual (`.prose-doc`), a
 *   paragraph a person reads rather than scans.
 * - `lead`      18/28 400 - the paragraph that opens a page or a site section, a step up from body.
 * - `label`     14/20 500 - the name of a field, a switch row, a list item.
 * - `option`    14/20 400 - UI text: a radio or checkbox choice, field text, a row's quieter line.
 * - `caption`   13/18 400 caption ink - everything that explains.
 * - `eyebrow`   13/18 500 caption ink, sentence case - the label inside a card naming what it shows.
 * - `code`      13/20 mono - a code block, a command (inline code is 0.9em of its sentence).
 *
 * Buttons are 14/20 600 (`buttonClass.ts`). NO WEIGHT 700 BELOW THE PAGE TITLE: a bold label beside
 * a semibold heading read as the heading. Numbers are the UI face with tabular figures
 * (`.data-readout`), never the code face.
 *
 * Use these strings (or the components in `Text.tsx`) rather than retyping a size. Plain .ts with no
 * imports, so `node --test` and any app can read it.
 */
export const TEXT = {
  pageTitle: 'text-2xl font-semibold text-ink-primary',
  title: 'text-lg font-semibold text-ink-primary',
  section: 'text-base leading-6 font-semibold text-ink-primary',
  body: 'text-base text-ink-secondary',
  lead: 'text-lg leading-7 text-ink-secondary',
  label: 'text-sm font-medium text-ink-primary',
  option: 'text-sm text-ink-secondary',
  caption: 'text-xs text-ink-caption',
  eyebrow: 'text-xs font-medium text-ink-caption',
  code: 'font-mono text-xs leading-5',
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
  'inline-flex items-center gap-1 font-semibold bg-transparent border-none p-0 cursor-pointer ' +
  'underline-offset-[3px] mouse:hover:underline transition-colors focus-ring rounded-sm ' +
  'disabled:opacity-40 disabled:cursor-not-allowed disabled:no-underline'

/**
 * `inline: true` for a link INSIDE a sentence the kit draws (a footnote's "read the manual"): no
 * flex box and no gap, so it wraps with the words around it. Two site files defined that by hand.
 */
export function textLink(tone: TextLinkTone = 'brand', opts: { inline?: boolean } = {}): string {
  // A standalone link gets a 44px box on glass (`tap-area`); one inside a sentence does not.
  const base = opts.inline ? TEXT_LINK_BASE.replace('inline-flex items-center gap-1 ', 'inline ') : `${TEXT_LINK_BASE} tap-area`
  return `${base} ${LINK_TONE[tone]}`
}
