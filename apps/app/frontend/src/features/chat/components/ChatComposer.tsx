import { useState, useRef, useEffect, useMemo } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlarmClock, Brain, Clock, Database, X, FolderPlus, Globe, Plug, Plus, ArrowUp, Square, Paperclip, FileText, Settings as SettingsIcon, MessageCirclePlus, Timer, Upload, Workflow,
  type LucideProps,
} from 'lucide-react'
import { AgentsGraphDialog } from './AgentsGraphDialog'
import { AgentSettingsLink } from './AgentSettingsLink'
import { MentionRichInput } from './MentionRichInput'
import { PermissionModePicker } from './PermissionModePicker'
import { FilePreviewDialog } from './FilePreviewDialog'
import { useAgentModel } from '../hooks/useAgentModel'
import { useConversationUsage } from '../hooks/useConversationUsage'
import { useUserQuota } from '../hooks/useUserQuota'
import { compactTokens, workingCount } from '../utils/agentTree'
import { dueIn } from '../utils/alarms'
import { useAgentTree } from '../hooks/useAgentTree'
import { useAlarms } from '../hooks/useAlarms'
import { useToastStore } from '@/store/toastStore'
import { withReason } from '@/services/apiError'
import { formatSessionReset } from '../utils/formatSessionReset'
import { RememberDialog } from './RememberDialog'
import { chatApi } from '../services/chatApi'
import { indexingApi } from '@/features/indexing/services/indexingApi'
import { SourceTypeMark } from '@/features/indexing/utils/sourceMarks'
import { ConnectorConfigModal } from '@/features/indexing/components/ConnectorConfigModal'
import { FileUploadModal } from '@/features/indexing/components/FileUploadModal'
import { LocalFolderModal } from '@/features/indexing/components/LocalFolderModal'
import { useFileUpload } from '@/hooks/useFileUpload'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { cacheDeadline, cacheLifetime, countdownText, nearlyCold, secondsUntil } from '../utils/cacheTimer'
import type { ChatAttachment } from '../types/chat.types'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import {
  Caption, Chip, ComposerShell, FadeSwap, IconButton, IconCountButton, InlineNotice, MenuEmpty, MenuGroup, MenuRow, Meter, MetaList,
  Popover, Presence, ProgressBar, Readout, StateMark, TEXT, Tooltip, UsageRing, cn, iconButton, useFilePicker, workingMotion,
} from '@/components/ui/kit'
import { skillsApi } from '@/features/settings/services/skillsApi'
import { SkillModal } from '@/features/settings/components/SkillsTab'
import { WorkflowModal } from '@/features/settings/components/WorkflowsTab'
import type { SkillDetail } from '@/features/settings/services/skillsApi'
import { keys } from '@/services/queryKeys'
import { FEATURES } from '@/config/features'
import { useFramedLayout } from '@/hooks/useWideLayout'
import { useDesktopWindow } from '@/hooks/useDesktopWindow'
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
        <div className="flex items-center gap-2">
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

// The composer's + menu, which replaced the old single-purpose "click to open file picker" Paperclip
// button. Since 2026-10-23 (the maintainer's request) it holds, in this order:
//
//  - Attach files - as before.
//  - Skills > - every skill visible to this user. Picking one inserts an inline "/SkillName " mention
//    into the message text (exactly like typing "/" - see MentionRichInput). Sending that message
//    loads the skill into the conversation for good: its full instructions go into the history at
//    the point it was picked (ChatService.applyPickedSkills - the system prompt only advertises
//    names), and picking it again later costs a one-line pointer back, not a second copy. Always-on
//    skills (Settings > Skills) are preloaded into every conversation the same way.
//  - Sources > - the sources this person can search, picked as an "@Source " mention, exactly like
//    typing "@"; and, under them, adding a web page or files/a folder as a new source, through the
//    same dialogs the Sources page opens. A new source is in this list the next time it opens.
//  - MCP servers > - each server this person may use, picked as its skill ("MCP: name"), which
//    ChatService loads as the agent's own readSkill would: the server's tools for this message on.
//  - Remember something - a fact or preference straight into the agent's memory (RememberDialog).
//
// Every menu here is the kit's Popover - portaled to document.body and placed against the viewport:
// the input pill sits at the very bottom of the page, and an `absolute` panel was routinely clipped
// by the page's own scroll container. A submenu opens BESIDE its row (right-start, flipping left
// where there is no room); only one is open at a time, and hovering any other row closes it.
type Sub = 'skills' | 'sources' | 'mcp' | null

function AttachMenu({
  onAttachFiles, value, onChange, mentions, onMentionsChange, skillMentions, onSkillMentionsChange,
}: {
  onAttachFiles: () => void
  value: string
  onChange: (v: string) => void
  mentions: string[]
  onMentionsChange: (mentions: string[]) => void
  skillMentions: string[]
  onSkillMentionsChange: (names: string[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [sub, setSub] = useState<Sub>(null)
  const [creatingSkill, setCreatingSkill] = useState(false)
  const [creatingWorkflow, setCreatingWorkflow] = useState(false)
  const [remembering, setRemembering] = useState(false)
  const [adding, setAdding] = useState<'webpage' | 'files' | null>(null)
  const canUpload = useFileUpload()
  const { t } = useTranslation()
  // A visitor to Enterprise Server without an account has no sources, connections or memory of
  // their own, and a request for them would send the page to the sign-in (.claude/rules/frontend.md)
  // - so those three entries are a signed-in reader's.
  const { user } = useAuth()
  const signedIn = !FEATURES.auth || !!user
  const { data: skills = [] } = useQuery({ queryKey: keys.chatSkills(), queryFn: skillsApi.list, enabled: open })
  // Asked again each time the menu opens (no staleTime), so a source added a moment ago is there.
  // The first fifty: typing "@" searches them all.
  const { data: sources = [] } = useQuery({
    queryKey: keys.chatSourcePicks(),
    queryFn: () => indexingApi.listByType('all', 0, undefined, 50, true).then(page => page.items),
    enabled: open && signedIn,
  })
  const { data: mcpServers = [] } = useQuery({
    queryKey: keys.chatMcpServers(), queryFn: chatApi.listMcpServers, enabled: open && signedIn,
  })

  const closeAll = () => { setOpen(false); setSub(null) }

  // Appended at the end of the message, not at the caret - this menu cannot reach into
  // MentionRichInput's own contentEditable DOM, but its external-value sync rebuilds the chips when
  // the value changes under it (as it does for a rewind's prefill).
  const append = (token: string) =>
    onChange(value.length > 0 && !value.endsWith(' ') ? `${value} ${token} ` : `${value}${token} `)

  const pickSkill = (name: string) => {
    if (!skillMentions.includes(name)) {
      append(`/${name}`)
      onSkillMentionsChange([...skillMentions, name])
    }
    closeAll()
  }

  const pickSource = (name: string) => {
    if (!mentions.includes(name)) {
      append(`@${name}`)
      onMentionsChange([...mentions, name])
    }
    closeAll()
  }

  return (
    <div>
      <Popover
        open={open}
        onOpenChange={o => { setOpen(o); if (!o) setSub(null) }}
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
              <div onMouseEnter={() => setSub(null)}>
                <Tooltip label={t('Attach one or more files to this message')} fullWidth>
                  <MenuRow icon={<Paperclip />} title={t('Attach files')} onSelect={() => { onAttachFiles(); closeAll() }} />
                </Tooltip>
              </div>
            </MenuGroup>
            <MenuGroup>
              <ComposerSubmenu id="skills" sub={sub} setSub={setSub} icon={<FileText />} title={t('Skills')}
                tooltip={t('Pick a skill to use for this message')}>
                <MenuGroup>
                  {skills.length === 0 ? (
                    <MenuEmpty>{t('No skills yet.')}</MenuEmpty>
                  ) : (
                    <div className="max-h-56 overflow-y-auto animate-fade-in">
                      {skills.map(s => (
                        <Tooltip key={s.id} label={s.shortDescription} fullWidth>
                          <MenuRow icon={<FileText />} title={s.name} onSelect={() => pickSkill(s.name)} />
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
              </ComposerSubmenu>

              {signedIn && (<>
              <ComposerSubmenu id="sources" sub={sub} setSub={setSub} icon={<Database />} title={t('Sources')}
                tooltip={t('Search one source for this message')}>
                <MenuGroup>
                  {sources.length === 0 ? (
                    <MenuEmpty>{t('No sources yet.')}</MenuEmpty>
                  ) : (
                    <div className="max-h-56 overflow-y-auto animate-fade-in">
                      {sources.map(c => (
                        <MenuRow key={c.id} icon={<SourceTypeMark type={c.type} />} title={c.name}
                          onSelect={() => pickSource(c.name)} />
                      ))}
                    </div>
                  )}
                </MenuGroup>
                <MenuGroup>
                  <Tooltip label={t('Index a website as a new source')} fullWidth>
                    <MenuRow tone="brand" icon={<Globe />} title={t('Add a web page')}
                      onSelect={() => { setAdding('webpage'); closeAll() }} />
                  </Tooltip>
                  {canUpload ? (
                    <Tooltip label={t('Upload files as a new source')} fullWidth>
                      <MenuRow tone="brand" icon={<Upload />} title={t('Upload files')}
                        onSelect={() => { setAdding('files'); closeAll() }} />
                    </Tooltip>
                  ) : (
                    <Tooltip label={t('Index a folder on this computer as a new source')} fullWidth>
                      <MenuRow tone="brand" icon={<FolderPlus />} title={t('Add a local folder')}
                        onSelect={() => { setAdding('files'); closeAll() }} />
                    </Tooltip>
                  )}
                </MenuGroup>
              </ComposerSubmenu>

              <ComposerSubmenu id="mcp" sub={sub} setSub={setSub} icon={<Plug />} title={t('MCP servers')}
                tooltip={t("Give this message an MCP server's tools")}>
                <MenuGroup>
                  {mcpServers.length === 0 ? (
                    <MenuEmpty>{t('No MCP servers yet.')}</MenuEmpty>
                  ) : (
                    <div className="max-h-56 overflow-y-auto animate-fade-in">
                      {mcpServers.map(s => (
                        <MenuRow key={s.name} icon={<Plug />} title={mcpServerName(s.name)}
                          onSelect={() => pickSkill(s.name)} />
                      ))}
                    </div>
                  )}
                </MenuGroup>
                <MenuGroup>
                  <Tooltip label={t('Add or manage MCP servers in Settings > Connections')} fullWidth>
                    <MenuRow icon={<SettingsIcon />} title={t('Manage MCP servers')}
                      onSelect={() => { useSettingsModalStore.getState().open('Connections'); closeAll() }} />
                  </Tooltip>
                </MenuGroup>
              </ComposerSubmenu>
              </>)}
            </MenuGroup>
            {signedIn && (
              <MenuGroup>
                <div onMouseEnter={() => setSub(null)}>
                  <Tooltip label={t('Save a fact or a preference to the agent\'s memory')} fullWidth>
                    <MenuRow icon={<Brain />} title={t('Remember something')} onSelect={() => { setRemembering(true); closeAll() }} />
                  </Tooltip>
                </div>
              </MenuGroup>
            )}
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
      {remembering && <RememberDialog onClose={() => setRemembering(false)} />}
      {adding === 'webpage' && <ConnectorConfigModal type="webpage" onClose={() => setAdding(null)} />}
      {adding === 'files' && (canUpload
        ? <FileUploadModal onClose={() => setAdding(null)} />
        : <LocalFolderModal onClose={() => setAdding(null)} />)}
    </div>
  )
}

// An MCP server's skill is "MCP: <server>"; the menu shows the server's own name.
function mcpServerName(skillName: string): string {
  return skillName.replace(/^MCP: /, '')
}

// One row of the + menu that opens a submenu beside it, on hover or click - opens only, never
// toggles closed: a real click always starts with the pointer entering the row, which has already
// opened it, and a toggle closed what the hover had just opened. The submenu is a Popover of its
// own, which stops its presses from reaching the document so the menu above does not take them for
// a press outside it. ArrowRight opens it from the keyboard and ArrowLeft goes back to its row, as
// a desktop menu's do (the menus' keyboard, useMenuFocus, then moves focus onto its first row).
function ComposerSubmenu({ id, sub, setSub, icon, title, tooltip, children }: {
  id: Exclude<Sub, null>
  sub: Sub
  setSub: React.Dispatch<React.SetStateAction<Sub>>
  icon: React.ReactNode
  title: string
  tooltip: string
  children: React.ReactNode
}) {
  const rowRef = useRef<HTMLDivElement | null>(null)
  return (
    <Popover
      open={sub === id}
      onOpenChange={o => setSub(s => (o ? id : s === id ? null : s))}
      placement="right-start"
      widthClass="w-64"
      ariaLabel={title}
      trigger={({ ref }) => (
        <div
          ref={el => { rowRef.current = el; ref(el) }}
          onMouseEnter={() => setSub(id)}
          onKeyDown={e => { if (e.key === 'ArrowRight') { e.preventDefault(); e.stopPropagation(); setSub(id) } }}
        >
          <Tooltip label={tooltip} fullWidth>
            <MenuRow icon={icon} title={title} trailing="chevron" onSelect={() => setSub(id)} />
          </Tooltip>
        </div>
      )}
    >
      {() => (
        <div
          onPointerDown={e => e.stopPropagation()}
          onMouseLeave={() => setSub(s => (s === id ? null : s))}
          onKeyDown={e => {
            if (e.key !== 'ArrowLeft') return
            e.preventDefault()
            e.stopPropagation()
            setSub(null)
            rowRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus()
          }}
        >
          {children}
        </div>
      )}
    </Popover>
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

// THE PROMPT CACHE'S TIMER (the maintainer's request, 2026-10-22): while the conversation's cached
// prompt is warm, a clock and how long until it goes cold. The server's PromptCacheClock knows -
// a response that wrote the cache starts it with its lifetime, every read restarts it - and the
// usage payload carries the seconds left, live through a turn with each 'usage' event. The page
// counts down from when that figure arrived (cacheTimer.ts), never from a timestamp. Gone when
// cold: a message sent then pays to write the prompt again. The last minute is in the warning ink.
function CacheTimer({ conversationId }: { conversationId?: string }) {
  const { t } = useTranslation()
  const { data } = useConversationUsage(conversationId)
  const secondsLeft = data?.cacheSecondsLeft ?? null
  // A new figure is a new deadline; the same warmUntil fetched again does not move it.
  const deadline = useMemo(
    () => cacheDeadline(secondsLeft, Date.now()),
    // Keyed on the cache's own expiry alone, deliberately.
    [data?.cacheWarmUntil],
  )
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (deadline == null) return
    setNow(Date.now())
    const tick = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(tick)
  }, [deadline])
  const left = secondsUntil(deadline, now)
  const time = countdownText(left)
  const label = cacheLifetime(data?.cacheLifetimeSeconds) === 'hour'
    ? t('Prompt cache warm for {time}. Each message that reads it keeps it another hour.', { time })
    : t('Prompt cache warm for {time}. Each message that reads it keeps it another five minutes.', { time })
  return (
    <Presence show={!!conversationId && left > 0} collapse={false} className="flex items-center">
      <Tooltip label={label}>
        <span
          role="timer"
          tabIndex={0}
          aria-label={t('Prompt cache warm for {time}', { time })}
          className={cn('inline-flex items-center gap-1 rounded-md px-2 focus-ring whitespace-nowrap', TEXT.caption,
            nearlyCold(left) ? 'text-warning' : 'text-ink-caption')}
        >
          {/* A stopwatch, not a clock: the clock beside it opens the conversation history. */}
          <Timer size={14} aria-hidden />
          <Readout>{time}</Readout>
        </span>
      </Tooltip>
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
//
// A click opens Settings > Agents, where the provider and model are chosen (the user's request,
// feedback 499724c9) - the reader can already read those settings, or the label would not show.
function AgentModelLabel() {
  const agent = useAgentModel()
  if (!agent) return null
  return (
    <AgentSettingsLink className="px-1">
      <MetaList items={[agent.provider, agent.model]} className="text-xs truncate min-w-0 select-none" />
    </AgentSettingsLink>
  )
}

// The agents map's button, beside the model label (maintainer's request, 2026-10-10), under the
// same rule as the label, since the map names each agent's provider and model; and only once there
// is a conversation to show.
//
// ALWAYS THE MAP'S ICON, however many agents there are (maintainer's request, 2026-10-14). It
// was one robot per working agent, up to three, each going to its panel, and a "+N" past three
// that opened the map - so with fewer than four at work the map could not be opened at all. The
// icon BLINKS while any agent of the conversation's tree works, in its own ink rather than the
// brand's, and the map's agent names open each agent's conversation (AgentsGraphDialog). How many
// work is in its name and tooltip. Read from the agent tree, with the stream's 'agent-status'
// events over it, as every agent panel is (useAgentTree).
function AgentsGraphButton({ conversationId }: { conversationId?: string }) {
  const { t } = useTranslation()
  const agent = useAgentModel()
  const [open, setOpen] = useState(false)
  const { agents } = useAgentTree(conversationId)
  const working = workingCount(agents)
  const label = working > 0
    ? t('Agents - {count} agent working|Agents - {count} agents working', { count: working })
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

// THE CONVERSATION'S ALARMS (the agent's `alarm` tool), beside the agents button: an alarm clock
// with how many are set, shown only while any is, opening a list of each one's time - how long
// until it goes off, the exact time a tooltip away - its note, and a Cancel. An alarm that goes off
// wakes the agent, and the list is read again then (useChat).
function AlarmsChip({ conversationId }: { conversationId?: string }) {
  const { t, locale } = useTranslation()
  const qc = useQueryClient()
  const { alarms } = useAlarms(conversationId)
  const [canceling, setCanceling] = useState<string | null>(null)
  const label = t('{count} alarm set|{count} alarms set', { count: alarms.length })
  const cancel = async (alarmId: string) => {
    if (!conversationId || canceling) return
    setCanceling(alarmId)
    try {
      await chatApi.cancelAlarm(conversationId, alarmId)
    } catch (e) {
      useToastStore.getState().addToast(withReason(t('Could not cancel the alarm'), e), 'danger')
    } finally {
      setCanceling(null)
      qc.invalidateQueries({ queryKey: keys.alarms(conversationId) })
    }
  }
  return (
    // Eases in when the first alarm is set and out when the last one goes.
    <Presence show={!!conversationId && alarms.length > 0} collapse={false} className="flex">
      <Popover
        placement="top-start"
        widthClass="w-72"
        role="dialog"
        panelClassName="p-3 space-y-2"
        ariaLabel={label}
        trigger={({ toggle, ref }) => (
          <Tooltip label={label}>
            <button
              ref={ref}
              type="button"
              onClick={toggle}
              aria-label={label}
              className={cn(iconButton('md'), 'w-auto touch:w-auto px-2 gap-1')}
            >
              <AlarmClock size={16} aria-hidden="true" className="shrink-0" />
              <span className="text-xs data-readout">{alarms.length}</span>
            </button>
          </Tooltip>
        )}
      >
        {() => (
          <ul className="space-y-2">
            {alarms.map(alarm => {
              const left = dueIn(alarm.dueAt, Date.now())
              const exact = new Date(alarm.dueAt).toLocaleString(locale)
              return (
                <li key={alarm.alarmId} className="flex items-start gap-2 animate-fade-in">
                  <AlarmClock size={14} aria-hidden="true" className="shrink-0 mt-0.5 text-ink-caption" />
                  <div className="min-w-0 flex-1">
                    <Tooltip label={exact}>
                      <span className={cn(TEXT.label, 'data-readout text-ink-primary')}>
                        {left ? t('In {time}', { time: left }) : t('Due now')}
                      </span>
                    </Tooltip>
                    {alarm.note && <Caption className="block whitespace-normal break-words">{alarm.note}</Caption>}
                  </div>
                  <Tooltip label={t('Cancel this alarm')}>
                    <button
                      type="button"
                      onClick={() => cancel(alarm.alarmId)}
                      disabled={canceling === alarm.alarmId}
                      aria-label={t('Cancel this alarm')}
                      className={iconButton('sm', 'danger')}
                    >
                      <X size={14} aria-hidden="true" />
                    </button>
                  </Tooltip>
                </li>
              )
            })}
          </ul>
        )}
      </Popover>
    </Presence>
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
  // Where the sidebar is drawn (AppShell's frame), its recent chats replace the history button.
  const desktopWindow = useDesktopWindow()
  const framed = useFramedLayout() || !!desktopWindow

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
            mentions={mentions}
            onMentionsChange={onMentionsChange}
            skillMentions={skillMentions}
            onSkillMentionsChange={onSkillMentionsChange}
          />
          <PermissionModePicker />
          <AgentModelLabel />
          <AgentsGraphButton conversationId={conversationId} />
          <AlarmsChip conversationId={conversationId} />
          <CacheTimer conversationId={conversationId} />
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
          {/* Not where the sidebar is drawn: its recent chats and their history are there (the
              maintainer, 2026-10-30). A phone keeps it - it has no sidebar. */}
          {onHistoryClick && !framed && <HistoryButton onClick={onHistoryClick} />}
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
