import { useEffect, useRef, type ReactNode } from 'react'
import { PenLine } from 'lucide-react'
import { Button, Caption, InlineNotice, Presence, PromptCard, Readout } from '@/components/ui/kit'
import { useToastStore } from '@/store/toastStore'
import { chatApi } from '../services/chatApi'
import { useApprovalCountdown } from '../hooks/useApprovalCountdown'
import { formatElapsed } from '../utils/formatElapsed'
import { toolActivity } from '../utils/toolActivity'
import { argumentRows } from '../utils/argumentRows'
import { ToolArguments } from './ToolArguments'
import type { ApprovalChoice, PendingApproval } from '../types/chat.types'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useTranslation } from '@/i18n'
import { isGone, withReason } from '@/services/apiError'

// FIVE MINUTES, since 2026-10-02 (maintainer's feedback). It was sixty seconds, which is less than
// it takes to read what a change will do and open the page it will do it to - a write the user
// meant to allow was denied while they were still checking it. The server's own limit, for a page
// that is closed and can deny nothing, is thirty minutes (ChatService.APPROVAL_TIMEOUT_MINUTES).
const APPROVAL_TIMEOUT_SECONDS = 5 * 60

type Decision = ApprovalChoice

const IN_FLIGHT_LABEL: Record<Decision, string> = {
  accept_once: 'Sending approval',
  accept_all: 'Sending approval',
  deny: 'Sending denial',
}

interface Props {
  approval: PendingApproval
  conversationId: string
  // Opens the call's detail dialog - every argument, and the raw JSON.
  onOpenDetail: () => void
  // Who asks, when it is not the agent itself - the card's eyebrow.
  from?: ReactNode
}

// Rendered whenever the backend fires a 'tool-approval-request' SSE event for a write-capable tool
// call. The ReAct loop blocks server side (up to 30 min) until this resolves via POST
// .../tool-approval - the actual tool-result still arrives asynchronously over SSE once that
// happens, which is also what clears this approval out of pendingApprovals (see useChat.ts's
// 'tool-result' handler) and unmounts this component - it only owns the auto-deny countdown.
//
// A CARD IN PLACE OF ITS ROW since 2026-09-30 (the review's F1, approved by the maintainer). It was three
// 12px text links run together on the row itself - "Accept Accept All Deny" - while the lower-
// stakes askUser question got a full card with real buttons. It now has QuestionPrompt's shape:
// what will change and where, the arguments that say how, then three real buttons. The choices
// and what they send are unchanged: Allow once is accept_once, Allow for this conversation is
// accept_all (ChatService stops asking for the rest of the conversation), Deny is deny - and the
// time limit still ends in the same deny.
export function ToolApprovalPrompt({ approval, conversationId, onOpenDetail, from }: Props) {
  // The decision the user picked, or null while the card is still awaiting input. Deliberately NOT
  // cleared once the POST resolves: that only means the server un-blocked the ReAct loop, the tool
  // itself still has to run, and the row stays until its own 'tool-result' SSE event arrives
  // (which can take a while for a slow write call). Keeping it set holds the buttons disabled and
  // the spinner up for that whole window - otherwise the controls silently re-enable and the
  // countdown keeps ticking after an accept, so the card looks like nothing registered and a second
  // click (or the auto-deny at 0s) can still land on an already-resolved call. Reset only on
  // failure, where the toast tells the user to try again.
  const { t } = useTranslation()
  const decision = approval.decision ?? null
  const setDecision = useChatStore(s => s.setApprovalDecision)
  // Guards only the auto-deny path below from firing more than once (e.g. re-renders while
  // secondsLeft is pinned at 0, or a failed auto-deny clearing `decision` back to null).
  const autoDeniedRef = useRef(false)
  const secondsLeft = useApprovalCountdown(approval.requestedAt, APPROVAL_TIMEOUT_SECONDS)

  const resolve = async (choice: Decision) => {
    setDecision(approval.callId, choice, false)
    try {
      await chatApi.resolveToolApproval(conversationId, approval.callId, choice)
      setDecision(approval.callId, choice, true)
      // Accept All covers the calls already waiting beside this one - the server resolves them
      // with it (ChatService.resolveApproval) - so their prompts say so too, rather than asking
      // again about calls that are already running.
      if (choice === 'accept_all') setDecision(null, 'accept_all', true)
    } catch (e) {
      // The real reason, not a guess: a 404 is a call the server stopped waiting on (the turn
      // ended or was stopped); anything else carries its own sentence.
      useToastStore.getState().addToast(isGone(e)
        ? t('This request is no longer waiting for an answer.')
        : withReason(t('Could not send your decision'), e), 'danger')
      setDecision(approval.callId, undefined, false)
    }
  }

  // If the limit elapses with no user action, auto-deny exactly the same way the Deny button does.
  // Skipped once any decision is in flight so a click landing right at the boundary can't race a
  // second, redundant resolution.
  useEffect(() => {
    if (secondsLeft > 0 || autoDeniedRef.current || decision !== null) return
    autoDeniedRef.current = true
    void resolve('deny')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [secondsLeft, decision])

  // Once the server has the decision the card has done its job: the row says which way it went
  // (ApprovalOutcome, which ToolCallList renders on the row) while the tool itself runs.
  // `<Presence show={false} />` rather than null, in the card's place (the motion rule).
  if (decision && approval.decisionSent) return <Presence show={false} />

  const isSubmitting = decision !== null
  // What will change, and where - the call's own running phrase: "Commenting on PROJ-341".
  const action = toolActivity({ callId: approval.callId, toolName: approval.toolName, arguments: approval.arguments, done: false }, t)
  // The arguments that say HOW, minus any the sentence already names (the issue key, usually).
  const rows = (argumentRows(approval.arguments) ?? []).filter(r => !(r.value.length <= 42 && action.includes(r.value)))

  return (
    // The kit's PromptCard: the actions RIGHT-aligned, secondary then primary as every dialog
    // footer runs, and Deny last and set apart. It had them left under an indent with the primary
    // first, the one prompt of three that did.
    <PromptCard
      icon={PenLine}
      tone="warning"
      eyebrow={from}
      title={action}
      onTitleClick={onOpenDetail}
      ariaLabel={t('Approve this change')}
      busy={isSubmitting}
      // The countdown and the in-flight line share one slot, so nothing shifts on a click.
      // IN_FLIGHT_LABEL holds English keys; translated here, at the one place it is read.
      status={isSubmitting ? (
        <InlineNotice tone="progress">{t(IN_FLIGHT_LABEL[decision])}</InlineNotice>
      ) : (
        <Readout>{t('Denied automatically in {time}', { time: formatElapsed(secondsLeft * 1000, t) })}</Readout>
      )}
      actions={(
        <>
          <Button variant="secondary" size="sm" onClick={() => resolve('accept_all')} disabled={isSubmitting}>
            {t('Allow for this conversation')}
          </Button>
          <Button variant="primary" size="sm" onClick={() => resolve('accept_once')} disabled={isSubmitting}>
            {t('Allow once')}
          </Button>
          {/* Quiet danger: danger ink with no fill and no border, not a red fill. Denying is the
              safe choice - the filled red is for actions that destroy something. */}
          <Button variant="danger-quiet" size="sm" className="ml-2" onClick={() => resolve('deny')} disabled={isSubmitting}>
            {t('Deny')}
          </Button>
        </>
      )}
    >
      {rows.length > 0 && <ToolArguments rows={rows} compact />}
    </PromptCard>
  )
}

// Which way the decision went, on the row itself, once the server has it: the tool may take a
// while, and a spinner alone left the user unsure whether Allow or Deny had registered.
export function ApprovalOutcome({ approval }: { approval: PendingApproval }) {
  const { t } = useTranslation()
  // Fades in beside the row once the server has the decision.
  return (
    <Presence show={!!(approval.decision && approval.decisionSent)} collapse={false}>
      <Caption as="span" role="status" tone={approval.decision === 'deny' ? 'danger' : 'default'}>
        {approval.decision === 'deny' ? t('Denied') : t('Approved')}
      </Caption>
    </Presence>
  )
}
