import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { ArrowLeft, Bot, LogOut, Pause, Play, Square } from 'lucide-react'
import { ModalShell } from '@/components/ui/ModalShell'
import { DialogHeader, IconButton, MetaList, Readout, StateMark, workingMotion } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { formatUsd } from '@/utils/formatters'
import { tokenCountLabel } from '@/features/settings/utils/modelDefaults'
import { ChatInstanceProvider } from '../instance/ChatInstanceContext'
import { ChatWindow } from './ChatWindow'
import { AgentTranscriptContext } from './agentTranscriptContext'
import { useChat } from '../hooks/useChat'
import { useAgentControl, useAgentTree, type AgentAction } from '../hooks/useAgentTree'
import { useAgentModel } from '../hooks/useAgentModel'
import { useServerNow } from '../hooks/useServerNow'
import { agentFigures, buildTree, controlsFor, figuresLine, isBusy, isWorking, stopReasonOf, type TreeNode } from '../utils/agentTree'
import { agentStatusTitle, agentWorkState, lifetimeLabel } from '../utils/agentState'
import { backInTrail, backTarget, openInTrail, shownInTrail, type TranscriptTrail } from '../utils/agentTranscript'

// A DEPLOYED AGENT'S TRANSCRIPT, READ-ONLY (the maintainer, 2026-11-04: "A sub-agent's transcript (via
// the agents map or the main chat interface) should show a modal instead of opening it in a new
// conversation. The intention is not to allow the user to talk with it.").
//
// Over the page: who the agent is and where it stands - its status, what it runs on, its context, cost
// and time worked, as the agents map shows them - with Pause or Resume, Dismiss and Stop where the map
// offers them; under that its conversation, drawn by the chat's own thread (tool groups, tool rows,
// markdown, attachments) with no composer, no retry, no rewind and no question to answer. A write the
// agent waits on is still decided here: approving is no message to it (agentTranscript.ts).
//
// LIVE while it works: the dialog follows the agent's conversation on a chat instance of its own,
// opened with the dialog and let go with it (resetChat), and the thread keeps to its newest line unless
// the reader scrolled up (threadFollow.ts, inside ChatWindow). The agents it deployed open in this same
// dialog, one step deeper, with Back - never a dialog on a dialog for every level (the trail).
//
// No footer: there is nothing to do but read (frontend.md, "A FOOTER EXISTS ONLY FOR REAL ACTIONS").

/**
 * The transcript dialog and its opener, for whatever shows deployed agents: `open` a deployed agent's
 * conversation by its id, and render `dialog` where the page renders its dialogs. A second `open`
 * while it is up walks one step deeper in the same dialog.
 */
export function useAgentTranscript(): { open: (conversationId: string) => void; dialog: ReactNode } {
  const [trail, setTrail] = useState<TranscriptTrail>([])
  const open = useCallback((conversationId: string) => setTrail(t => openInTrail(t, conversationId)), [])
  const dialog = trail.length > 0
    ? <AgentTranscriptDialog trail={trail} onTrail={setTrail} onClose={() => setTrail([])} />
    : null
  return { open, dialog }
}

function AgentTranscriptDialog({ trail, onTrail, onClose }: {
  trail: TranscriptTrail
  onTrail: (next: TranscriptTrail) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  // One chat instance per dialog, so two dialogs (the map's page and a chat panel's) never share one.
  const instanceKey = `agent-transcript:${useId()}`
  return (
    // As wide as the chat itself (the maintainer, 2026-11-06: "sub agent transcript width should be equal to
    // the chat interface width"): the thread's 45rem column with the chat window's gutters and scrollbar
    // room - 50rem - rather than a roomy viewer whose margins were empty either side of the same column.
    <ModalShell onClose={onClose} ariaLabel={t('Agent transcript')} size="xl" fill panelClassName="max-w-[50rem]">
      <ChatInstanceProvider instanceKey={instanceKey}>
        <TranscriptBody trail={trail} onTrail={onTrail} />
      </ChatInstanceProvider>
    </ModalShell>
  )
}

function TranscriptBody({ trail, onTrail }: { trail: TranscriptTrail; onTrail: (next: TranscriptTrail) => void }) {
  const shown = shownInTrail(trail)!
  const back = backTarget(trail)
  // An agent's own agents open here, one step deeper.
  const deeper = useCallback((conversationId: string) => onTrail(openInTrail(trail, conversationId)), [trail, onTrail])
  return (
    <AgentTranscriptContext.Provider value={deeper}>
      {/* Keyed on the agent shown, so each step fades in whole and its thread is followed afresh. */}
      <div key={shown} className="flex-1 min-h-0 flex flex-col animate-fade-in">
        <TranscriptHeader conversationId={shown} back={back} onBack={() => onTrail(backInTrail(trail))} />
        <TranscriptThread conversationId={shown} />
      </div>
    </AgentTranscriptContext.Provider>
  )
}

// The agent's node in its tree - for its figures, which count the agents under it against its budget.
function findNode(nodes: TreeNode[], conversationId: string): TreeNode | undefined {
  for (const n of nodes) {
    if (n.agent.conversationId === conversationId) return n
    const below = findNode(n.children, conversationId)
    if (below) return below
  }
  return undefined
}

function TranscriptHeader({ conversationId, back, onBack }: {
  conversationId: string
  back: string | undefined
  onBack: () => void
}) {
  const { t } = useTranslation()
  const { agents, self, rootConversationId } = useAgentTree(conversationId)
  const { control, busy } = useAgentControl(conversationId)
  const mainModel = useAgentModel()
  const agent = self
  const working = !!agent && (isWorking(agent) || isBusy(agent.status))
  const now = useServerNow(working)
  const node = useMemo(() => (agent && rootConversationId ? findNode(buildTree(agents, rootConversationId), conversationId) : undefined),
    [agent, agents, rootConversationId, conversationId])

  const title = agent?.title || t('Deployed agent')
  const backName = back ? (agents.find(a => a.conversationId === back)?.title || t('Deployed agent')) : ''
  const state = agent ? (isWorking(agent) ? 'running' : agentWorkState(agent.status)) : 'done'
  const standing = agent ? agentStatusTitle(agent.status, stopReasonOf(agent), t) : ''
  const words = agent && agent.lifetime === 'LONG' ? `${standing} - ${lifetimeLabel(agent.lifetime, t)}` : standing
  // What it runs on: its own model where one was named for it, else the main agent's.
  const runsOn = agent?.model
    ? [agent.providerName, agent.model].filter(Boolean).join(' / ')
    : mainModel ? [mainModel.provider, mainModel.model].filter(Boolean).join(' / ') : null
  const figures = node ? figuresLine(agentFigures(node, agents, now), tokenCountLabel, formatUsd) : ''
  const controls = agent ? controlsFor(agent) : null
  const held = !!agent && busy === agent.agentId
  const act = (action: AgentAction) => { if (agent) void control(agent.agentId, action) }

  return (
    <DialogHeader
      icon={back ? (
        <IconButton icon={ArrowLeft} label={t('Back to {name}', { name: backName })} size="sm" onClick={onBack} className="animate-fade-in" />
      ) : <StateMark icon={Bot} state={state} blink={false} />}
      title={<span className={workingMotion('text', state === 'running')}>{title}</span>}
      subtitle={agent ? (
        <span className="animate-fade-in">
          <MetaList items={[words, runsOn]} />
          {figures && <Readout tone="caption" className="block text-xs animate-fade-in">{figures}</Readout>}
        </span>
      ) : null}
      actions={controls ? (
        <span className="flex items-center gap-1">
          {controls.pause && <HeaderAction icon={Pause} label={t('Pause this agent')} onClick={() => act('pause')} disabled={held} />}
          {controls.resume && <HeaderAction icon={Play} label={t('Resume this agent')} onClick={() => act('resume')} disabled={held} />}
          {controls.dismiss && <HeaderAction icon={LogOut} label={t('Dismiss this agent')} onClick={() => act('dismiss')} disabled={held} />}
          {controls.stop && <HeaderAction icon={Square} label={t('Stop this agent')} onClick={() => act('stop')} disabled={held} danger />}
        </span>
      ) : undefined}
    />
  )
}

function HeaderAction({ icon: Icon, label, onClick, disabled, danger }: {
  icon: typeof Pause
  label: string
  onClick: () => void
  disabled?: boolean
  danger?: boolean
}) {
  return <IconButton icon={Icon} label={label} size="sm" tone={danger ? 'danger' : 'default'} onClick={onClick} disabled={disabled} className="animate-fade-in" />
}

// The agent's conversation, followed live on this dialog's chat instance while the dialog shows it.
function TranscriptThread({ conversationId }: { conversationId: string }) {
  const {
    messages, streaming, toolCalls, loadingHistory, conversationId: openId, resumeConversation, resetChat,
    pendingApprovals, pendingEnableRequests, condensing, attachedToolCallCount, workflowRuns,
  } = useChat()
  const { self } = useAgentTree(conversationId)

  // Through refs: useChat's callbacks change identity every render.
  const calls = useRef({ resumeConversation, resetChat })
  calls.current = { resumeConversation, resetChat }
  useEffect(() => {
    void calls.current.resumeConversation(conversationId)
    // Closed, or another agent shown: its stream is let go of at once - the agent works on regardless.
    return () => calls.current.resetChat()
  }, [conversationId])

  // Given work again while the dialog is open (a LONG agent messaged by its parent, a resumed one): the
  // stream closed when its last turn ended, so its conversation is followed again once the tree - read
  // every few seconds while anything works - says it is at work and nothing here is following it.
  const working = !!self && (isWorking(self) || isBusy(self.status))
  const wasWorking = useRef(working)
  useEffect(() => {
    const rose = working && !wasWorking.current
    wasWorking.current = working
    if (rose && !streaming && !loadingHistory) void calls.current.resumeConversation(conversationId)
  }, [working, streaming, loadingHistory, conversationId])

  return (
    // The pinned question fades into the dialog's own plane, not the page's.
    <div className="flex-1 min-h-0 flex flex-col [--pin-plane:var(--surface-float)]">
      <ChatWindow
        transcript
        mailText
        messages={messages}
        streaming={streaming}
        toolCalls={toolCalls}
        loading={loadingHistory || openId !== conversationId}
        conversationId={openId}
        pendingApprovals={pendingApprovals}
        pendingEnableRequests={pendingEnableRequests}
        condensing={condensing}
        attachedToolCallCount={attachedToolCallCount}
        workflowRuns={workflowRuns}
      />
    </div>
  )
}
