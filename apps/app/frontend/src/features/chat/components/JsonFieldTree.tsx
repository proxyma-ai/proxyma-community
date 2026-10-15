import { KeyValue, KeyValueGroup, KeyValueList } from '@/components/ui/kit'
import type { JsonField } from '../utils/jsonFields'

// A JSON payload as labelled fields - see jsonFields.ts - in the kit's `rows` KeyValueList. A group
// (an object, an item of a list) is a KeyValueGroup: its label over its own fields, set in behind
// a rule, so depth reads as indentation and nothing is ever printed as JSON.
export function JsonFieldTree({ fields }: { fields: JsonField[] }) {
  return (
    <KeyValueList layout="rows">
      {fields.map((field, i) => field.kind === 'value' ? (
        <KeyValue key={i} layout="rows" label={field.label} block={field.block}>
          {field.value}
        </KeyValue>
      ) : (
        <KeyValueGroup key={i} label={field.label}>
          <JsonFieldTree fields={field.fields} />
        </KeyValueGroup>
      ))}
    </KeyValueList>
  )
}
