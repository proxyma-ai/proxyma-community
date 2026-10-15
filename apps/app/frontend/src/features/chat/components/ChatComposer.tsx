import { useState, useRef, useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Clock, Plus, ArrowUp, Square, Paperclip, FileText, Settings as SettingsIcon, MessageCirclePlus, Workflow,
  type LucideProps,
} from 'lucide-react'
import { AgentsGraphDialog } from './AgentsGraphDialog'
import { MentionRichInput } from './MentionRichInput'
import { PermissionModePicker } from './PermissionModePicker'
import { FilePreviewDialog } from './FilePreviewDialog'
import { useAgentModel } from '../hooks/useAgentModel'
import { useConversationUsage } from '../hooks/useConversationUsage'
import { useUserQuota } from '../hooks/useUserQuota'
import { runningAgents } from '../utils/subAgent'
import { compactTokens } from '../utils/agentsGraph'
import { useChatStore } from '../instance/ChatInstanceContext'
import { formatSessionReset } from '../utils/formatSessionReset'
import type { ChatAttachment } from '../types/chat.types'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import {
  Caption, Chip, ComposerShell, FadeSwap, IconButton, IconCountButton, InlineNotice, MenuEmpty, MenuGroup, MenuRow, Meter, MetaList,
  Popover, Presence, ProgressBar, Readout, StateMark, Tooltip, UsageRing, cn, iconButton, useFilePicker, workingMotion,
} from '@/components/ui/kit'
import { skillsApi } from '@/features/settings/services/skillsApi'
import { SkillModal } from '@/features/settings/components/SkillsTab'
import { WorkflowModal } from '@/features/settings/components/WorkflowsTab'
import type { SkillDetail } from '@/features/settings/services/skillsApi'
import { keys } from '@/services/queryKeys'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'

// Every icon-only control in the chat input's toolbar (attach, send, stop, condense, new
// conversation, conversation history) is the kit's iconButton('md') - NO BACKGROUND AT ANY TIME, so the whole row reads as one consistent icon-button language instead of Send/Stop
// looking like a separate, heavier control.
//
// The soft tinted disc that used to appear on hover went on 2026-09-05, with every other one in
// the product: an icon-only button answers a pointer with its COLOR alone. Six glyphs each growing
// their own shaded circle turned a quiet toolbar into a row of buttons the moment the pointer
// crossed it, and the tint under a 16px glyph reads as a differently shaped control beside the
// text buttons it sits level with. A control's own state (a warning ring) is its color at rest,
// passed with tone null.

const ATTACHMENT_ACCEPT = '.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.msg,.epub,.txt,.csv,.md,.png,.jpg,.jpeg,.gif,.webp,.bmp,.tif,.tiff'

// One attached-file chip, above the editor in ChatComposer - the kit's `file` chip, the same chip
// the file is once the message is sent (MessageBubble's SentAttachment), so a file does not change
// shape on the way. Its mark is the upload's state (the kit StateMark: pulsing while it uploads,
// the brand's "it worked", red when it failed) - a spinner, a green tick and a red alert stood in
// front of it, and the chip itself turned green, which is the colour of a verdict, not of work.
function AttachmentChip({ attachment, onRemove }: { attachment: ChatAttachment; onRemove: () => void }) {
  const { file, status, progress, error } = attachment
  const { t } = useTranslation()
  // Previewed from the browser's own copy - the file need not have finished uploading.
  const [previewing, setPreviewing] = useState(false)
  return (
    // 14rem, the one cap a file chip has - in the composer and once sent (MessageBubble's
    // SentAttachment), so the chip does not change width on the way. It was 200px here, 220px there.
    <div className="flex flex-col gap-1 max-w-56 min-w-0">
      <Chip
        variant="file"
        icon={<StateMark icon={Paperclip} state={status === 'uploading' ? 'running' : status === 'failed' ? 'failed' : 'done'} />}
        label={file.name}
        onClick={() => setPreviewing(true)}
        onRemove={onRemove}
        removeLabel={t('Remove {name}', { name: file.name })}
      />
      <Presence show={status === 'uploading'}>
        <div className="flex items-center gap-1.5">
          <ProgressBar value={progress} size="xs" className="flex-1" />
          <Readout tone="caption" className="text-xs shrink-0">{progress}%</Readout>
        </div>
      </Presence>
      <Presence show={status === 'failed' && !!error}><Caption tone="danger" className="truncate">{error}</Caption></Presence>
      {previewing && <FilePreviewDialog source={{ name: file.name, blob: file }} onClose={() => setPreviewing(false)} />}
    </div>
  )
}

// Opens the conversation history modal - lives in the input's own toolbar (left of New
// Conversation) rather than a separate row above it, in both the hero and conversation views.
// Always visible (unlike New Conversation/Condense, which only show once there's an active
// conversation) - this is also what keeps the hero pill's right side from sitting empty against
// the attach button on the left. Plain icon + Tooltip, matching every other control in this
// toolbar (no hover-expand label) for visual consistency, and hovers to the app's neutral
// foreground color rather than the brand accent - a utility control, not a themed one.
//
// The kit's IconButton: the tooltip and the accessible name are one sentence.
function HistoryButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation()
  return (
    <IconButton
      icon={Clock}
      label={t('Conversation history')}
      onClick={onClick}
      // Anchor for the onboarding tour's "Your conversations" step (see tourSteps.ts). Safe to put
      // on the component itself rather than one call site: only one of the two views is mounted.
      data-tour-id="tour-chat-history"
    />
  )
}

// Popover replacing the old single-purpose "click to open file picker" Paperclip button.
// "Attach files" behaves exactly as before; "Skills" is a row shaped just like it (icon + title)
// but with a trailing chevron that opens a nested submenu on hover or click, listing every skill
// visible to this user. Picking one inserts an inline "/SkillName " mention into the message text
// (exactly like an "@mention" - see MentionRichInput's own "/" trigger, which does the same thing
// when typed directly). Sending that message loads the skill into the conversation for good: its
// full instructions are put into the conversation's history as a message at the point it was
// picked, and stay in effect from there on (see ChatService.applyPickedSkills - the system prompt
// itself only ever advertises names). Picking the same skill again later costs a one-line pointer
// back to those instructions, not a second copy. Nothing in the input advertises the standing set,
// and there is no unload: a loaded skill leaves only with the conversation. Always-on skills (see
// Settings > Skills) are preloaded into every conversation the same way, without being picked.
//
// Both the main menu and the Skills submenu are the kit's Popover - portaled to document.body and
// placed against the viewport, rather than `absolute` inside this component's own DOM position:
// the input pill sits at the very bottom of the page, so an `absolute`-positioned panel routinely
// got clipped by the page's own scroll container. Their rows are the kit's MenuRow.
function AttachMenu({ onAttachFiles, value, onChange, skillMentions, onSkillMentionsChange }: {
  onAttachFiles: () => void
  value: string
  onChange: (v: string) => void
  skillMentions: string[]
  onSkillMentionsChange: (names: string[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [skillsSubOpen, setSkillsSubOpen] = useState(false)
  const skillsRowRef = useRef<HTMLDivElement | null>(null)
  const [creatingSkill, setCreatingSkill] = useState(false)
  const [creatingWorkflow, setCreatingWorkflow] = useState(false)
  const { t } = useTranslation()
  const { data: skills = [] } = useQuery({ queryKey: keys.chatSkills(), queryFn: skillsApi.list, enabled: open })

  const closeAll = () => { setOpen(false); setSkillsSubOpen(false) }

  const pickSkill = (name: string) => {
    if (!skillMentions.includes(name)) {
      // Inserted at the end of the message, not at an arbitrary caret position - this menu can't
      // reach into MentionRichInput's own contentEditable DOM from outside it, but its external-
      // value-sync effect already handles "value changed out from under me" (e.g. rewind
      // prefill), so appending the token here and letting that effect rebuild the chip works the
      // same way.
      onChange(value.length > 0 && !value.endsWith(' ') ? `${value} /${name} ` : `${value}/${name} `)
      onSkillMentionsChange([...skillMentions, name])
    }
    closeAll()
  }

  return (
    <div>
      <Popover
        open={open}
        onOpenChange={o => { setOpen(o); if (!o) setSkillsSubOpen(false) }}
        placement="top-start"
        widthClass="w-64"
        ariaLabel={t('Add files, select a skill, and more')}
        trigger={({ toggle, ref }) => (
          <IconButton
            ref={ref}
            icon={Plus}
            label={t('Add files, select a skill, and more')}
            onClick={toggle}
            aria-haspopup="menu"
            aria-expanded={open}
          />
        )}
      >
        {() => (
          <>
            <MenuGroup>
              <Tooltip label={t('Attach one or more files to this message')} fullWidth>
                <MenuRow icon={<Paperclip />} title={t('Attach files')} onSelect={() => { onAttachFiles(); closeAll() }} />
              </Tooltip>
            </MenuGroup>
            <MenuGroup>
              {/* The Skills row opens its submenu on hover or click - opens only, never toggles
                  closed: a real click always starts with the pointer entering this row, which has
                  already opened it, and a toggle closed what the hover had just opened. The
                  submenu is a Popover of its own, which stops its presses from reaching the
                  document so this menu does not take them for a press outside it. It opens BESIDE
                  its row (right-start, flipping left where there is no room): opened top-start it
                  landed over the attach menu it came out of. */}
              <Popover
                open={skillsSubOpen}
                onOpenChange={setSkillsSubOpen}
                placement="right-start"
                widthClass="w-64"
                ariaLabel={t('Skills')}
                trigger={({ ref }) => (
                  // ArrowRight opens the submenu from the keyboard, as a desktop menu's does - the
                  // menus' keyboard (useMenuFocus) then moves focus onto its first row.
                  <div
                    ref={el => { skillsRowRef.current = el; ref(el) }}
                    onMouseEnter={() => setSkillsSubOpen(true)}
                    onKeyDown={e => { if (e.key === 'ArrowRight') { e.preventDefault(); e.stopPropagation(); setSkillsSubOpen(true) } }}
                  >
                    <Tooltip label={t('Pick a skill to use for this message')} fullWidth>
                      <MenuRow icon={<FileText />} title={t('Skills')} trailing="chevron" onSelect={() => setSkillsSubOpen(true)} />
                    </Tooltip>
                  </div>
                )}
              >
                {() => (
                  // ArrowLeft goes back to the Skills row, as Escape does.
                  <div
                    onPointerDown={e => e.stopPropagation()}
                    onMouseLeave={() => setSkillsSubOpen(false)}
                    onKeyDown={e => {
                      if (e.key !== 'ArrowLeft') return
                      e.preventDefault()
                      e.stopPropagation()
                      setSkillsSubOpen(false)
                      skillsRowRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus()
                    }}
                  >
                    <MenuGroup>
                      {skills.length === 0 ? (
                        <MenuEmpty>{t('No skills yet.')}</MenuEmpty>
                      ) : (
                        <div className="max-h-56 overflow-y-auto animate-fade-in">
                          {skills.map(s => (
                            <Tooltip key={s.id} label={s.shortDescription} fullWidth>
                              <MenuRow
                                icon={<FileText />}
                                title={s.name}
                                onSelect={() => pickSkill(s.name)}
                              />
                            </Tooltip>
                          ))}
                        </div>
                      )}
                    </MenuGroup>
                    <MenuGroup>
                      <Tooltip label={t('Open skill settings')} fullWidth>
                        <MenuRow
                          icon={<SettingsIcon />}
                          title={t('Manage skills')}
                          onSelect={() => { useSettingsModalStore.getState().open('Skills'); closeAll() }}
                        />
                      </Tooltip>
                      <Tooltip label={t('Create a new skill')} fullWidth>
                        <MenuRow tone="brand" icon={<Plus />} title={t('Create new skill')} onSelect={() => { setCreatingSkill(true); closeAll() }} />
                      </Tooltip>
                      {/* No workflow SUB-menu beside the skills one: picking an existing workflow is
                          already what typing "/" does, and both kinds live in that one list. What has
                          no other route from here is creating one, so that is the only entry added.
                          Hidden with the rest of the feature as of 0.1.1 - see FEATURES.workflows. */}
                      {FEATURES.workflows && (
                        <Tooltip label={t('Create a new workflow')} fullWidth>
                          <MenuRow tone="brand" icon={<Plus />} title={t('Create new workflow')} onSelect={() => { setCreatingWorkflow(true); closeAll() }} />
                        </Tooltip>
                      )}
                    </MenuGroup>
                  </div>
                )}
              </Popover>
            </MenuGroup>
          </>
        )}
      </Popover>
      {creatingSkill && (
        <SkillModal
          skill={null}
          onClose={() => setCreatingSkill(false)}
          onSaved={(created: SkillDetail) => pickSkill(created.name)}
        />
      )}
      {/* pickSkill by name, for a workflow: both kinds are picked with "/" and both travel to the
          backend in the same skillMentions list, which resolves a workflow first and treats the
          rest as skills. One chip type on screen, one field on the wire. */}
      {creatingWorkflow && (
        <WorkflowModal
          workflow={null}
          onClose={() => setCreatingWorkflow(false)}
          onSaved={(created) => pickSkill(created.name)}
        />
      )}
    </div>
  )
}

// The glyph of Stop, filled, and the same glyph blinking once Stop is pressed and the turn has not
// yet ended - an icon alone blinks while it works (working.ts). Module-level, so the button does
// not remount its glyph on every render.
function StopGlyph(props: LucideProps) {
  return <Square {...props} fill="currentColor" />
}
function StoppingGlyph(props: LucideProps) {
  return <Square {...props} fill="currentColor" className={workingMotion('icon', true)} />
}

// How long the "N tokens freed from context window" confirmation stays up before the indicator
// goes back to being just a usage ring (or disappears, which is the usual outcome - a successful
// condensation normally drops usage back under the 60% threshold that shows it at all).
const CONDENSE_RESULT_MS = 5000

// The conversation's context-window indicator, in all three of its states. One component rather
// than three, because they are the same control at different moments and the transitions between
// them are the point:
//
//  - Idle: a usage ring, shown once the context window crosses 60% - well before
//    SmartChatMemory's own 80% auto-condense trigger fires mid-turn - so the user can free space
//    pre-emptively instead of having a later response stall to do it for them. Clicking condenses.
//  - Condensing: the ring is REPLACED by the kit's progress line, "Condensing conversation". This
//    is the only place that state is reported now; it used to be a line in the transcript, which
//    put a progress message among the messages, left the ring sitting next to it still showing a
//    number that was about to change, and scrolled out of view exactly when the user went looking
//    for it.
//  - Just finished: a check in the brand ink and what the wait bought, for CONDENSE_RESULT_MS. Same
//    tick-then-revert language as the copy and share buttons in a message's action row.
function ContextWindowIndicator({ conversationId, condensing, tokensFreed, onCondense }: {
  conversationId?: string
  condensing: boolean
  tokensFreed: number | null
  onCondense: () => void
}) {
  const { data } = useConversationUsage(conversationId)
  const [showResult, setShowResult] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const { t } = useTranslation()

  // Keyed off the arrival of a figure rather than off `condensing` going false, so a condensation
  // that freed nothing (aborted, nothing condensable) reports nothing instead of flashing a tick.
  useEffect(() => {
    if (tokensFreed == null) return
    setShowResult(true)
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => setShowResult(false), CONDENSE_RESULT_MS)
    return () => { if (timerRef.current) clearTimeout(timerRef.current) }
  }, [tokensFreed])

  const contextTokenLimit = data?.contextTokenLimit ?? 0
  const contextTokens = data?.contextTokens ?? 0
  const pct = contextTokenLimit > 0 ? (contextTokens / contextTokenLimit) * 100 : 0
  // The ring alone is threshold-gated; the other two states are things that are happening or have
  // just happened, and must show regardless of where usage currently sits - the whole point of a
  // condensation is that it takes usage back below the threshold.
  const show = condensing || showResult || (!!conversationId && pct >= 60)

  // In a row, so it only fades as it comes and goes (Presence collapse={false}); its three states
  // swap by a fade of their own (FadeSwap). Both were framer-motion, which reduced motion could not
  // stop.
  const state = condensing ? 'condensing' : showResult ? 'result' : 'ring'
  return (
    <Presence show={show} collapse={false} className="flex items-center min-w-0">
      <FadeSwap swapKey={state} className="flex items-center min-w-0">
        {condensing ? (
          <InlineNotice tone="progress" className="items-center whitespace-nowrap">{t('Condensing conversation')}</InlineNotice>
        ) : showResult ? (
          // "It worked" in the brand ink, as every finished piece of work: the kit's `done` tone.
          // The count is a readout ("1.2k"), the sentence around it is prose - so the number goes
          // in as a parameter rather than the sentence being cut in two.
          <InlineNotice tone="done" className="items-center min-w-0 [&>span]:truncate">
            {t('{count} tokens freed from context window', { count: compactTokens(tokensFreed ?? 0) })}
          </InlineNotice>
        ) : (
          <Tooltip label={t('Context window is {pct}% full. Condense now to free up space, or start a new conversation for the best experience.', { pct: Math.round(pct) })}>
            <button
              type="button"
              onClick={onCondense}
              className={cn(iconButton('md', null), 'text-warning mouse:hover:text-warning/80')}
              aria-label={t('Condense conversation - context window {pct}% full', { pct: Math.round(pct) })}
            >
              <UsageRing value={pct} />
            </button>
          </Tooltip>
        )}
      </FadeSwap>
    </Presence>
  )
}

// One row inside the QuotaIndicator popover - the kit's Meter: its name over the bar, the reset
// line under it on the left and the percentage on the right, coloured by the one quota rule every
// meter follows (warning from 80%, danger from 90% - meterTier). It was a hand-made copy with its
// own thresholds, so one session quota could read in two colours here and in Settings. The
// footnote is computed by the caller (`resetLabel`), because the three windows say different
// things: Weekly/Monthly state an absolute reset time, while the Session (5h) row is a rolling
// window that either has not started yet or is running, in which case a countdown answers the
// actual question. `null` shows no footnote.
function QuotaRow({ label, pct, resetLabel }: {
  label: string
  pct: number
  resetLabel: string | null
}) {
  return <Meter used={pct} limit={100} percentOnly label={label} caption={resetLabel ?? undefined} />
}

// Proactive usage-limit indicator, shown once session (5h) spend reaches 80% of quota - mirrors
// the context ring's look (same ring, same icon-button shape) but click opens a
// popover with session/weekly/monthly detail (see QuotaRow) instead of taking an action, since
// unlike condensing there's nothing for the user to directly trigger here. Server-mode +
// Enterprise Server only - useUserQuota() is disabled entirely elsewhere, so `quota` stays
// undefined and this renders nothing.
function QuotaIndicator() {
  const { data: quota } = useUserQuota()
  const { t, locale } = useTranslation()

  // Early return (after every hook above, per Rules of Hooks) rather than a derived boolean -
  // narrows `quota` to the `enabled: true` branch of the UserQuotaStatus union for every access
  // below, which a separately-named `const enabled = quota?.enabled` flag would not do.
  // `<Presence show={false} />` rather than null, in the place the indicator stands (the motion rule).
  if (!quota?.enabled) return <Presence show={false} />

  const sessionPct = quota.fiveHourLimitUsd > 0 ? (quota.fiveHourSpendUsd / quota.fiveHourLimitUsd) * 100 : 0
  const weeklyPct = quota.weeklyLimitUsd > 0 ? (quota.weeklySpendUsd / quota.weeklyLimitUsd) * 100 : 0
  const monthlyPct = quota.effectiveQuotaUsd > 0 ? (quota.currentCycleSpendUsd / quota.effectiveQuotaUsd) * 100 : 0
  const show = sessionPct >= 80
  const danger = sessionPct >= 100

  return (
    <Presence show={show} collapse={false} className="flex">
          {/* The kit Popover - a non-menu panel, anchored to the ring and opening upward. */}
          <Popover
            placement="top-start"
            widthClass="w-72"
            role="dialog"
            panelClassName="p-4 space-y-3"
            ariaLabel={t('Usage quota - session {pct}% full', { pct: Math.round(sessionPct) })}
            trigger={({ toggle, ref }) => (
              <Tooltip label={t('Session usage is {pct}% of quota. Click for details.', { pct: Math.round(sessionPct) })}>
                <button
                  ref={ref}
                  type="button"
                  onClick={toggle}
                  className={cn(iconButton('md', null), danger ? 'text-danger' : 'text-warning')}
                  aria-label={t('Usage quota - session {pct}% full', { pct: Math.round(sessionPct) })}
                >
                  <UsageRing value={sessionPct} />
                </button>
              </Tooltip>
            )}
          >
            {() => (
              <>
                {/* The timestamps in the APP's language (toLocaleString(locale)), not the
                    browser's - a Vietnamese page printed its dates in the browser's English. */}
                <QuotaRow label={t('Session (5h)')} pct={sessionPct} resetLabel={formatSessionReset(quota.fiveHourSpendUsd, quota.fiveHourResetsAt, t)} />
                <QuotaRow label={t('Weekly')} pct={weeklyPct} resetLabel={t('Resets {at}', { at: new Date(quota.weeklyResetsAt).toLocaleString(locale) })} />
                <QuotaRow label={t('Monthly')} pct={monthlyPct} resetLabel={t('Resets {at}', { at: new Date(quota.cycleResetsAt).toLocaleString(locale) })} />
              </>
            )}
          </Popover>
    </Presence>
  )
}

// The provider and model answering this conversation, beside the + button (maintainer's request,
// 2026-09-24). Shown only where the reader can already read the agent's settings: Desktop, and
// administrators on a server. Everyone else sees nothing - on a server the agent's own system
// prompt keeps the model vendor out of its answers, and a label under the input would say it
// anyway. A workflow that runs on its own model is not reflected; the label names the agent's.
function AgentModelLabel() {
  const agent = useAgentModel()
  if (!agent) return null
  return (
    <MetaList items={[agent.provider, agent.model]} className="text-xs text-ink-caption truncate min-w-0 px-1 select-none" />
  )
}

// The agents map's button, beside the model label (maintainer's request, 2026-10-10), under the
// same rule as the label, since the map names each agent's provider and model; and only once there
// is a conversation to show.
//
// ALWAYS THE MAP'S ICON, however many sub-agents there are (maintainer's request, 2026-10-14). It
// was one robot per working sub-agent, up to three, each going to its panel, and a "+N" past three
// that opened the map - so with fewer than four at work the map could not be opened at all. Now
// the icon BLINKS while any sub-agent works, in its own ink rather than the brand's, and the map's
// agent names are what go to a panel in the thread (AgentsGraphDialog). How many work is in its
// name and tooltip. Working sub-agents are read from the chat store, as the thread's panels are.
function AgentsGraphButton({ conversationId }: { conversationId?: string }) {
  const { t } = useTranslation()
  const agent = useAgentModel()
  const [open, setOpen] = useState(false)
  const messages = useChatStore(st => st.messages)
  const liveCalls = useChatStore(st => st.toolCalls)
  const working = runningAgents(messages, liveCalls).length
  const label = working > 0
    ? t('Agents - {count} sub-agent working|Agents - {count} sub-agents working', { count: working })
    : t('Agents')
  // Fades in once there is a conversation to map, rather than appearing in one frame.
  return (
    <>
      <Presence show={!!agent && !!conversationId} collapse={false} className="flex">
        <IconCountButton icon={Workflow} label={label} working={working > 0} onClick={() => setOpen(true)} />
      </Presence>
      {open && conversationId && <AgentsGraphDialog conversationId={conversationId} onClose={() => setOpen(false)} />}
    </>
  )
}

// Replaces the old always-visible "New conversation" button that used to sit above the input -
// this one lives in the toolbar right next to Send/Stop and only appears once there's actually a
// conversation worth leaving (hasMessages) - shown regardless of what's currently typed, unlike
// Condense (which only cares about context usage, not the draft either).
function NewConversationButton({ show, onClick }: { show: boolean; onClick: () => void }) {
  const { t } = useTranslation()
  return (
    <Presence show={show} collapse={false} className="flex">
      <IconButton icon={MessageCirclePlus} label={t('New conversation')} tooltip={t('Start a new conversation')} onClick={onClick} />
    </Presence>
  )
}

// THE COMPOSER, for every chat - the chat page's two views and a panel's (ChatPanel). It was
// ChatPage's private ChatPillInput until the key-account panel needed the same one beside a page.
// What it reads of the chat (the permission mode, the agents) is the chat it is rendered in.
export function ChatComposer({
  value, onChange, onSend, onStop, streaming, placeholder, autoFocus, mentions, onMentionsChange,
  attachments, onFiles, onRemoveAttachment, skillMentions, onSkillMentionsChange,
  hasMessages = false, conversationId, onNewConversation, onHistoryClick,
  condensing = false, condenseTokensFreed = null, onCondense,
}: {
  value: string
  onChange: (v: string) => void
  onSend: () => void
  onStop?: () => void
  streaming?: boolean
  // Condensation is reported in the toolbar's context-window indicator (see
  // ContextWindowIndicator) rather than in the transcript, so this row needs all three of them.
  condensing?: boolean
  condenseTokensFreed?: number | null
  onCondense?: () => void
  placeholder?: string
  autoFocus?: boolean
  mentions: string[]
  onMentionsChange: (mentions: string[]) => void
  attachments: ChatAttachment[]
  onFiles: (fileList: FileList | File[]) => void
  onRemoveAttachment: (id: string) => void
  skillMentions: string[]
  onSkillMentionsChange: (names: string[]) => void
  // Only meaningful in the conversation view (the hero pill never has messages yet, so these
  // simply default to "hidden") - see NewConversationButton's own docs for the visibility rule.
  hasMessages?: boolean
  conversationId?: string
  onNewConversation?: () => void
  // The history button is shown only where there is a history to open: the chat page's, or a
  // panel's own (a key account's conversations - ChatPanel's onOpenConversation).
  onHistoryClick?: () => void
}) {
  // The kit's file picker: the hidden input and its click bridge (`after` below holds the input).
  const picker = useFilePicker({ onFiles, accept: ATTACHMENT_ACCEPT, multiple: true })
  // Flips the instant Stop is clicked - the backend's own confirmation (a real 'done'/'error' SSE
  // event once its abort-polling loop notices, see useChat's stop()) can lag by up to ~1s under
  // normal conditions and longer under load, so this is purely local, optimistic UI feedback.
  const [stopRequested, setStopRequested] = useState(false)
  const { t } = useTranslation()

  // Streaming flipping false is the real confirmation the turn ended (Stopped, or it simply
  // finished on its own) - clear the optimistic flag then, not on a timer.
  useEffect(() => { if (!streaming) setStopRequested(false) }, [streaming])

  const showSend = !streaming && value.trim().length > 0 && !attachments.some(a => a.status === 'uploading')
  const showStop = !!streaming

  // The kit's ComposerShell: attachments over the editor, the editor, and the toolbar - attach on the
  // left, send/stop on the right, the text field on a row of its own above them rather than beside
  // them like the old single-line pill. It draws the composer's light (the one glow in the product)
  // and turns it faster while the agent works.
  return (
    <ComposerShell
      working={!!streaming}
      attachments={attachments.length > 0 && attachments.map(att => (
        <AttachmentChip key={att.id} attachment={att} onRemove={() => onRemoveAttachment(att.id)} />
      ))}
      editor={(
        <MentionRichInput
          value={value}
          onChange={onChange}
          onSend={onSend}
          placeholder={placeholder}
          autoFocus={autoFocus}
          streaming={streaming}
          mentions={mentions}
          onMentionsChange={onMentionsChange}
          skillMentions={skillMentions}
          onSkillMentionsChange={onSkillMentionsChange}
        />
      )}
      toolbarStart={(
        <>
          <AttachMenu
            onAttachFiles={picker.open}
            value={value}
            onChange={onChange}
            skillMentions={skillMentions}
            onSkillMentionsChange={onSkillMentionsChange}
          />
          <PermissionModePicker />
          <AgentModelLabel />
          <AgentsGraphButton conversationId={conversationId} />
          <ContextWindowIndicator
            conversationId={conversationId}
            condensing={condensing}
            tokensFreed={condenseTokensFreed}
            onCondense={() => onCondense?.()}
          />
          <QuotaIndicator />
        </>
      )}
      toolbarEnd={(
        <>
          {onHistoryClick && <HistoryButton onClick={onHistoryClick} />}
          <NewConversationButton
            show={hasMessages && !!onNewConversation}
            onClick={() => onNewConversation?.()}
          />
          {/* No placeholder slot for the empty-input/not-streaming case - rendering nothing at
              all (rather than a same-sized spacer) is what lets History/New Conversation
              actually shift right into the freed space, matching how New Conversation itself
              disappears with no reserved space of its own. */}
          {/* ONE SLOT for Stop and Send: present while either is, fading in and out as a whole
              (Presence), and fading from one to the other in place (FadeSwap). Nothing is drawn
              while neither is - no reserved space - so History and New conversation move into the
              room it leaves. */}
          <Presence show={(showStop && !!onStop) || showSend} collapse={false} className="flex">
            <FadeSwap swapKey={showStop ? 'stop' : 'send'} className="flex">
              {showStop && onStop ? (
                <IconButton
                  icon={stopRequested ? StoppingGlyph : StopGlyph}
                  label={stopRequested ? t('Stopping') : t('Stop')}
                  onClick={() => { setStopRequested(true); onStop() }}
                  disabled={stopRequested}
                  className={cn(stopRequested && 'pointer-events-none')}
                />
              ) : (
                <IconButton
                  icon={ArrowUp}
                  label={t('Send')}
                  // preventDefault on mousedown only (keeps the editor from losing focus) - onSend
                  // itself must stay on onClick. onSend flips `streaming` synchronously, which swaps
                  // this very button for Stop in the same slot; if onSend ran on mousedown, the
                  // mouseup half of the same click would land on Stop and abort the turn that was
                  // just started. On click, the swap happens after mouseup has already resolved
                  // against Send.
                  onMouseDown={e => e.preventDefault()}
                  onClick={onSend}
                />
              )}
            </FadeSwap>
          </Presence>
        </>
      )}
      after={picker.input}
    />
  )
}
