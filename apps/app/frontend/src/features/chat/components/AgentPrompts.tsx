import { useState } from 'react'
import { ActivityLine, Presence } from '@/components/ui/kit'
import { useLeavingItems } from '../hooks/useLeavingItems'
import { QuestionPrompt } from './QuestionPrompt'
import { ToolApprovalPrompt } from './ToolApprovalPrompt'
import { ToolEnablePrompt } from './ToolEnablePrompt'
import { ToolCallDetailModal } from './ToolCallDetailModal'
import { toolIcon } from '../utils/toolIcon'
import { DELEGATE } from '../utils/subAgent'
import { agentWorkState } from '../utils/agentState'
import type {
  PendingApproval, PendingEnableRequest, PendingQuestion, SubAgentReported,
} from '../types/chat.types'
import { useTranslation } from '@/i18n'

// What SUB-AGENTS are waiting on the user for, at the foot of the thread.
//
// A sub-agent's write takes the same approval the main agent's does, and it may ask the user a
// question - but its panel can be anywhere: several turns up, for one working in the background,
// and scrolled out of sight. A prompt drawn inside it is a sub-agent that sits waiting on a
// question nobody can see. So they are drawn here, where the main agent's own prompts are and
// where the thread scrolls to when one appears, each saying which sub-agent it is from.

interface Props {
  conversationId: string
  approvals: PendingApproval[]
  enableRequests: PendingEnableRequest[]
  questions: PendingQuestion[]
  // The sub-agent's title, by its id - undefined for one this page does not know.
  titleOf: (agentId: string) => string | undefined
}

type Entry =
  | { kind: 'approval'; key: string; approval: PendingApproval }
  | { kind: 'enable'; key: string; request: PendingEnableRequest }
  | { kind: 'question'; key: string; question: PendingQuestion }

export function AgentPrompts({ conversationId, approvals, enableRequests, questions, titleOf }: Props) {
  const { t } = useTranslation()
  const [detail, setDetail] = useState<PendingApproval | null>(null)
  // One list of what is asked, so an answered one eases out where it stood (Presence, kept for its
  // exit by useLeavingItems) instead of the thread jumping up by its height (CHAT-08).
  const entries: Entry[] = [
    ...approvals.filter(a => !(a.decision && a.decisionSent)).map(a => ({ kind: 'approval' as const, key: `a:${a.callId}`, approval: a })),
    ...enableRequests.map(r => ({ kind: 'enable' as const, key: `e:${r.callId}`, request: r })),
    ...questions.map(q => ({ kind: 'question' as const, key: `q:${q.callId}`, question: q })),
  ]
  const shown = useLeavingItems(entries, e => e.key)

  const from = (agentId?: string) => t('From sub-agent: {title}', { title: (agentId && titleOf(agentId)) || t('Sub-agent') })

  return (
    <>
      {shown.map(({ item, key, leaving }) => item.kind === 'enable' ? (
        // A dialog of its own, which says which sub-agent asks (CHAT-15) - nothing in the thread's
        // flow, so no line of its own here: it left an orphan "From sub-agent" caption above
        // nothing. Its exit is the dialog's.
        !leaving && <ToolEnablePrompt key={key} request={item.request} conversationId={conversationId} from={from(item.request.parentCallId)} />
      ) : (
        <Presence key={key} show={!leaving}>
          {/* Turn prompts, which the thread scrolls to when they appear (CHAT-17 for approvals). */}
          <div className="mb-5" data-turn-prompt="">
            {item.kind === 'approval'
              ? <ToolApprovalPrompt approval={item.approval} conversationId={conversationId} onOpenDetail={() => setDetail(item.approval)} from={from(item.approval.parentCallId)} />
              : <QuestionPrompt pending={item.question} conversationId={conversationId} from={from(item.question.parentCallId)} />}
          </div>
        </Presence>
      ))}
      {detail && (
        <ToolCallDetailModal toolName={detail.toolName} arguments={detail.arguments} onClose={() => setDetail(null)} />
      )}
    </>
  )
}

// The point in the thread where a background sub-agent's report reached the main agent. What the
// assistant says next is its reaction to that report, and without this line it would be speaking
// about something the reader last saw several messages up - or speaking unprompted altogether,
// when the report started a turn by itself. The report itself is in the sub-agent's panel.
export function SubAgentReportedNotice({ reports }: { reports: SubAgentReported[] }) {
  const { t } = useTranslation()
  const AgentIcon = toolIcon(DELEGATE)
  return (
    <div className="mb-3 space-y-1">
      {reports.map(r => {
        // The robot finished, as its panel and the agents graph draw it.
        return (
          <ActivityLine
            key={r.agentId}
            icon={AgentIcon}
            state={agentWorkState(r.status)}
            label={t('{title} reported back', { title: r.title || t('Sub-agent') })}
            maxWidth="max-w-full"
          />
        )
      })}
    </div>
  )
}
