/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Prose.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { cn } from './cn'
import { HScroll } from './HScroll'
import { TEXT } from './typeScale'

/**
 * PROSE AT THE THREAD'S DENSITY - markdown the agent wrote, in a chat bubble. `.prose-doc` is the
 * type of a DOCUMENT (a manual, a legal page): its 40px heading gaps and 16px paragraph gaps are
 * right on a page and far too loose between two tool calls. This is the same hierarchy, packed:
 * the type scale's title and section for `#` and `##`, then a semibold heading at the body
 * size, `my-1` paragraphs, lists hanging their markers outside. One set of classes, so the
 * markdown renderer stops carrying its own.
 *
 * The thread reads at `TEXT.body`, 13/20 (since 2026-10-31, the maintainer's 13px base, VS Code's;
 * 14/22 the day before, 16/26 from 2026-10-28): the UI's own size with a reading leading. Headings
 * are modest - `#` 14, `##` and `###` 13 semibold - so an answer's structure never outshouts the
 * page. A table in it is 13px (`ProseTable`), as in every other table. EVERY TEXT BLOCK RUNS THE
 * COLUMN'S FULL WIDTH, as a table and a code block do (the maintainer, 2026-10-31: "Always utilize
 * full width of the chat interface"); it was capped at 70ch, which left a note under a table wrapping
 * at half the table's width.
 */
export const PROSE_CHAT = {
  h1: cn(TEXT.title, 'mt-4 mb-2'),
  h2: cn(TEXT.section, 'mt-3 mb-2'),
  h3: 'text-base font-semibold text-ink-primary mt-2 mb-1',
  p: 'my-1 text-base',
  /** A list; nested lists sit tight under their item (`depth > 0`). */
  list: (ordered: boolean, depth: number) => cn(
    ordered ? 'list-decimal' : 'list-disc',
    'list-outside pl-6 space-y-1 text-base',
    depth === 0 ? 'my-2' : 'mt-1',
  ),
  quote: 'border-l-2 border-line pl-3 my-2 text-ink-caption text-base',
  hr: 'border-line/40 my-3',
  /** A link in running text: the paragraph's own weight, brand ink, underlined under a mouse only. */
  link: 'text-brand-legible underline-offset-[3px] mouse:hover:underline focus-ring rounded-sm',
} as const

/**
 * A TABLE IN PROSE - ruled between rows, a caption-ink header, no grid and no filled header row
 * (`.prose-doc table`'s look, at the thread's size), in a CARD since 2026-10-29 (the depth review:
 * the card plane, its line and `shadow-card`, as `.prose-doc .table-wrap` is). Wider than the
 * column, it scrolls sideways in the kit's `HScroll`. `nowrap(cell)` keeps a one-token cell (an
 * issue key, a date) on one line.
 */
export function ProseTable({ headers, rows, nowrap, className }: {
  headers: ReactNode[]
  rows: ReactNode[][]
  nowrap?: (row: number, col: number) => boolean
  className?: string
}) {
  return (
    <div className={cn('kit-card my-2 rounded-lg border surface-card shadow-card overflow-hidden', className)}>
    <HScroll>
      <table className="min-w-full text-sm border-collapse">
        <thead>
          <tr>
            {headers.map((h, i) => (
              <th key={i} className="text-left text-xs font-medium text-ink-caption border-b border-line/40 px-3 py-2">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, ri) => (
            <tr key={ri}>
              {row.map((cell, ci) => (
                <td key={ci} className={cn('px-3 py-2 align-top', ri < rows.length - 1 && 'border-b border-line/20', nowrap?.(ri, ci) && 'whitespace-nowrap')}>{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </HScroll>
    </div>
  )
}

/**
 * A SPREADSHEET, as a spreadsheet - a grid with every cell ruled and the row numbers pinned at the
 * left, 12px. Not prose: a sheet or a CSV previewed is read by row and column, and the grid is
 * what tells a reader that cell C14 is a cell. Cells are cut at 24rem with the rest on hover.
 * `headerRow` sets the first row in primary ink and medium weight - a sheet's column names.
 */
export function SheetTable({ rows, headerRow, className }: { rows: string[][]; headerRow?: boolean; className?: string }) {
  return (
    <table className={cn('min-w-full text-xs text-ink-secondary border-collapse', className)}>
      <tbody>
        {rows.map((row, r) => (
          <tr key={r} className={headerRow && r === 0 ? 'font-medium text-ink-primary' : undefined}>
            <td className="sticky left-0 w-px bg-surface px-2 py-1 border border-line/30 text-ink-disabled text-right data-readout">{r + 1}</td>
            {row.map((cell, c) => (
              <td key={c} title={cell} className="px-2 py-1 border border-line/30 whitespace-nowrap max-w-[24rem] truncate">{cell}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
