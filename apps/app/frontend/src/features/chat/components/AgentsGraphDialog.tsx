import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowUpRight, Bot, LogOut, Pause, Play, Square } from 'lucide-react'
import { ModalShell } from '@/components/ui/ModalShell'
import {
  Caption, DialogBody, DialogHeader, DisclosureButton, HScroll, MetaList, StateMark, TABLE_CLASS,
  TABLE_FLUSH, TableEmptyRow, TableSkeletonRows, TD_COMPACT, Th, THEAD_TR, TR, Tooltip, cn, iconButton, workingMotion,
  type WorkState,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { formatUsd } from '@/utils/formatters'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useAgentModel } from '../hooks/useAgentModel'
import { AgentSettingsLink } from './AgentSettingsLink'
import { useAgentControl, useAgentTree, type AgentAction } from '../hooks/useAgentTree'
import { useConversationUsage } from '../hooks/useConversationUsage'
import {
  buildTree, compactTokens, controlsFor, flattenTree, isWorking, scoreOf, totalCost, workingCount, type TreeNode,
} from '../utils/agentTree'
import { agentStatusLabel, agentWorkState, lifetimeLabel } from '../utils/agentState'

// THE AGENTS MAP, AS A TREE (since 2026-10-28). Every agent can deploy agents of its own, so the map
// is the conversation's whole tree of agents: the conversation the user opened first, then each
// agent it deployed and, indented under each, the agents that one deployed - with where each stands,
// what it spent, and Open, Pause or Resume, and Stop. It is the same tree from any conversation of
// it, a deployed agent's own included.
//
// AN AGENT'S NAME OPENS ITS CONVERSATION: the map closes and the page goes there, where the person
// can read what it did and talk to it. The conversation already open is marked, and its name goes
// to its latest turn instead (threadReveal.ts).
//
// Unchanged from the map before it: a working agent's name waves (working.ts); the button that
// opens this blinks while any agent of the tree works (ChatComposer); cost shows only where
// something was priced, since $0 would read as "free"; no footer. LIVE while anything works - the
// tree is read again every few seconds (useAgentTree), and the stream's 'agent-status' events
// move a row the moment its agent does.

// How far a row is indented, by its depth (the deployment's maximum is five). A phone stacks each row
// as a block of its own, where an indent has no meaning.
const INDENT = ['', 'sm:pl-6', 'sm:pl-12', 'sm:pl-16', 'sm:pl-20', 'sm:pl-24', 'sm:pl-28']

export function AgentsGraphDialog({ conversationId, onClose }: { conversationId: string; onClose: () => void }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const agentModel = useAgentModel()
  const { data, agents, isLoading, rootConversationId } = useAgentTree(conversationId)
  const { data: usage } = useConversationUsage(conversationId)
  const turnRunning = useChatStore(st => st.streaming)
  const messages = useChatStore(st => st.messages)
  const revealInThread = useChatStore(st => st.revealInThread)
  const { control, busy } = useAgentControl(conversationId)
  const [folded, setFolded] = useState<ReadonlySet<string>>(new Set())

  const root = rootConversationId ?? conversationId
  const rows = flattenTree(buildTree(agents, root), folded)
  const spent = totalCost(agents)
  const priced = spent > 0
  // Agent, where it stands, model, input, output, (cost), controls.
  const columns = priced ? 7 : 6
  const atRoot = root === conversationId

  // To a conversation of the tree. The one open goes to its latest turn rather than being reloaded.
  const open = (target: string) => {
    onClose()
    if (target === conversationId) {
      if (messages.length > 0) revealInThread({ kind: 'main' })
      return
    }
    navigate(`/chat/${target}`)
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
        subtitle={t('Every agent working on this conversation, the agents each deployed, and what each cost.')}
      />

      {/* ONE LINE, not three stat tiles (the UI review, 2026-10-28): how many, how many work, and
          what they cost where anything was priced. */}
      <div className="px-4 sm:px-6 pt-4 shrink-0">
        {data && (
          <Caption className="data-readout animate-fade-in">
            <MetaList items={[
              t('{count} deployed agent|{count} deployed agents', { count: agents.length }),
              t('{count} working now', { count: workingCount(agents) }),
              priced ? t('{cost} spent by deployed agents', { cost: formatUsd(spent) }) : null,
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
                <Th compact>{t('Model')}</Th>
                <Th compact align="right">{t('Input')}</Th>
                <Th compact align="right">{t('Output')}</Th>
                {priced && <Th compact align="right">{t('Cost')}</Th>}
                <Th compact><span className="sr-only">{t('Actions')}</span></Th>
              </tr>
            </thead>
            <tbody>
              {isLoading && !data ? (
                <TableSkeletonRows compact rows={3} columns={['w-28', 'w-16', 'w-32', 'w-10', 'w-10', 'w-8']} />
              ) : (
                <>
                  {/* The conversation the user opened, at the top of the tree. Its figures are known
                      here only while it is the one open. */}
                  <tr className={cn(TR, 'animate-fade-in')}>
                    <td className={cn(TD_COMPACT, 'max-w-[20rem]')}>
                      {/* The same flex line as every agent's row: the mark, the name and the note
                          on one line, apart (the review found the robot on a line of its own and
                          "Main agentThis conversation" run together). */}
                      <span className="flex items-center gap-2 min-w-0">
                        <AgentName
                          title={t('Main agent')}
                          state={atRoot && turnRunning ? 'running' : 'done'}
                          strong
                          here={atRoot}
                          onGo={() => open(root)}
                        />
                      </span>
                    </td>
                    {/* What is known, or nothing - never a dash standing in for it. */}
                    {/* A phone stacks a cell under its label: a cell with nothing to say names no label. */}
                    <td data-label={atRoot && turnRunning ? t('Status') : undefined} className={cn(TD_COMPACT, 'text-ink-secondary whitespace-nowrap')}>
                      {atRoot && turnRunning ? t('Working') : null}
                    </td>
                    <td data-label={agentModel ? t('Model') : undefined} className={cn(TD_COMPACT, 'text-ink-secondary max-w-[14rem]')}>
                      {agentModel ? (
                        <AgentSettingsLink before={onClose} className="block truncate">
                          {[agentModel.provider, agentModel.model].filter(Boolean).join(' / ')}
                        </AgentSettingsLink>
                      ) : null}
                    </td>
                    <Figure label={atRoot && usage ? t('Input') : undefined}>{atRoot && usage ? compactTokens(usage.inputTokens) : null}</Figure>
                    <Figure label={atRoot && usage ? t('Output') : undefined}>{atRoot && usage ? compactTokens(usage.outputTokens) : null}</Figure>
                    {priced && <Figure>{null}</Figure>}
                    <td className={TD_COMPACT} />
                  </tr>
                  {rows.length === 0 ? (
                    <TableEmptyRow colSpan={columns}>
                      {t('No deployed agents yet. The agents the agent deploys appear here, finished ones included.')}
                    </TableEmptyRow>
                  ) : rows.map(({ node, depth }) => (
                    <AgentTreeRow
                      key={node.agent.agentId}
                      node={node}
                      depth={depth}
                      here={node.agent.conversationId === conversationId}
                      priced={priced}
                      folded={folded.has(node.agent.agentId)}
                      onToggle={() => toggle(node.agent.agentId)}
                      onGo={() => open(node.agent.conversationId)}
                      onControl={action => control(node.agent.agentId, action)}
                      held={busy === node.agent.agentId}
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

function AgentTreeRow({ node, depth, here, priced, folded, onToggle, onGo, onControl, held }: {
  node: TreeNode
  depth: number
  here: boolean
  priced: boolean
  folded: boolean
  onToggle: () => void
  onGo: () => void
  onControl: (action: AgentAction) => void
  held: boolean
}) {
  const { t } = useTranslation()
  const a = node.agent
  const state: WorkState = isWorking(a) ? 'running' : agentWorkState(a.status)
  const controls = controlsFor(a)
  const words = a.lifetime === 'LONG' ? `${agentStatusLabel(a.status, t)} - ${lifetimeLabel(a.lifetime, t)}` : agentStatusLabel(a.status, t)
  const hasChildren = node.children.length > 0
  const cost = formatUsd(a.costUsd || 0)
  // Its limits, its verifier's score and why the tree stopped it, under its status.
  const score = scoreOf(a.verification)
  const notes = [
    a.role === 'VERIFIER' ? t('Checks a report') : null,
    a.budgetUsd ? t('Budget {budget}', { budget: formatUsd(a.budgetUsd) }) : null,
    a.deadlineAt && !a.endedAt ? t('Due {time}', { time: new Date(a.deadlineAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) }) : null,
    score ? t('Checked {supported} of {total} supported', { supported: score.supported, total: score.supported + score.unsupported + score.uncited }) : null,
    a.stopNote,
  ].filter((x): x is string => !!x)
  return (
    <tr className={cn(TR, 'animate-fade-in')}>
      <td className={cn(TD_COMPACT, 'max-w-[20rem]')}>
        <span className={cn('flex items-center gap-2 min-w-0', INDENT[Math.min(depth, INDENT.length - 1)])}>
          <AgentName title={a.title || t('Deployed agent')} state={state} here={here} onGo={onGo} />
          {hasChildren && (
            <span className="shrink-0 flex">
              <DisclosureButton
                open={!folded}
                onToggle={onToggle}
                labels={{ show: t('Show the agents it deployed'), hide: t('Hide the agents it deployed') }}
              />
            </span>
          )}
        </span>
      </td>
      <td data-label={t('Status')} className={cn(TD_COMPACT, 'text-ink-secondary')}>
        <span className="block whitespace-nowrap">{words}</span>
        {notes.length > 0 && <span className="block text-xs text-ink-caption data-readout">{notes.join(' \u00b7 ')}</span>}
      </td>
      <td data-label={a.model ? t('Model') : undefined} className={cn(TD_COMPACT, 'text-ink-secondary max-w-[14rem]')}>
        {a.model ? <span className="block truncate">{a.model}</span> : null}
      </td>
      <Figure label={t('Input')}>{compactTokens(a.inputTokens)}</Figure>
      <Figure label={t('Output')}>{compactTokens(a.outputTokens)}</Figure>
      {priced && (
        <td data-label={t('Cost')} className={cn(TD_COMPACT, 'text-right data-readout text-ink-primary whitespace-nowrap')}>
          {/* With agents under it, what the whole branch cost is a tooltip away. */}
          {hasChildren
            ? (
              <Tooltip label={t('With the agents it deployed: {cost}', { cost: formatUsd(node.subtreeCostUsd) })}>
                <span className="underline decoration-dotted underline-offset-2">{cost}</span>
              </Tooltip>
            )
            : cost}
        </td>
      )}
      <td className={cn(TD_COMPACT, 'whitespace-nowrap')}>
        <span className="flex items-center justify-end">
          <RowAction icon={ArrowUpRight} label={t('Open its conversation')} onClick={onGo} />
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
  return (
    <Tooltip label={label}>
      <button type="button" onClick={onClick} disabled={disabled} aria-label={label} className={cn(iconButton('sm', danger ? 'danger' : undefined), 'animate-fade-in')}>
        <Icon size={13} aria-hidden="true" />
      </button>
    </Tooltip>
  )
}

function Figure({ label, children }: { label?: string; children: ReactNode }) {
  return <td data-label={label} className={cn(TD_COMPACT, 'text-right data-readout text-ink-secondary whitespace-nowrap')}>{children}</td>
}

// An agent's mark and name: the same mark and state colors a tool call's row uses (the kit
// StateMark), the robot for every agent, held still beside a wave across its name while it works
// (working.ts - words wave, an icon alone blinks). The name is a button that opens the agent's
// conversation - underlined under a mouse, as a tool row's name is; the one open says so.
function AgentName({ title, state, strong = false, here, onGo }: {
  title: string
  state: WorkState
  strong?: boolean
  here: boolean
  onGo: () => void
}) {
  const { t } = useTranslation()
  const name = cn('min-w-0 truncate text-left text-ink-primary mouse:hover:underline underline-offset-2 focus-ring rounded-sm', strong && 'font-medium', workingMotion('text', state === 'running'))
  return (
    <>
      <StateMark icon={Bot} state={state} blink={false} className="shrink-0" />
      <Tooltip label={here ? t('Go to its latest turn') : t('Open its conversation')}>
        <button type="button" onClick={onGo} className={name}>{title}</button>
      </Tooltip>
      {here && <span className="shrink-0 text-ink-caption">{t('This conversation')}</span>}
    </>
  )
}
