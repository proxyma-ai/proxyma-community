import { CircleDot } from 'lucide-react'
import { Caption, Card, CardHeader, Disclosure, Presence, StateMark, cn, workingMotion, type WorkState } from '@/components/ui/kit'
import { MarkdownRenderer } from './MarkdownRenderer'
import { ToolCallList } from './ToolCallList'
import type { ToolCallInfo, WorkflowRunSnapshot, WorkflowRunStep } from '../types/chat.types'
import { useTranslation } from '@/i18n'
import { serverText } from '@/i18n/serverText'

// The plan a workflow run is following, and where in it the run has got to.
//
// This table is the whole reason a workflow is a different thing from a skill. A skill is
// instructions - the model reads them and decides what to do, so nothing can be drawn before it
// happens. A workflow is a program whose steps its author fixed, so the full list is drawn from
// the FIRST snapshot, every row pending, before any work has been done. It is a plan the reader can
// look ahead in, not a log that accretes.
//
// Specified against the backup file table in AdminPage, scaled down to sit inside a message.

// A step's ONE mark, the kit StateMark: the same glyph for every step, its color how the step
// stands - caption ink while it runs, the brand's "it worked", red when it failed. It was a spinner, a
// green check or a red cross, the shape the tool rows retired on 2026-10-03.
const STEP_STATE: Record<WorkflowRunStep['status'], WorkState> = {
  PENDING: 'pending',
  RUNNING: 'running',
  COMPLETED: 'done',
  FAILED: 'failed',
  // Never attempted, because an earlier step failed or the run was stopped - the SAME quiet mark a
  // step that has not started gets. Drawing it as a failure would claim a step failed when nothing
  // ever tried it, which is the same class of lie as narrating a refused tool call in the past
  // tense.
  SKIPPED: 'stopped',
}

interface Props {
  run: WorkflowRunSnapshot
  // The turn's tool calls. A step claims those carrying its own callId as parentCallId - the
  // nesting the backend set up so this needed no new plumbing.
  //
  // Only live calls carry that link: the persisted transcript has no parentCallId column (the same
  // limitation a delegated sub-agent's rows already have on reload), so a run read back from the
  // server shows its steps without their tool calls, and those calls appear in the flat list above
  // the final answer instead. Nothing is hidden either way - only the grouping is lost.
  toolCalls?: ToolCallInfo[]
}

export function WorkflowStepTable({ run, toolCalls = [] }: Props) {
  const { t } = useTranslation()
  if (!run.steps.length) return null

  const done = run.steps.filter(s => s.status === 'COMPLETED').length

  return (
    // Flush with the message column, as the tool rows and the answer are - the `px-3` it was set in
    // by was a leftover from message bubbles that had padding.
    <Card padding="none" className="mb-3 overflow-hidden">
      <CardHeader
        divided
        title={run.workflowName}
        // Deliberately a count and not a percentage or a bar: the steps themselves are right
        // underneath, so a second, vaguer rendering of the same fact would only compete with them.
        // A failed run says so here instead, because the reason is one row down and the header is
        // what the eye lands on first.
        readout={run.status === 'FAILED' ? t('Stopped') : `${done}/${run.steps.length}`}
      />
      {run.steps.map(step => (
        <StepRow
          key={step.index}
          step={step}
          toolCalls={toolCalls.filter(tc => tc.parentCallId === step.callId)}
        />
      ))}
    </Card>
  )
}

function StepRow({ step, toolCalls }: { step: WorkflowRunStep; toolCalls: ToolCallInfo[] }) {
  // Closed until the reader opens it, and it stays however they left it - the same rule
  // ToolCallList settled on, and for the same reason: a row that unfolds itself while a run
  // progresses shifts the thread under someone who is reading it.
  const hasDetail = !!step.instruction || !!step.output || !!step.failureMessage || toolCalls.length > 0

  // The row's line: its mark, its title (waving while it runs, its mark held still - working.ts),
  // and a failure's reason.
  const line = (
    <span className="flex items-center gap-2 min-w-0">
      <StateMark icon={CircleDot} state={STEP_STATE[step.status]} blink={false} />
      <span className={cn(
        'flex-1 truncate text-xs',
        step.status === 'PENDING' || step.status === 'SKIPPED' ? 'text-ink-caption' : 'text-ink-primary',
        workingMotion('text', step.status === 'RUNNING'),
      )}>
        {step.title}
      </span>
      <Presence show={step.status === 'FAILED' && !!step.failureMessage} collapse={false} className="shrink-0 min-w-0">
        <span className="block truncate max-w-[180px] text-xs text-danger">{serverText(step.failureMessage ?? '')}</span>
      </Presence>
    </span>
  )

  // The kit Disclosure (CHAT-22): its button, the trailing chevron, colour-only hover and the eased
  // body - this row built all four by hand, with a hover FILL the kit's rule forbids.
  return (
    <div className="border-b border-line/20 last:border-b-0">
      {hasDetail ? (
        <Disclosure summary={line} summaryClassName="px-4 sm:px-5 py-2 rounded-none">
          <div className="px-4 sm:px-5 pb-3 pl-10 sm:pl-11 space-y-2">
            {step.instruction && <Caption className="whitespace-pre-wrap">{step.instruction}</Caption>}
            {toolCalls.length > 0 && <ToolCallList toolCalls={toolCalls} />}
            {/* The step's own answer, rendered HERE rather than as its own bubble in the thread.
                Without that rule a five-step workflow produces five walls of text and this table
                decorates the mess it exists to replace. Only the last step's answer is the visible
                reply, and it is shown in both places on purpose - the row is where you go to
                compare it against the step that was asked for. */}
            {step.output && (
              <div className="text-sm">
                <MarkdownRenderer content={step.output} />
              </div>
            )}
          </div>
        </Disclosure>
      ) : (
        <div className="px-4 sm:px-5 py-2">{line}</div>
      )}
    </div>
  )
}
