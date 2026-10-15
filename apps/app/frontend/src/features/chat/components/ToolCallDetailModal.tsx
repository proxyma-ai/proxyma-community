import { useState } from 'react'
import { Button, Caption, CodeBlock, DialogBody, DialogHeader, Eyebrow, Frame, Presence, cn } from '@/components/ui/kit'
import { redactFilePaths } from '../utils/redactFilePaths'
import { jsonFields } from '../utils/jsonFields'
import { JsonFieldTree } from './JsonFieldTree'
import { toolDisplayName } from '../utils/toolDisplayName'
import { toolIcon } from '../utils/toolIcon'
import { useTranslation } from '@/i18n'
import { ModalShell } from '@/components/ui/ModalShell'

function formatJson(raw: string): string {
  try {
    return JSON.stringify(redactFilePaths(JSON.parse(raw)), null, 2)
  } catch {
    return raw
  }
}

interface Props {
  toolName: string
  arguments: string
  result?: string
  onClose: () => void
}

// Opened from ToolCallList's own row (completed calls show a result, pending/in-flight calls -
// including ones awaiting approval - show just the arguments) so every state shares the exact
// same input/output chrome instead of maintaining separate modal layouts per state.
export function ToolCallDetailModal({ toolName, arguments: args, result, onClose }: Props) {
  const { t } = useTranslation()
  // The same mark the row that opened this modal carries. toolDisplayName exists because three
  // places deriving the same title independently is how a row said "Agent" while the modal it
  // opened still said "Delegate"; an icon map is the same hazard, so it resolves from the one
  // module too.
  const ToolIcon = toolIcon(toolName)
  // readSkill's output is the skill's full instruction text, not something a user needs to see
  // here - the skill name in Input is already self-explanatory.
  const output = result && toolName !== 'readSkill' ? result : null
  // Both sides as labelled fields (the review's F28 for the arguments; the result since
  // 2026-10-02), each with its exact JSON one click away. A side that is not a JSON object - plain
  // text, a number, nothing - has only the plain view and no switch.
  return (
    <ModalShell
      onClose={onClose}
      // A call's arguments and its result are both of unknown length, so the panel is capped and
      // each block scrolls inside itself - the panel never does. Input ABOVE Output, at every
      // width (maintainer's feedback on 0.26.0 - side by side, a one-line query stood in a column
      // as tall as the result beside it): the input takes the height it needs up to a cap, and the
      // result takes the rest.
      size="xl"
      cap
      ariaLabel={toolDisplayName(toolName, t)}
    >
      <DialogHeader
        icon={<ToolIcon className="text-ink-caption" aria-hidden="true" />}
        title={toolDisplayName(toolName, t)}
      />
      <DialogBody className="flex-1 min-h-0 flex flex-col gap-4">
        <Payload label={t('Input')} json={args} fit={!!output} />
        {/* Arrives while the dialog is open, for a call still running when it was opened. */}
        <Presence show={!!output} collapse={false} className="flex-1 min-h-0 flex flex-col">
          {output && <Payload label={t('Output')} json={output} />}
        </Presence>
      </DialogBody>
    </ModalShell>
  )
}

// One side of the dialog: the payload as fields, or as the text it is. `fit`: as tall as it needs
// up to a cap, for the input above a result - otherwise it fills what the panel has left.
function Payload({ label, json, fit = false }: { label: string; json: string; fit?: boolean }) {
  const { t } = useTranslation()
  const fields = jsonFields(json)
  const [raw, setRaw] = useState(false)
  const hasFields = !!fields && fields.length > 0
  // An empty object or list - a call with no arguments - says so in a word, not as "{}".
  const empty = !!fields && fields.length === 0
  return (
    <div className={cn('min-w-0 min-h-0 flex flex-col', fit ? 'shrink-0 max-h-[min(14rem,35dvh)]' : 'flex-1')}>
      {/* A sentence-case label over the block (it was an upper-case copy of `label-upper`), and
          the switch between fields and JSON as a quiet text action beside it. */}
      <Eyebrow
        className="mb-1.5 shrink-0"
        action={hasFields && (
          <Button variant="quiet" onClick={() => setRaw(r => !r)} aria-pressed={raw}>
            {raw ? t('Show as fields') : t('Show raw JSON')}
          </Button>
        )}
      >
        {label}
      </Eyebrow>
      {/* The fields and the "Empty" word sit in the code block's own frame (the kit's Frame), so
          the two views of one payload are one box. */}
      {hasFields && !raw ? (
        // Each view fades in as the switch above swaps them.
        <Frame className={cn('min-h-0 overflow-y-auto animate-fade-in', !fit && 'flex-1')}>
          <JsonFieldTree fields={fields} />
        </Frame>
      ) : empty ? (
        <Frame className={cn('min-h-0', !fit && 'flex-1')}>
          <Caption>{t('Empty')}</Caption>
        </Frame>
      ) : (
        // The kit CodeBlock - JSON is code, so `font-mono` (this `pre` had none). Wrapped, and its
        // inner region is the one that scrolls.
        <CodeBlock wrap maxHeight="flex-1 min-h-0" className={cn('flex flex-col min-h-0 animate-fade-in', !fit && 'flex-1')}>
          {formatJson(json)}
        </CodeBlock>
      )}
    </div>
  )
}
