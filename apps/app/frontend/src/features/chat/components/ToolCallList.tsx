import { useState } from 'react'
import { Presence, ToolCallGroup, cn, toolFold } from '@/components/ui/kit'
import { ToolCallDetailModal } from './ToolCallDetailModal'
import { ToolCallRow } from './ToolCallRow'
import { SubAgentPanel } from './SubAgentPanel'
import { toolBlocks } from '../utils/toolBlocks'
import type { ToolCallInfo, PendingApproval, PendingEnableRequest } from '../types/chat.types'
import { useTranslation } from '@/i18n'

// What the agent did between two things it said, as BLOCKS in the order it worked: a group of its
// own tool calls, each sub-agent it handed work to, the next group of tool calls (toolBlocks.ts).
//
// The tool NAME and a dump of its argument values used to be what a row and the header said -
// "Search Indexed Sources - release notes 5 0.3". Both now read as a clause about what is
// happening ("Searching your sources - release notes", then "Searched 2 sources"), which is
// toolActivity's whole job; see that file for why it is separate from toolDisplayName.

interface Props {
  toolCalls: ToolCallInfo[]
  // One entry per write-capable tool call currently awaiting the user's Accept/Deny. While it waits
  // the card is drawn under its group's list, in the order of the calls; answered, the call is a
  // row in the list again, with which way it went beside it.
  pendingApprovals?: PendingApproval[]
  // One entry per write-capable tool call currently paused awaiting the user's Enable/Keep
  // Disabled decision (a gate ahead of pendingApprovals - see ToolEnablePrompt's docs). Matched
  // by callId the same way pendingApprovals is; mutually exclusive per call with pendingApprovals,
  // so both can be looked up unconditionally.
  pendingEnableRequests?: PendingEnableRequest[]
  conversationId?: string
}

export function ToolCallList({
  toolCalls, pendingApprovals = [], pendingEnableRequests = [], conversationId,
}: Props) {
  const [modalTool, setModalTool] = useState<ToolCallInfo | null>(null)

  if (!toolCalls.length) return null

  return (
    <>
      {/* No horizontal padding, matching the assistant message below it, which has none either
          since 2026-09-14 - the two must change together or the tool rows and the answer stop
          lining up. */}
      <div className="mb-2 space-y-2">
        {toolBlocks(toolCalls).map(block => block.kind === 'agent' ? (
          <SubAgentPanel
            key={block.call.agent?.id ?? block.call.callId}
            call={block.call}
            steps={block.steps}
            conversationId={conversationId}
            onOpenDetail={setModalTool}
          />
        ) : (
          <ToolGroup
            key={block.calls[0].callId}
            calls={block.calls}
            // The main agent's own prompts. A sub-agent's are drawn at the foot of the thread
            // (AgentPrompts), since its panel may be far from where the reader is.
            pendingApprovals={pendingApprovals.filter(a => block.calls.some(tc => tc.callId === a.callId))}
            pendingEnableRequests={pendingEnableRequests.filter(r => block.calls.some(tc => tc.callId === r.callId))}
            conversationId={conversationId}
            onOpenDetail={setModalTool}
          />
        ))}
      </div>
      {modalTool && (
        <ToolCallDetailModal
          toolName={modalTool.toolName}
          arguments={modalTool.arguments}
          result={modalTool.result}
          onClose={() => setModalTool(null)}
        />
      )}
    </>
  )
}

interface GroupProps {
  calls: ToolCallInfo[]
  pendingApprovals: PendingApproval[]
  pendingEnableRequests: PendingEnableRequest[]
  conversationId?: string
  onOpenDetail: (tc: ToolCallInfo) => void
}

// A run of the main agent's own tool calls (maintainer's request, 2026-10-13): FOLDED by default,
// which draws every call still running - all of a burst of parallel calls - and folds each away as it
// finishes, until the last call alone is left with the chevron beside it (the kit's toolFold). The
// chevron opens every call, and the user's choice then stands for this group.
//
// It was OPEN by default inside a box five rows tall that scrolled (0.29.2 feedback), so parallel
// calls showed three at a time under a fade. Folding is what keeps the thread from growing now: what
// stays drawn is only what is still working.
function ToolGroup({ calls, pendingApprovals, pendingEnableRequests, conversationId, onOpenDetail }: GroupProps) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState(false)

  // A call the turn is waiting on the user for: its approval card, or its enable prompt. Those are
  // drawn UNDER the list, never inside it - a write the agent is blocked on must be answerable
  // without opening a fold. Once answered, the call goes back to its place in the list.
  const asking = (tc: ToolCallInfo) =>
    pendingApprovals.some(a => a.callId === tc.callId && !(a.decision && a.decisionSent))
    || pendingEnableRequests.some(r => r.callId === tc.callId)
  const listed = calls.filter(tc => !asking(tc))
  const waiting = calls.filter(asking)
  const fold = toolFold(listed.map(tc => !!tc.done), expanded)

  const row = (tc: ToolCallInfo) => (
    <ToolCallRow
      key={tc.callId}
      tc={tc}
      approval={pendingApprovals.find(a => a.callId === tc.callId)}
      enableRequest={pendingEnableRequests.find(r => r.callId === tc.callId)}
      conversationId={conversationId}
      onOpenDetail={() => onOpenDetail(tc)}
    />
  )

  return (
    // Flush with the thread, not indented (maintainer, 2026-10-02): an indent cost a phone a fifth
    // of its width. The group itself is the kit's ToolCallGroup, which the site's scenes draw too.
    <div>
      {listed.length > 0 && (
        <ToolCallGroup
          rows={listed.map((tc, i) => ({ key: tc.callId, node: row(tc), shown: fold.shown[i] }))}
          open={expanded && fold.foldable}
          onToggle={fold.foldable ? () => setExpanded(e => !e) : undefined}
          toggleLabels={{ show: t('Show all steps'), hide: t('Show only the last step') }}
        />
      )}
      {/* Eases in and out as calls start and stop waiting on the reader. */}
      <Presence show={waiting.length > 0}>
        <div className={cn('space-y-1', listed.length > 0 && 'mt-1')}>
          {waiting.map(row)}
        </div>
      </Presence>
    </div>
  )
}
