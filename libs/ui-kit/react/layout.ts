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
 * ends, and where the FAQ's and the download section's text ends. Full width below `lg`.
 */
export const PROSE_MEASURE = 'lg:max-w-[calc(66.6667%-1rem)]'
