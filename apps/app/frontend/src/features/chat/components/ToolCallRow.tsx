import { ToolCallRow as KitToolCallRow } from '@/components/ui/kit'
import { ApprovalOutcome, ToolApprovalPrompt } from './ToolApprovalPrompt'
import { ToolEnablePrompt } from './ToolEnablePrompt'
import { useElapsedText } from '../hooks/useServerNow'
import { failedToolLabel, isFailedResult, toolActivity } from '../utils/toolActivity'
import { toolIcon } from '../utils/toolIcon'
import { extractErrorSummary } from '../utils/toolErrors'
import type { ToolCallInfo, PendingApproval, PendingEnableRequest } from '../types/chat.types'
import { useTranslation } from '@/i18n'

// A tool call's ONE MARK is the kit's StateMark, drawn by the kit ToolCallRow: the tool's own glyph
// (toolIcon) in the colour of how the call stands - caption ink while it runs, the brand's once it
// worked, red once it failed. The local ToolMark wrapper had no caller left (CHAT-24).

interface RowProps {
  tc: ToolCallInfo
  approval?: PendingApproval
  enableRequest?: PendingEnableRequest
  conversationId?: string
  onOpenDetail: () => void
}

// One tool call: whether it worked, what it is, what it did, how long it took. The same row for
// every call the agent makes (ToolCallList). The row itself is the
// kit's `ToolCallRow`, shared with the site's product scenes; what it SAYS - the wording, the
// glyph, the approval and enable prompts - is this app's.
export function ToolCallRow({ tc, approval, enableRequest, conversationId, onOpenDetail }: RowProps) {
  const { t } = useTranslation()
  const elapsed = useElapsedText([tc])
  const failed = tc.done && !!tc.result && isFailedResult(tc.result)
  // A failure keeps the tool's own name (or a passthrough's specific wording) and shows the error. toolActivity's finished wording is a
  // claim that the thing was done - "Created PROX-412" over a red cross is a contradiction, and
  // the half of it the eye reads first is the wrong half.
  const label = failed ? failedToolLabel(tc, t) : toolActivity(tc, t)

  // While an approval is being asked, the CARD is the row: it names the same action, so the row
  // above it would only say it twice. Its title opens the same detail dialog the row's name does.
  // Marked as a turn prompt, so the thread scrolls to IT when it appears rather than to an older
  // question (CHAT-17).
  if (approval && conversationId && !(approval.decision && approval.decisionSent)) {
    return (
      <div className="mb-2" data-turn-prompt="">
        <ToolApprovalPrompt approval={approval} conversationId={conversationId} onOpenDetail={onOpenDetail} />
      </div>
    )
  }

  return (
    <div>
      {/* WHAT the tool is and WHETHER it worked, in one mark (see ToolMark). The words are
          clickable in every state (not just once done), so a running call's arguments can be read
          before it lands. A failed call's error starts on the row's one line; the whole of it is
          in the detail the words open (the UI review, 2026-10-28). */}
      <KitToolCallRow
        icon={toolIcon(tc.toolName)}
        state={!tc.done ? 'running' : failed ? 'failed' : 'done'}
        label={label}
        error={failed ? extractErrorSummary(tc.result!, t) : undefined}
        elapsed={elapsed}
        onOpen={onOpenDetail}
        // Which way an approval went, once sent. The question itself is the card above.
        outcome={approval && <ApprovalOutcome approval={approval} />}
      />
      {enableRequest && conversationId && (
        <ToolEnablePrompt request={enableRequest} conversationId={conversationId} />
      )}
    </div>
  )
}
