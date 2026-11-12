import { useState } from 'react'
import {
  BookOpen, ClipboardList, Clock, FilePlus, Gauge, GraduationCap, MessageSquare, MessageSquareReply, MessageSquareX,
  PenLine, Play, ShieldCheck, Webhook,
} from 'lucide-react'
import { ActivityLine, Presence, cn } from '@/components/ui/kit'
import { MarkdownRenderer } from './MarkdownRenderer'
import { useLeavingItems } from '../hooks/useLeavingItems'
import { QuestionPrompt } from './QuestionPrompt'
import { ToolApprovalPrompt } from './ToolApprovalPrompt'
import { ToolEnablePrompt } from './ToolEnablePrompt'
import { ToolCallDetailModal } from './ToolCallDetailModal'
import { toolIcon } from '../utils/toolIcon'
import { mailLabel } from '../utils/mailLabel'
import type {
  AgentMailEntry, PendingApproval, PendingEnableRequest, PendingQuestion,
} from '../types/chat.types'
import { useTranslation } from '@/i18n'

// What a WORKFLOW STEP is waiting on the user for, at the foot of the thread.
//
// A step's write takes the same approval the agent's does, and it may ask the user a question - but
// its row can be folded away in the step table, and a prompt drawn inside it is a step that sits
// waiting on a question nobody can see. So they are drawn here, where the agent's own prompts are
// and where the thread scrolls to when one appears.
//
// (Sub-agents' prompts were drawn here too, until 2026-10-28. An agent the agent deploys works in a
// conversation of its own, and asks there.)

interface Props {
  conversationId: string
  approvals: PendingApproval[]
  enableRequests: PendingEnableRequest[]
  questions: PendingQuestion[]
}

type Entry =
  | { kind: 'approval'; key: string; approval: PendingApproval }
  | { kind: 'enable'; key: string; request: PendingEnableRequest }
  | { kind: 'question'; key: string; question: PendingQuestion }

export function AgentPrompts({ conversationId, approvals, enableRequests, questions }: Props) {
  const [detail, setDetail] = useState<PendingApproval | null>(null)
  // One list of what is asked, so an answered one eases out where it stood (Presence, kept for its
  // exit by useLeavingItems) instead of the thread jumping up by its height (CHAT-08).
  const entries: Entry[] = [
    ...approvals.filter(a => !(a.decision && a.decisionSent)).map(a => ({ kind: 'approval' as const, key: `a:${a.callId}`, approval: a })),
    ...enableRequests.map(r => ({ kind: 'enable' as const, key: `e:${r.callId}`, request: r })),
    ...questions.map(q => ({ kind: 'question' as const, key: `q:${q.callId}`, question: q })),
  ]
  const shown = useLeavingItems(entries, e => e.key)

  return (
    <>
      {shown.map(({ item, key, leaving }) => item.kind === 'enable' ? (
        // A dialog of its own - nothing in the thread's flow, so no line of its own here. Its exit
        // is the dialog's.
        !leaving && <ToolEnablePrompt key={key} request={item.request} conversationId={conversationId} />
      ) : (
        <Presence key={key} show={!leaving}>
          {/* Turn prompts, which the thread scrolls to when they appear (CHAT-17 for approvals). */}
          <div className="mb-6" data-turn-prompt="">
            {item.kind === 'approval'
              ? <ToolApprovalPrompt approval={item.approval} conversationId={conversationId} onOpenDetail={() => setDetail(item.approval)} />
              : <QuestionPrompt pending={item.question} conversationId={conversationId} />}
          </div>
        </Presence>
      ))}
      {detail && (
        <ToolCallDetailModal toolName={detail.toolName} arguments={detail.arguments} onClose={() => setDetail(null)} />
      )}
    </>
  )
}

/** The glyph of one mail entry - the mark of the tool its kind belongs to. */
function mailIcon(entry: AgentMailEntry) {
  switch (entry.kind) {
    case 'report': return toolIcon('deployAgent')
    case 'message': return toolIcon('tellAgent')
    case 'reply': return MessageSquareReply
    case 'told': return MessageSquare
    case 'alarm': return toolIcon('alarm')
    case 'background': return toolIcon('background')
    case 'approval': return ShieldCheck
    case 'context': return Gauge
    case 'memory': return BookOpen
    // The marks the Triggers tab gives each kind of trigger.
    case 'run_schedule': return Clock
    case 'run_webhook': return Webhook
    case 'run_source': return FilePlus
    case 'run_member': return Play
    case 'learning': return GraduationCap
    case 'revising': return PenLine
    case 'lead_note': return ClipboardList
    case 'reply_failed': return MessageSquareX
    default: return MessageSquare
  }
}

// THE POINT IN THE THREAD WHERE SOMETHING REACHED THE AGENT FROM ITS INBOX while it worked - a
// deployed agent's report or message, an alarm going off, a background job's result, or what the
// user said to a deployed agent in its own conversation. What the assistant says next is its
// reaction to it, and without this line it would be speaking unprompted. One compact line each,
// never a user bubble: nobody typed it here.
//
// `withText` draws the words of the items that have them under their line (AgentMailEntry.text) - a
// teammate's message or reply, what the agent told the person - left-aligned and indented to the
// line's words, as mail rather than as anybody's bubble: a Constellations agent's own chat, where the
// team's internal work is the point. What the agent TOLD the person reads as the agent's own words,
// in the body's ink; the rest is quoted, in the secondary ink.
export function AgentMailNotice({ entries, withText = false }: { entries: AgentMailEntry[]; withText?: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="mb-3 space-y-1 animate-fade-in">
      {entries.map((entry, i) => (
        <div key={`${entry.agentId ?? entry.kind}-${i}`} className="min-w-0">
          <ActivityLine
            icon={mailIcon(entry)}
            state="done"
            label={mailLabel(entry, t)}
            maxWidth="max-w-full"
          />
          {withText && entry.text && (
            <div className={cn('mt-1 pl-6 min-w-0', entry.kind === 'told' ? 'text-ink-primary' : 'text-ink-secondary')}>
              <MarkdownRenderer content={entry.text} />
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
