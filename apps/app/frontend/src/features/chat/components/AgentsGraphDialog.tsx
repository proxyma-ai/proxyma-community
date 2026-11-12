import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowUpRight, Bot, LogOut, Pause, Play, Square } from 'lucide-react'
import { ModalShell } from '@/components/ui/ModalShell'
import {
  Caption, DialogBody, DialogHeader, DisclosureButton, HScroll, MetaList, Readout, StateMark, TABLE_CLASS,
  TABLE_FLUSH, TableEmptyRow, TableSkeletonRows, TD_COMPACT, Th, THEAD_TR, TR, Tooltip, IconButton, cn, workingMotion,
  type WorkState,
} from '@/components/ui/kit'
import { useTranslation, type Translate } from '@/i18n'
import { formatUsd } from '@/utils/formatters'
import { tokenCountLabel } from '@/features/settings/utils/modelDefaults'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useAgentModel, type AgentModel } from '../hooks/useAgentModel'
import { AgentSettingsLink } from './AgentSettingsLink'
import { useAgentControl, useAgentTree, type AgentAction } from '../hooks/useAgentTree'
import { useConversationUsage } from '../hooks/useConversationUsage'
import { useOpenAgentTranscript } from './agentTranscriptContext'
import { mapPick } from '../utils/agentTranscript'
import { useServerNow } from '../hooks/useServerNow'
import {
  agentFigures, buildTree, childrenBusy, controlsFor, figuresLine, flattenTree, hms, isWorking, mainAgentStatus,
  mainFigures, scoreOf, stopReasonOf, totalCost, workingCount, type AgentFigures, type TreeAgent, type TreeRow,
} from '../utils/agentTree'
import {
  agentStatusTitle, agentWorkState, lifetimeLabel, mainAgentStatusTitle, mainAgentWorkState,
} from '../utils/agentState'

// THE AGENTS MAP, AS A TREE (since 2026-10-28). Every agent can deploy agents of its own, so the map
// is the conversation's whole tree of agents: the conversation the user opened first, then each
// agent it deployed and, under each, the agents that one deployed - with where each stands, what it
// spent, and Open, Pause or Resume, and Stop. It is the same tree from any conversation of it, a
// deployed agent's own included.
//
// A DEPLOYED AGENT'S NAME OPENS ITS TRANSCRIPT (since 2026-11-04, the maintainer: "should show a
// modal instead of opening it in a new conversation. The intention is not to allow the user to talk
// with it."): a read-only dialog over the map (AgentTranscriptDialog), which Escape closes back to the
// map. The main agent's name goes to its latest turn when its conversation is the one open
// (threadReveal.ts), else to its conversation - the one the person talks to (agentTranscript.ts, mapPick).
//
// TWO COLUMNS AND THE ACTIONS (the maintainer's feedback, 2026-11-03). Under each name what it runs
// on; under each status its figures - the context in use, what it spent and how long it has run,
// each against its limit only while it can still spend against it (agentTree.ts, limitsShown): an
// agent that is done, stopped or failed shows its figures alone. The Input, Output and Cost columns
// went - the cost is in the figures, and the whole branch's is in their tooltip.
//
// THE MAIN AGENT IS A ROW LIKE THE OTHERS (the same feedback: "Main agent must have Idle status. Its
// cost and run time must be shown."): Working, Idle, waiting for approval or an answer, rate limited
// or waiting on its agents (mainAgentStatus), and under it its context, its OWN conversation's spend
// and its time worked - the wall clock of its turns (the server's AgentTreeView.main).
//
// TREE LINES (the same feedback: "should have the tree lines"): a line drops from each agent's mark
// to its agents' marks, a tee to each and an elbow to the last, every mark on one 24px column per
// depth (TreeLines). Drawn from sm up only - a phone stacks each row as a block of its own.
//
// Unchanged: a working agent's name waves (working.ts); the button that opens this blinks while any
// agent of the tree works (ChatComposer); cost shows only where something was priced, since $0 would
// read as "free"; no footer. LIVE while anything works - the tree is read again every few seconds
// (useAgentTree), the stream's 'agent-status' events move a row the moment its agent does, and a
// working agent's time counts by the second.

// Where a row's content starts, by its depth: one 24px column per level (the deployment's maximum is
// five), so every mark of a depth sits on one column and the lines meet it square.
const INDENT = ['', 'sm:pl-6', 'sm:pl-12', 'sm:pl-[72px]', 'sm:pl-24', 'sm:pl-[120px]', 'sm:pl-36', 'sm:pl-[168px]']

/** A model with its provider, as the composer names it: "Provider / model". */
function modelWords(m: Pick<AgentModel, 'provider' | 'model'>): string {
  return [m.provider, m.model].filter(Boolean).join(' / ')
}

export function AgentsGraphDialog({ conversationId, onClose }: { conversationId: string; onClose: () => void }) {
  const { t, locale } = useTranslation()
  const navigate = useNavigate()
  const agentModel = useAgentModel()
  const { data, agents, isLoading, rootConversationId } = useAgentTree(conversationId)
  const { data: usage } = useConversationUsage(conversationId)
  const turnRunning = useChatStore(st => st.streaming)
  const pendingApprovals = useChatStore(st => st.pendingApprovals)
  const pendingEnableRequests = useChatStore(st => st.pendingEnableRequests)
  const pendingQuestions = useChatStore(st => st.pendingQuestions)
  const messages = useChatStore(st => st.messages)
  const revealInThread = useChatStore(st => st.revealInThread)
  const { control, busy } = useAgentControl(conversationId)
  const [folded, setFolded] = useState<ReadonlySet<string>>(new Set())

  const root = rootConversationId ?? conversationId
  const rows = flattenTree(buildTree(agents, root), folded)
  const spent = totalCost(agents)
  const atRoot = root === conversationId
  const main = data?.main

  // Where the main agent stands: what the page itself knows while its conversation is the one open -
  // its stream is live - and what the server's poll says otherwise, and of what the page cannot see
  // (a rate limit, a write in the approvals inbox, its agents).
  const mainStatus = mainAgentStatus({
    working: atRoot ? turnRunning : !!main?.working,
    awaitingApproval: !!main?.awaitingApproval || (atRoot && (
      pendingApprovals.some(a => !(a.decision && a.decisionSent)) || pendingEnableRequests.length > 0)),
    awaitingAnswer: atRoot && pendingQuestions.some(q => !q.sent),
    rateLimited: !!main?.rateLimited,
    waiting: !!main?.waiting || childrenBusy(root, agents, {}),
  })
  const mainWorking = mainStatus === 'WORKING' || mainStatus === 'RATE_LIMITED'
  // A working agent's time counts by the second; nothing ticks while nothing works.
  const now = useServerNow(workingCount(agents) > 0 || mainWorking)
  // Its context is the composer's meter while it is the conversation open - that one is live.
  const mainContext = atRoot && usage && usage.contextTokens > 0
    ? { used: usage.contextTokens, window: usage.contextTokenLimit > 0 ? usage.contextTokenLimit : null }
    : null
  const mainF = mainFigures(main, now, mainContext)
  const mainLine = figuresLine(mainF, tokenCountLabel, formatUsd)

  // A deployed agent's transcript over the map; the main agent's latest turn, or its conversation.
  const openTranscript = useOpenAgentTranscript()
  const open = (target: string) => {
    const pick = mapPick({ target, openConversationId: conversationId, rootConversationId: root })
    if (pick.kind === 'transcript') {
      openTranscript?.(pick.conversationId)
      return
    }
    onClose()
    if (pick.kind === 'reveal') {
      if (messages.length > 0) revealInThread({ kind: 'main' })
      return
    }
    navigate(`/chat/${pick.to}`)
  }

  const toggle = (agentId: string) => setFolded(prev => {
    const next = new Set(prev)
    if (next.has(agentId)) next.delete(agentId)
    else next.add(agentId)
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
        subtitle={t('Every agent in this conversation, and what each cost.')}
      />

      {/* ONE LINE, not three stat tiles (the UI review, 2026-10-28): how many, how many work, and
          what they cost where anything was priced. */}
      <div className="px-4 sm:px-6 pt-4 shrink-0">
        {data && (
          <Caption className="data-readout animate-fade-in">
            <MetaList items={[
              t('{count} deployed agent|{count} deployed agents', { count: agents.length }),
              t('{count} working now', { count: workingCount(agents) }),
              spent > 0 ? t('{cost} spent by deployed agents', { cost: formatUsd(spent) }) : null,
            ]} />
          </Caption>
        )}
      </div>

      {/* The one region that scrolls. */}
      <DialogBody scroll className="pt-3">
        <HScroll>
          <table className={cn(TABLE_CLASS, TABLE_FLUSH)}>
            <thead>
              <tr className={THEAD_TR}>
                <Th compact>{t('Agent')}</Th>
                <Th compact>{t('Status')}</Th>
                <Th compact><span className="sr-only">{t('Actions')}</span></Th>
              </tr>
            </thead>
            <tbody>
              {isLoading && !data ? (
                <TableSkeletonRows compact rows={3} columns={['w-40', 'w-32', 'w-8']} />
              ) : (
                <>
                  {/* The conversation the user opened, at the top of the tree. */}
                  <tr className={cn(TR, 'animate-fade-in')}>
                    <td className={cn(TD_COMPACT, CELL_AGENT)}>
                      <TreeLines depth={0} guides={[]} last stem={rows.length > 0} />
                      <AgentName
                        title={t('Main agent')}
                        state={mainAgentWorkState(mainStatus)}
                        strong
                        here={atRoot}
                        onGo={() => open(root)}
                        subtitle={agentModel ? (
                          <AgentSettingsLink before={onClose} className="block truncate">{modelWords(agentModel)}</AgentSettingsLink>
                        ) : null}
                      />
                    </td>
                    <td data-label={t('Status')} className={cn(TD_COMPACT, CELL_STATUS)}>
                      <span className={STATUS_WORDS}>{mainAgentStatusTitle(mainStatus, t)}</span>
                      {mainLine && (
                        <Tooltip label={figuresWords(mainF, t, { worked: true, locale })}>
                          <Readout tone="caption" className={FIGURES}>{mainLine}</Readout>
                        </Tooltip>
                      )}
                    </td>
                    <td className={cn(TD_COMPACT, 'align-top')} />
                  </tr>
                  {rows.length === 0 ? (
                    <TableEmptyRow colSpan={3}>
                      {t('No deployed agents yet.')}
                    </TableEmptyRow>
                  ) : rows.map(row => (
                    <AgentTreeRow
                      key={row.node.agent.agentId}
                      row={row}
                      agents={agents}
                      now={now}
                      here={row.node.agent.conversationId === conversationId}
                      // An agent given no model of its own runs on the main agent's.
                      runsOn={row.node.agent.model
                        ? modelWords({ provider: row.node.agent.providerName ?? undefined, model: row.node.agent.model })
                        : agentModel ? modelWords(agentModel) : null}
                      folded={folded.has(row.node.agent.agentId)}
                      onToggle={() => toggle(row.node.agent.agentId)}
                      onGo={() => open(row.node.agent.conversationId)}
                      onControl={action => control(row.node.agent.agentId, action)}
                      held={busy === row.node.agent.agentId}
                    />
                  ))}
                </>
              )}
            </tbody>
          </table>
        </HScroll>
      </DialogBody>
    </ModalShell>
  )
}

// Every cell of a row starts at its top, so the name, the status and the actions share their first
// line - 24px, the height of a row's action button - and the tree lines meet each mark at its middle.
// The agent's cell holds its lines, drawn over it from edge to edge.
const CELL_AGENT = 'relative align-top max-w-[24rem]'
const CELL_STATUS = 'align-top text-ink-secondary'
const STATUS_WORDS = 'block whitespace-nowrap leading-6 transition-colors duration-200'
const FIGURES = 'block text-xs whitespace-nowrap animate-fade-in'

/** The context in use, against the window where it is known. */
function contextWords(used: number, window: number | null, t: Translate): string {
  return window
    ? t('Context {used} of {window}', { used: tokenCountLabel(used), window: tokenCountLabel(window) })
    : t('Context {used}', { used: tokenCountLabel(used) })
}

/**
 * The figures in words, one per line - the tooltip over the readouts under a status. `worked` for
 * the main agent, whose time is the wall clock of its turns; `branch`, what an agent and the agents
 * under it spent together, where it deployed any.
 */
function figuresWords(f: AgentFigures, t: Translate, opts: { worked?: boolean; branch?: number | null; locale?: string } = {}): string {
  const lines: string[] = []
  if (f.context) lines.push(contextWords(f.context.used, f.context.window, t))
  if (f.cost) {
    lines.push(f.cost.budget === null
      ? t('Spent {cost}', { cost: formatUsd(f.cost.spent) })
      : f.cost.withAgents
        ? t('Spent {cost} of its {budget} budget, with the agents it deployed', { cost: formatUsd(f.cost.spent), budget: formatUsd(f.cost.budget) })
        : t('Spent {cost} of its {budget} budget', { cost: formatUsd(f.cost.spent), budget: formatUsd(f.cost.budget) }))
  }
  if (opts.branch && !f.cost?.withAgents && opts.branch > (f.cost?.spent ?? 0)) {
    lines.push(t('With the agents it deployed: {cost}', { cost: formatUsd(opts.branch) }))
  }
  if (f.time) {
    lines.push(opts.worked
      ? t('Worked {time}, its turns added up', { time: hms(f.time.seconds) })
      : f.time.limitSeconds === null
        ? t('Ran {time}', { time: hms(f.time.seconds) })
        : t('Ran {time} of {limit}', { time: hms(f.time.seconds), limit: hms(f.time.limitSeconds) }))
    if (f.time.deadlineAt) {
      const at = new Date(f.time.deadlineAt).toLocaleString(opts.locale, { hour: '2-digit', minute: '2-digit', day: 'numeric', month: 'short' })
      lines.push(f.time.inherited
        ? t('Time limit {time}, set on an agent above it', { time: at })
        : t('Time limit {time}', { time: at }))
    }
  }
  return lines.join('\n')
}

function AgentTreeRow({ row, agents, now, here, runsOn, folded, onToggle, onGo, onControl, held }: {
  row: TreeRow
  agents: TreeAgent[]
  now: number
  here: boolean
  /** "Provider / model", or null where it cannot be told. */
  runsOn: string | null
  folded: boolean
  onToggle: () => void
  onGo: () => void
  onControl: (action: AgentAction) => void
  held: boolean
}) {
  const { t, locale } = useTranslation()
  const { node, depth } = row
  const a = node.agent
  const state: WorkState = isWorking(a) ? 'running' : agentWorkState(a.status)
  const controls = controlsFor(a)
  const standing = agentStatusTitle(a.status, stopReasonOf(a), t)
  const words = a.lifetime === 'LONG' ? `${standing} - ${lifetimeLabel(a.lifetime, t)}` : standing
  const hasChildren = node.children.length > 0
  const figures = agentFigures(node, agents, now)
  const line = figuresLine(figures, tokenCountLabel, formatUsd)
  // What it is and what checked it, beside its figures.
  const score = scoreOf(a.verification)
  const notes = [
    a.role === 'VERIFIER' ? t('Checks a report') : null,
    score ? t('Checked {supported} of {total} supported', { supported: score.supported, total: score.supported + score.unsupported + score.uncited }) : null,
  ].filter((x): x is string => !!x)
  return (
    <tr className={cn(TR, 'animate-fade-in')}>
      <td className={cn(TD_COMPACT, CELL_AGENT)}>
        <TreeLines depth={depth} guides={row.guides} last={row.last} stem={row.stem} />
        <AgentName
          title={a.title || t('Deployed agent')}
          state={state}
          here={here}
          onGo={onGo}
          indent={INDENT[Math.min(depth, INDENT.length - 1)]}
          subtitle={runsOn ? <span className="block truncate">{runsOn}</span> : null}
          after={hasChildren ? (
            <span className="shrink-0 flex">
              <DisclosureButton
                open={!folded}
                onToggle={onToggle}
                labels={{ show: t('Show the agents it deployed'), hide: t('Hide the agents it deployed') }}
              />
            </span>
          ) : null}
        />
      </td>
      <td data-label={t('Status')} className={cn(TD_COMPACT, CELL_STATUS)}>
        <span className={STATUS_WORDS}>{words}</span>
        {line && (
          <Tooltip label={figuresWords(figures, t, { branch: hasChildren ? node.subtreeCostUsd : null, locale })}>
            <Readout tone="caption" className={FIGURES}>{line}</Readout>
          </Tooltip>
        )}
        {notes.length > 0 && <Caption as="span" className="block animate-fade-in">{notes.join(' \u00b7 ')}</Caption>}
      </td>
      <td className={cn(TD_COMPACT, 'align-top whitespace-nowrap')}>
        <span className="flex items-center justify-end">
          <RowAction icon={ArrowUpRight} label={t('Open its transcript')} onClick={onGo} />
          {controls.pause && <RowAction icon={Pause} label={t('Pause this agent')} onClick={() => onControl('pause')} disabled={held} />}
          {controls.resume && <RowAction icon={Play} label={t('Resume this agent')} onClick={() => onControl('resume')} disabled={held} />}
          {controls.dismiss && <RowAction icon={LogOut} label={t('Dismiss this agent')} onClick={() => onControl('dismiss')} disabled={held} />}
          {controls.stop && <RowAction icon={Square} label={t('Stop this agent')} onClick={() => onControl('stop')} disabled={held} danger />}
        </span>
      </td>
    </tr>
  )
}

function RowAction({ icon: Icon, label, onClick, disabled, danger }: {
  icon: typeof Pause
  label: string
  onClick: () => void
  disabled?: boolean
  danger?: boolean
}) {
  return <IconButton icon={Icon} label={label} size="sm" tone={danger ? 'danger' : 'default'} onClick={onClick} disabled={disabled} className="animate-fade-in" />
}

/**
 * A row's TREE LINES, over its agent cell from its top edge to its bottom (the cell is `relative`;
 * a line runs one pixel past each edge so it closes over the row's border). Column k, 24px wide, is
 * where the line of the row's ancestor at depth k runs, 8px in - under the middle of that ancestor's
 * 16px mark. Its ancestors' columns carry a straight line where theirs runs on (`guides`); its own
 * parent's column a tee, or an elbow for the last of its parent's agents; and where its own agents
 * are shown, a line drops from under its mark (`stem`). The first line of every cell is 24px under
 * an 8px pad, so a mark's middle is 20px down.
 */
function TreeLines({ depth, guides, last, stem }: { depth: number; guides: boolean[]; last: boolean; stem: boolean }) {
  if (depth === 0 && !stem) return null
  return (
    <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 left-0 hidden sm:flex">
      {guides.map((on, i) => (
        <span key={i} className="relative w-6 shrink-0">
          {on && <span className="absolute left-2 -top-px -bottom-px w-px bg-line" />}
        </span>
      ))}
      {depth > 0 && (
        <span className="relative w-6 shrink-0">
          <span className={cn('absolute left-2 -top-px w-px bg-line', last ? 'h-[22px]' : '-bottom-px')} />
          <span className="absolute left-2 top-5 w-3 h-px bg-line" />
        </span>
      )}
      {stem && (
        <span className="relative w-6 shrink-0 animate-fade-in">
          <span className="absolute left-2 top-8 -bottom-px w-px bg-line" />
        </span>
      )}
    </span>
  )
}

// An agent's mark and name, and what it runs on under the name: the same mark and state colors a
// tool call's row uses (the kit StateMark), the robot for every agent, held still beside a wave
// across its name while it works (working.ts - words wave, an icon alone blinks). The name is a
// button that opens the agent's conversation - underlined under a mouse, as a tool row's name is;
// the one open says so. The first line is 24px whatever it holds, so the tree lines meet the mark at
// its middle; the subtitle starts under the name, past the mark's 16px slot and the gap.
function AgentName({ title, state, strong = false, here, onGo, indent, subtitle, after }: {
  title: string
  state: WorkState
  strong?: boolean
  here: boolean
  onGo: () => void
  /** Where its content starts, by its depth. */
  indent?: string
  /** What it runs on, under its name. */
  subtitle?: ReactNode
  /** Beside the name - the disclosure of the agents it deployed. */
  after?: ReactNode
}) {
  const { t } = useTranslation()
  const name = cn('min-w-0 truncate text-left text-ink-primary mouse:hover:underline underline-offset-2 focus-ring rounded-sm', strong && 'font-medium', workingMotion('text', state === 'running'))
  return (
    <span className={cn('block min-w-0', indent)}>
      {/* The mark, the name and the note on one line, apart (the review found the robot on a line
          of its own and "Main agentThis conversation" run together). */}
      <span className="flex items-center gap-2 min-w-0 h-6">
        <StateMark icon={Bot} state={state} blink={false} className="shrink-0" />
        <Tooltip label={here ? t('Go to its latest turn') : strong ? t('Open its conversation') : t('Open its transcript')}>
          <button type="button" onClick={onGo} className={name}>{title}</button>
        </Tooltip>
        {here && <span className="shrink-0 text-ink-caption">{t('This conversation')}</span>}
        {after}
      </span>
      {subtitle && <Caption as="span" className="block min-w-0 pl-6 animate-fade-in">{subtitle}</Caption>}
    </span>
  )
}
