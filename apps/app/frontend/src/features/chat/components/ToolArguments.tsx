import { KeyValue, KeyValueList } from '@/components/ui/kit'
import type { ArgumentRow } from '../utils/argumentRows'

// A call's arguments as label / value rows - see argumentRows.ts - in the kit's `rows` KeyValueList.
// `compact` is the approval card's version: the first few rows, each value held to a few lines
// (`clamp`), since the card is a question to answer rather than a place to read a payload (the
// row's detail dialog has all of it).
export function ToolArguments({ rows, compact = false }: { rows: ArgumentRow[]; compact?: boolean }) {
  const shown = compact ? rows.slice(0, 4) : rows
  return (
    <KeyValueList layout="rows">
      {shown.map(row => (
        <KeyValue key={row.key} layout="rows" label={row.label} block={row.block} clamp={compact}>
          {row.value}
        </KeyValue>
      ))}
    </KeyValueList>
  )
}
