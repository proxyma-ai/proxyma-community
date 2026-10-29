import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { ChatInstanceProvider } from '../instance/ChatInstanceContext'
import { ChatWindow } from './ChatWindow'
import { ChatComposer } from './ChatComposer'
import { ChatHistoryModal } from './ChatHistoryModal'
import { useChat } from '../hooks/useChat'
import { useAttachmentUploader } from '../hooks/useAttachmentUploader'
import { bindsBeforeSend, withPanelSkills } from '../utils/panelSend'
import type { ChatAttachment } from '../types/chat.types'
import type { ConversationListSource } from '../services/chatApi'
import { useDraftState } from '@/store/composerDraftStore'
import { useToastStore } from '@/store/toastStore'
import { Caption, DropOverlay, FadeSwap, List, ListRow, Title, cn, useFileDrop } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'

export interface ChatPanelProps {
  /**
   * Which chat this panel is. One key is one chat - its thread, its stream, its draft - and it
   * outlives a remount of the panel (features/chat/instance). Keep it STABLE for what the panel is
   * about (`key-account:{accountId}`), never derived from the conversation id: binding a
   * conversation must not swap the chat it was started in.
   */
  instanceKey: string
  /**
   * Where this chat's conversations are held (the backend's ConversationSurface, e.g.
   * `key-account`): recorded from a conversation's first message and never changed, and what keeps
   * them out of the chat page's history and search. Read when the instance is first made.
   */
  surface?: string
  /**
   * The conversation to open and continue - one that exists, or an id the page minted and bound
   * itself before anything was sent (it opens empty; the first message creates it under this id).
   * Absent: the panel starts a new conversation on its first message, minting the id itself.
   */
  conversationId?: string
  /** Skills picked on EVERY message, beside any the user picks (the panel's own skill). By name. */
  skillMentions?: string[]
  /**
   * Called with the id of a conversation the PANEL starts (no `conversationId` prop), before its
   * first message is sent - awaited, so the page can bind it first (a POST of its own). Rejecting
   * keeps the message unsent, in the composer. Ids are minted by the client and the server uses a
   * named one as given (ChatController.chat), so this id is the conversation's.
   */
  onConversationCreated?: (conversationId: string) => void | Promise<void>
  /**
   * The composer's New conversation, shown once the thread has messages. The PAGE starts it: it
   * names the new conversation through `conversationId` (a key account mints an id and binds it
   * first), and the panel follows. Absent: no such button.
   */
  onNewConversation?: () => void
  /**
   * The composer's History: a dialog listing this panel's own conversations (`history`), and the one
   * picked is handed to the page, which names it through `conversationId` (a key account binds it
   * again first). Absent, or without `history`: no such button.
   */
  onOpenConversation?: (conversationId: string) => void
  /**
   * What the History dialog lists - the page's own record of its conversations (a key account's:
   * the ones bound to the account). Keep it stable between renders.
   */
  history?: ConversationListSource
  /**
   * A conversation deleted from the History dialog. The PAGE decides where the panel goes when it was
   * the one open (a key account opens the next most recent bound one, or a fresh one).
   */
  onConversationDeleted?: (conversationId: string) => void
  /** Above the thread: the panel's title and its actions (collapse, close). */
  header?: ReactNode
  /** What the empty panel says: a title, a sentence, and up to three prompts sent on a click. */
  emptyTitle: ReactNode
  emptyDescription?: ReactNode
  suggestions?: string[]
  placeholder?: string
  className?: string
}

/**
 * A COMPLETE CHAT IN A COLUMN beside a page - the key-account page's panel
 * (docs/architecture.html#spec-11-8): the chat page's thread, prompts and composer, as an
 * independent chat instance, so the panel and the chat page never share state. Laid out for
 * 380-480px; the parent gives it its height.
 */
export function ChatPanel(props: ChatPanelProps) {
  return (
    <ChatInstanceProvider instanceKey={props.instanceKey} surface={props.surface}>
      <ChatPanelBody {...props} />
    </ChatInstanceProvider>
  )
}

// Stable empties for the draft store: a fresh [] each render would read as a change to every
// effect that depends on it.
const NO_NAMES: string[] = []
const NO_ATTACHMENTS: ChatAttachment[] = []
const MAX_SUGGESTIONS = 3

function ChatPanelBody({
  instanceKey, conversationId, skillMentions, onConversationCreated, onNewConversation, onOpenConversation, history,
  onConversationDeleted, header, emptyTitle, emptyDescription, suggestions = NO_NAMES, placeholder, className,
}: ChatPanelProps) {
  const { t } = useTranslation()
  const [historyOpen, setHistoryOpen] = useState(false)
  const {
    messages, streaming, toolCalls, loadingHistory, send, retry, canRetry, stop, rewind, condense,
    conversationId: openId, resumeConversation, pendingApprovals, pendingEnableRequests,
    condensing, condenseTokensFreed, attachedToolCallCount, workflowRuns, ensureConversationId,
  } = useChat()

  // The draft is the panel's own, kept while the user is elsewhere - as the chat page's is.
  const draftKey = `panel:${instanceKey}`
  const [text, setText] = useDraftState(draftKey, 'text', '')
  const [mentions, setMentions] = useDraftState<string[]>(draftKey, 'mentions', NO_NAMES)
  const [attachments, setAttachments] = useDraftState<ChatAttachment[]>(draftKey, 'attachments', NO_ATTACHMENTS)
  const [picked, setPicked] = useDraftState<string[]>(draftKey, 'skills', NO_NAMES)
  const uploader = useAttachmentUploader(attachments, setAttachments, ensureConversationId)

  // Opens the conversation the page names, once per id. Through a ref, as ChatPage does: the
  // callbacks useChat returns change identity every render. The conversation this chat already
  // shows is left alone - which is what a page binding the id the panel just started sees.
  const latest = useRef({ openId, resumeConversation })
  latest.current = { openId, resumeConversation }
  useEffect(() => {
    if (conversationId && conversationId !== latest.current.openId) latest.current.resumeConversation(conversationId)
  }, [conversationId])

  // The id already handed to onConversationCreated, so a retry after a failed send does not bind twice.
  const announced = useRef<string | null>(null)
  const binding = useRef(false)

  // Sends one message, binding a conversation the panel starts before its first message. True
  // when it was sent.
  const deliver = useCallback(async (
    msg: string, msgMentions?: string[], files?: string[], names?: string[], msgSkills?: string[],
  ): Promise<boolean> => {
    if (binding.current) return false
    if (!conversationId && onConversationCreated && messages.length === 0 && !streaming) {
      // An attachment may have minted the id already; this returns that one.
      const id = ensureConversationId()
      if (bindsBeforeSend({
        pageConversationId: conversationId, pageBinds: true, messageCount: messages.length, streaming,
        conversationId: id, announcedId: announced.current,
      })) {
        binding.current = true
        try {
          await onConversationCreated(id)
          announced.current = id
        } catch {
          useToastStore.getState().addToast(t('Could not start this conversation. Try again.'), 'danger')
          return false
        } finally {
          binding.current = false
        }
      }
    }
    send(msg, msgMentions, files, names, withPanelSkills(msgSkills, skillMentions))
    return true
  }, [conversationId, onConversationCreated, messages.length, streaming, ensureConversationId, send, skillMentions, t])

  const handleSend = () => {
    const msg = text.trim()
    if (!msg) return
    const done = attachments.filter(a => a.status === 'done').map(a => a.fileName!)
    void deliver(msg, mentions, done, done, picked).then(sent => {
      if (!sent) return
      setText('')
      setMentions([])
      setAttachments([])
      setPicked([])
    })
  }

  const handleRewind = useCallback(async (messageId: number) => {
    const content = await rewind(messageId)
    if (content != null) setText(content)
  }, [rewind, setText])

  // A file dropped anywhere on the panel attaches to its composer - over the panel only, not the
  // page beside it.
  const drop = useFileDrop({
    onDrop: data => { if (data.files.length > 0) uploader.handleFiles(data.files) },
  })

  const empty = messages.length === 0 && !loadingHistory && !streaming
  // The composer's readouts (the agents, the context window) ask the server about the conversation.
  // One the panel minted has no row until its first turn is under way, so they wait for that turn's
  // first answer or tool call - a loaded conversation has its answers already. (On the chat page the
  // id usually arrives with the server's reply to the first message, so this rarely comes up there.)
  const serverKnows = !!openId && (toolCalls.length > 0 || messages.some(m => m.role === 'assistant'))

  return (
    // No plane of its own: the thread is on the glass sheet it sits in (or the panel's, `className`).
    <div className={cn('relative flex flex-col h-full min-h-0 min-w-0', className)} {...drop.handlers}>
      <DropOverlay active={drop.active} label={t('Drop files to attach')} portal={false} />
      {header && <div className="shrink-0 px-3 py-2 border-b border-line/45 animate-fade-in">{header}</div>}
      {/* The empty state and the thread swap by a fade (FadeSwap) as the first message goes. */}
      <FadeSwap swapKey={empty} className="flex-1 min-h-0 flex flex-col">
      {empty ? (
        <div className="flex-1 min-h-0 overflow-y-auto px-3 py-6">
          <Title>{emptyTitle}</Title>
          {emptyDescription && <Caption className="mt-1">{emptyDescription}</Caption>}
          {suggestions.length > 0 && (
            <List className="mt-4" ariaLabel={t('Suggested prompts')}>
              {suggestions.slice(0, MAX_SUGGESTIONS).map(prompt => (
                <ListRow key={prompt} title={prompt} chevron onClick={() => { void deliver(prompt) }} />
              ))}
            </List>
          )}
        </div>
      ) : (
        <ChatWindow
          compact
          messages={messages}
          streaming={streaming}
          toolCalls={toolCalls}
          loading={loadingHistory}
          onRetry={canRetry ? retry : undefined}
          conversationId={openId}
          pendingApprovals={pendingApprovals}
          pendingEnableRequests={pendingEnableRequests}
          condensing={condensing}
          onRewind={handleRewind}
          attachedToolCallCount={attachedToolCallCount}
          workflowRuns={workflowRuns}
        />
      )}
      </FadeSwap>
      <div className="shrink-0 px-3 pt-2 pb-3">
        <ChatComposer
          value={text}
          onChange={setText}
          onSend={handleSend}
          onStop={stop}
          streaming={streaming}
          placeholder={placeholder ?? t('Type @ to mention a source or / to use a skill')}
          mentions={mentions}
          onMentionsChange={setMentions}
          attachments={attachments}
          onFiles={uploader.handleFiles}
          onRemoveAttachment={uploader.removeAttachment}
          skillMentions={picked}
          onSkillMentionsChange={setPicked}
          conversationId={serverKnows ? openId : undefined}
          condensing={condensing}
          condenseTokensFreed={condenseTokensFreed}
          onCondense={condense}
          hasMessages={messages.length > 0}
          onNewConversation={onNewConversation}
          onHistoryClick={onOpenConversation && history ? () => setHistoryOpen(true) : undefined}
        />
      </div>
      {/* Inside the instance's provider, so the list's working dot for the open conversation is
          this chat's. Picking the one already open only closes the dialog. */}
      {historyOpen && onOpenConversation && history && (
        <ChatHistoryModal
          activeId={openId}
          source={history}
          onDeleted={onConversationDeleted}
          onSelect={id => {
            setHistoryOpen(false)
            if (id !== openId) onOpenConversation(id)
          }}
          onClose={() => setHistoryOpen(false)}
        />
      )}
    </div>
  )
}
