/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/layout.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * THE PAGE COLUMN: the one width every Proxyma surface lays its content out in.
 *
 * <p>`AppHeader` centres its content in this box, and a page's content has to sit in the SAME box
 * or the header reads as detached from the page under it - the logo and nav outside the text
 * column's edges, most visibly on a wide screen. It was a class string retyped in the header and
 * in every page and section that wanted to line up with it, and a retyped string is one that can
 * drift; a page now imports the string the header itself uses.
 *
 * <p>`max-w-7xl` from `2xl` so a 1080p screen is not two wide empty margins.
 *
 * <p>`PAGE_GUTTER` is the inset inside the column. The header writes its own right side, since it
 * reserves room for the preference cluster there (see AppHeader), but its LEFT side is this.
 *
 * <p>12px on a phone since 2026-10-02 (it was 16px): a 390px screen has no width to give to
 * margins, and the maintainer asked for the room to go to the content.
 */
export const PAGE_COLUMN = 'max-w-5xl 2xl:max-w-7xl mx-auto'
export const PAGE_GUTTER = 'px-3 sm:px-6'

/**
 * THE MEASURE for running prose that spans a page or section: it ends on the grid's eight-column
 * line (2/3 of the column less a third of a 3rem gap) - where the second of three equal columns
 * ends, and where the FAQ's and the download section's text ends - and never past about 70
 * characters a line (since 2026-10-28; the review measured 114-142 on the site's widest prose).
 */
export const PROSE_MEASURE = 'max-w-[70ch] lg:max-w-[min(70ch,calc(66.6667%-1rem))]'

/**
 * THE COLUMN'S EDGES FOR SOMETHING PLACED ABSOLUTELY in a box as wide as the window - a control
 * floating over a full-bleed canvas (Constellations), which cannot sit inside `PAGE_COLUMN` and has
 * to land on its lines anyway (since 2026-10-28; the review found the canvas's controls at x=12
 * under a header starting at x=232). The same arithmetic as PAGE_COLUMN plus PAGE_GUTTER: the
 * gutter on a phone, and from `sm` half of what the column leaves plus the gutter.
 */
export const PAGE_INSET_LEFT =
  'left-3 sm:left-[max(1.5rem,calc((100%_-_64rem)_/_2_+_1.5rem))] 2xl:left-[max(1.5rem,calc((100%_-_80rem)_/_2_+_1.5rem))]'
export const PAGE_INSET_RIGHT =
  'right-3 sm:right-[max(1.5rem,calc((100%_-_64rem)_/_2_+_1.5rem))] 2xl:right-[max(1.5rem,calc((100%_-_80rem)_/_2_+_1.5rem))]'
