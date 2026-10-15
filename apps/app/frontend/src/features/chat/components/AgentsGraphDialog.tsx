import { useState, type ReactNode } from 'react'
import { Bot } from 'lucide-react'
import { ModalShell } from '@/components/ui/ModalShell'
import {
  Collapse,
  Caption, DialogBody, DialogHeader, DisclosureButton, EmptyValue, HScroll, Presence, StatGrid, StatTile, StateMark, TABLE_CLASS,
  TABLE_FLUSH, TableEmptyRow, TableSkeletonRows, TD_COMPACT, Th, THEAD_TR, TR, cn, workingMotion, type WorkState,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useAgentModel } from '../hooks/useAgentModel'
import { useConversationAgents } from '../hooks/useConversationAgents'
import { useConversationUsage } from '../hooks/useConversationUsage'
import { useServerNow } from '../hooks/useServerNow'
import { formatElapsed } from '../utils/formatElapsed'
import { SubAgentSteps } from './SubAgentPanel'
import { ToolCallDetailModal } from './ToolCallDetailModal'
import { formatUsd } from '@/utils/formatters'
import {
  activeMs, compactTokens, elapsedMs, workSpans,
  type Spend, type SubAgentSpend,
} from '../utils/agentsGraph'
import { agentWorkState } from '../utils/agentState'
import { delegationCall } from '../utils/subAgent'
import type { RevealTarget } from '../utils/threadReveal'
import type { ToolCallInfo } from '../types/chat.types'

// THE AGENTS MAP (maintainer's request, 2026-10-03): every agent this conversation has used - the
// main one and each sub-agent, finished ones included - and what each has used, opened from beside
// the composer's provider and model.
//
// A TABLE since the 0.29.1 feedback (maintainer's, after a reference they attached): the
// conversation's figures across the top - how many sub-agents, the total time, the cost - and one
// row per agent with its model, time, input, output, CACHE WRITE AND CACHE HIT apart, context and
// cost. The main agent heads the table and its chevron folds the sub-agents under it; the drawn
// edges from it to each sub-agent are kept from the tree this was before.
//
// AN AGENT'S NAME GOES TO IT IN THE THREAD (maintainer's request, 2026-10-14): the map closes and
// the thread scrolls to that sub-agent's panel, which opens and lights up for a moment - or, for
// the main agent, to the latest turn (threadReveal.ts). The name is a button of its own.
//
// The rest of a sub-agent's row, and its chevron, open ITS BLOCK in place, under its row (0.29.1
// feedback): the same steps, text and report its panel shows in the thread (SubAgentSteps), so a
// run can be read here too. Clicking again folds it.
//
// TOTAL TIME is the wall clock: the time during which at least one agent worked - the main agent's
// turns and every sub-agent's run, overlaps counted once (activeMs). Not the sum of everyone's.
//
// Unchanged from before: a working agent's name waves (working.ts), so there is no "Running"; a
// sub-agent is running or finished (agentWorkState); "Background" is a dimmed word while one works;
// cost shows only where something was priced, since $0 would read as "free"; no footer. The main
// agent's figures are the conversation's totals less its sub-agents' (ConversationAgents on the
// backend), so they include naming and condensing the conversation. LIVE while anything works.

export function AgentsGraphDialog({ conversationId, onClose }: { conversationId: string; onClose: () => void }) {
  const { t } = useTranslation()
  const agentModel = useAgentModel()
  // The main agent's context is the conversation's own - what the composer's meter reads.
  const { data: usage } = useConversationUsage(conversationId)
  const { data, isLoading, live } = useConversationAgents(conversationId)
  const turnRunning = useChatStore(st => st.streaming)
  const messages = useChatStore(st => st.messages)
  const liveCalls = useChatStore(st => st.toolCalls)
  const revealInThread = useChatStore(st => st.revealInThread)
  // The server's clock, ticking while anything works - every time here is the server's.
  const now = useServerNow(live)
  const [subsOpen, setSubsOpen] = useState(true)
  const [openAgents, setOpenAgents] = useState<ReadonlySet<string>>(() => new Set())
  const [detail, setDetail] = useState<ToolCallInfo | null>(null)

  const subs = data?.subAgents ?? []
  const priced = (data?.total.costUsd ?? 0) > 0
  const columns = priced ? 9 : 8
  const totalMs = data ? activeMs(workSpans(data, now)) : null
  const mainMs = data?.turns?.length
    ? activeMs(data.turns.map(turn => [turn.startedAt, turn.endedAt ?? now] as [number, number]))
    : null

  // To an agent in the thread: the map closes first, and the thread goes there (ChatWindow).
  const goTo = (target: RevealTarget) => {
    onClose()
    revealInThread(target)
  }

  const toggleAgent = (id: string) => setOpenAgents(prev => {
    const next = new Set(prev)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })

  return (
    <ModalShell
      onClose={onClose}
      ariaLabel={t('Agents')}
      size="wide"
      // Capped rather than fixed (the kit's one cap): one agent is a short dialog, twenty scroll
      // inside the body.
      cap
    >
      <DialogHeader
        title={t('Agents')}
        subtitle={t('Who worked on this conversation, on which model, and what each cost.')}
      />

      <div className="px-4 sm:px-6 pt-4 shrink-0">
        <StatGrid columns={priced ? 3 : 2}>
          <StatTile label={t('Sub-agents')} value={data ? subs.length : null} loading={!data} />
          <StatTile
            label={t('Total time')}
            value={totalMs != null && totalMs >= 1000 ? formatElapsed(totalMs, t) : <EmptyValue />}
            loading={!data}
          />
          {priced && <StatTile label={t('Cost')} value={formatUsd(data!.total.costUsd)} />}
        </StatGrid>
      </div>

      {/* The one region that scrolls. */}
      <DialogBody scroll className="pt-3">
        <HScroll>
          <table className={cn(TABLE_CLASS, TABLE_FLUSH)}>
            <thead>
              <tr className={THEAD_TR}>
                <Th compact>{t('Agent')}</Th>
                <Th compact>{t('Provider / model')}</Th>
                <Th compact align="right">{t('Time')}</Th>
                <Th compact align="right">{t('Input')}</Th>
                <Th compact align="right">{t('Output')}</Th>
                <Th compact wrap align="right">{t('Cache write')}</Th>
                <Th compact wrap align="right">{t('Cache hit')}</Th>
                <Th compact align="right">{t('Context')}</Th>
                {priced && <Th compact align="right">{t('Cost')}</Th>}
              </tr>
            </thead>
            <tbody>
              {isLoading || !data ? (
                <TableSkeletonRows compact rows={3} columns={['w-28', 'w-32', 'w-10', 'w-10', 'w-10', 'w-10', 'w-10', 'w-16']} />
              ) : (
                <>
                  <AgentRow
                    name={
                      <span className="flex items-center gap-1.5 min-w-0">
                        <AgentName
                          title={t('Main agent')}
                          state={turnRunning ? 'running' : 'done'}
                          strong
                          onGo={messages.length > 0 ? () => goTo({ kind: 'main' }) : undefined}
                        />
                        <Presence show={subs.length > 0} collapse={false} className="flex">
                          <ChevronButton
                            open={subsOpen}
                            onToggle={() => setSubsOpen(!subsOpen)}
                            labels={{ show: t('Show sub-agents'), hide: t('Hide sub-agents') }}
                          />
                        </Presence>
                      </span>
                    }
                    model={agentModel ? [agentModel.provider, agentModel.model] : []}
                    duration={mainMs != null && mainMs >= 1000 ? formatElapsed(mainMs, t) : null}
                    spend={data.main}
                    priced={priced}
                    context={usage ? { used: usage.contextTokens ?? 0, window: usage.contextTokenLimit ?? 0 } : null}
                  />
                  {subs.length === 0 ? (
                    <TableEmptyRow colSpan={columns}>
                      {t('No sub-agents yet. The ones the agent starts appear here, finished ones included.')}
                    </TableEmptyRow>
                  ) : subsOpen && subs.map((a, i) => (
                    <SubAgentRows
                      key={a.id}
                      agent={a}
                      last={i === subs.length - 1}
                      now={now}
                      priced={priced}
                      columns={columns}
                      open={openAgents.has(a.id)}
                      onToggle={() => toggleAgent(a.id)}
                      // Where the thread keeps what it did, live and after a reload.
                      call={delegationCall(messages, liveCalls, a.id)}
                      onOpenDetail={setDetail}
                      onGo={() => goTo({ kind: 'agent', agentId: a.id })}
                    />
                  ))}
                </>
              )}
            </tbody>
          </table>
        </HScroll>
      </DialogBody>
      {detail && (
        <ToolCallDetailModal
          toolName={detail.toolName}
          arguments={detail.arguments}
          result={detail.result}
          onClose={() => setDetail(null)}
        />
      )}
    </ModalShell>
  )
}

function SubAgentRows({ agent, last, now, priced, columns, open, onToggle, call, onOpenDetail, onGo }: {
  agent: SubAgentSpend
  last: boolean
  now: number
  priced: boolean
  columns: number
  open: boolean
  onToggle: () => void
  call: ToolCallInfo | undefined
  onOpenDetail: (tc: ToolCallInfo) => void
  onGo: () => void
}) {
  const { t } = useTranslation()
  const ms = elapsedMs(agent, now)
  const state = agentWorkState(agent.status)
  return (
    <>
      <AgentRow
        clickable
        onClick={onToggle}
        name={
          // The edge from the main agent: a trunk down the left - stopping at the last sub-agent's
          // branch - and a branch to each at the height of its robot. Not on a phone, where each row
          // is a block of its own.
          <span className="relative flex items-center gap-1.5 min-w-0 sm:pl-6">
            <span aria-hidden="true" className={cn('hidden sm:block absolute left-[7px] -top-2 w-px bg-line', last ? 'h-[calc(50%+0.5rem)]' : '-bottom-2')} />
            <span aria-hidden="true" className="hidden sm:block absolute left-[7px] top-1/2 w-4 h-px bg-line" />
            {/* To its panel in the thread - only where the thread has one to go to. */}
            <AgentName title={agent.title || t('Sub-agent')} state={state} onGo={call ? onGo : undefined} />
            <Presence show={agent.mode === 'async' && (state === 'running' || state === 'pending')} collapse={false} className="shrink-0">
              <span className="text-ink-caption">{t('Background')}</span>
            </Presence>
            <ChevronButton open={open} onToggle={onToggle} labels={{ show: t('Show its steps'), hide: t('Hide its steps') }} />
          </span>
        }
        model={[agent.provider, agent.model]}
        duration={ms == null ? null : formatElapsed(ms, t)}
        spend={agent}
        priced={priced}
        context={agent.contextTokens ? { used: agent.contextTokens, window: agent.contextWindow ?? 0 } : null}
      />
      {/* Always in the table, so it can ease open and shut (the kit's Collapse); while shut the
          row has no border, or two hairlines would sit where one row is. */}
      <tr className={cn(TR, !open && 'border-b-0')}>
        <td colSpan={columns} className="p-0">
          <Collapse open={open} className="px-3 pb-3 sm:px-0">
            {/* Its block: the same body its panel in the thread has, starting where its row's robot
                does (0.29.2 feedback), on the dialog's own background with no box of its own
                (0.31.0 feedback). It GROWS inside the dialog's one scrolling body rather than
                scrolling in a box of its own - two scroll regions, one inside the other (CHAT-29).
                The indent is on the block, not the cell: this cell is its row's first, and the
                table's first-cell rule takes a cell's own left padding away from `sm` up. */}
            <div className="sm:ml-6 py-1 space-y-1">
              {call
                ? <SubAgentSteps call={call} steps={[]} onOpenDetail={onOpenDetail} />
                : <Caption>{t('Its steps are not loaded in this conversation.')}</Caption>}
            </div>
          </Collapse>
        </td>
      </tr>
    </>
  )
}

// The chevron TRAILING a name - the kit's DisclosureButton, the one chevron button the thread's
// panels use too (CHAT-21). Wrapped so a click on it is not also a click on the row it sits in.
function ChevronButton({ open, onToggle, labels }: {
  open: boolean
  onToggle: () => void
  labels: { show: string; hide: string }
}) {
  return (
    <span className="shrink-0 flex" onClick={e => e.stopPropagation()}>
      <DisclosureButton open={open} onToggle={onToggle} labels={labels} />
    </span>
  )
}

// An agent's mark and name: the same mark and state colors a tool call's row uses (the kit
// StateMark), the robot for every agent, held still beside a wave across its name while it works
// (working.ts - words wave, an icon alone blinks). With `onGo` the name
// is a button that goes to the agent in the thread - underlined under a mouse, as a tool row's name
// is; a click on it is not also a click on the row it sits in.
function AgentName({ title, state, strong = false, onGo }: {
  title: string
  state: WorkState
  strong?: boolean
  onGo?: () => void
}) {
  const { t } = useTranslation()
  const name = cn('min-w-0 truncate text-ink-primary', strong && 'font-medium', workingMotion('text', state === 'running'))
  return (
    <>
      <StateMark icon={Bot} state={state} blink={false} className="shrink-0" />
      {onGo ? (
        <button
          type="button"
          onClick={e => { e.stopPropagation(); onGo() }}
          aria-label={t('Go to {title} in the conversation', { title })}
          className={cn(name, 'text-left mouse:hover:underline underline-offset-2 focus-ring rounded-sm')}
        >
          {title}
        </button>
      ) : <span className={name}>{title}</span>}
    </>
  )
}

function AgentRow({ name, model, duration, spend, priced, context, clickable = false, onClick }: {
  name: ReactNode
  // Provider and model, either of which may be missing.
  model: (string | null | undefined)[]
  duration?: string | null
  spend: Spend
  // Whether anything in the conversation was priced - the Cost column is there if so.
  priced: boolean
  // How full the agent's context is; null where nothing has been measured yet.
  context: { used: number; window: number } | null
  // A sub-agent's row opens its steps. A pointer and no fill: the kit's interactive row tints on
  // focus-within, which stayed on after its chevron was clicked and sat apart from the dialog's
  // background (0.31.0 feedback).
  clickable?: boolean
  onClick?: () => void
}) {
  const { t } = useTranslation()
  const shownModel = model.filter(Boolean).join(' / ')
  const figure = cn(TD_COMPACT, 'text-right data-readout text-ink-secondary whitespace-nowrap')
  return (
    <tr className={cn(TR, 'animate-fade-in', clickable && 'cursor-pointer')} onClick={onClick}>
      <td className={cn(TD_COMPACT, 'max-w-[18rem]')}>{name}</td>
      <td data-label={t('Provider / model')} className={cn(TD_COMPACT, 'text-ink-secondary max-w-[14rem]')}>
        {shownModel ? <span className="block truncate" title={shownModel}>{shownModel}</span> : <EmptyValue />}
      </td>
      <td data-label={t('Time')} className={figure}>{duration ?? <EmptyValue />}</td>
      <td data-label={t('Input')} className={figure}>{compactTokens(spend.inputTokens)}</td>
      <td data-label={t('Output')} className={figure}>{compactTokens(spend.outputTokens)}</td>
      <td data-label={t('Cache write')} className={figure}>{compactTokens(spend.cacheWriteTokens)}</td>
      <td data-label={t('Cache hit')} className={figure}>{compactTokens(spend.cacheReadTokens)}</td>
      <td data-label={t('Context')} className={figure}>
        {/* ONE child: the stacked phone row lays a labelled cell out as label and value, and two
            children would be pulled apart. */}
        {context && context.used > 0
          ? (context.window > 0
              ? <span>{compactTokens(context.used)} <span className="text-ink-caption">/ {compactTokens(context.window)}</span></span>
              : compactTokens(context.used))
          : <EmptyValue />}
      </td>
      {priced && (
        <td data-label={t('Cost')} className={cn(TD_COMPACT, 'text-right data-readout text-ink-primary whitespace-nowrap')}>
          {formatUsd(spend.costUsd)}
        </td>
      )}
    </tr>
  )
}
