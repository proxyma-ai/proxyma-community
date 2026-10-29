import { memo, useState } from 'react'
import type { ReactNode } from 'react'
import { ThumbsUp, ThumbsDown, RotateCcw, CirclePause, Paperclip, Share2, FileText, ShieldCheck } from 'lucide-react'
import { SourceCitations } from './SourceCitations'
import { MarkdownRenderer } from './MarkdownRenderer'
import { useSubmitFeedback } from '@/features/feedback/hooks/useSubmitFeedback'
import { FeedbackModal } from '@/features/feedback/components/FeedbackModal'
import {
  Badge, Button, Caption, ChatMessage as KitChatMessage, Chip, CopyButton, IconButton, IconToggleButton, NoticeCard, Presence, Tooltip, cn,
} from '@/components/ui/kit'
import { FEATURES } from '@/config/features'
import { useToastStore } from '@/store/toastStore'
import { chatApi } from '../services/chatApi'
import { tokenizeMentionText } from '../utils/tokenizeMentionText'
import { mentionIcon } from '../utils/mentionStyle'
import { useMentionTypes } from '../hooks/useMentionTypes'
import { stripConversationMediaUrls } from '../utils/stripMediaUrls'
import type { ChatMessage } from '../types/chat.types'
import { attachmentUrl } from '../services/chatAttachmentApi'
import { FilePreviewDialog } from './FilePreviewDialog'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useTranslation } from '@/i18n'
import { withReason } from '@/services/apiError'

// Read-only counterpart to MentionRichInput's inline chips - the same kit chip, `solid` in the
// brand fill - since a sent message's @mention/skill tokens are still plain "@Name"/"/Name" text in
// `content` (see ChatMessage.mentions/skillMentions' docs), so they need re-rendering as chips here
// rather than being editable ones. A document mention takes its source type's MARK, as in the
// composer (mentionStyle.ts): only the NAME survives once a message is sent, so the type is looked
// up by name (useMentionTypes). A name that no longer matches a source takes the plain file glyph.
function MentionOrSkillBadge({ name, skill, type }: { name: string; skill: boolean; type?: string }) {
  const Icon = skill ? FileText : mentionIcon(type)
  return <Chip variant="solid" icon={<Icon />} label={name} className="mx-0.5 max-w-48" />
}

function renderMessageContent(content: string, mentions: string[] | undefined, skillMentions: string[] | undefined,
                              types: Map<string, string>): ReactNode {
  if (!mentions?.length && !skillMentions?.length) return content
  return tokenizeMentionText(content, mentions ?? [], skillMentions ?? []).map((t, i) => {
    if (t.kind === 'text') return t.value
    return <MentionOrSkillBadge key={i} name={t.name} skill={t.kind === 'skill'} type={types.get(t.name)} />
  })
}

interface Props {
  message: ChatMessage
  onRetry?: () => void
  isLast?: boolean
  // False for an agent message that another agent message follows in the same turn - the
  // narration between tool calls ("I'll update all 27 now."). Only the message that ENDS a turn
  // carries the action row; see showActions.
  endsTurn?: boolean
  conversationId?: string
  // Full current conversation, used to build the transcript attached to a dislike's feedback
  // submission when "Include conversation messages" is checked.
  allMessages?: ChatMessage[]
  // This message's index within allMessages - scopes the feedback transcript to this message
  // and everything before it, never later messages.
  messageIndex?: number
  streaming?: boolean
  onRewind?: (messageId: number) => void
  // Set on the /shared/:token viewer page. A viewer may read and copy, and nothing else: no
  // like/dislike, no feedback, no rewind, and no re-sharing of someone else's link. This is the
  // display half of that restriction - the backend half is that the share token is accepted by
  // exactly one read-only endpoint and no other.
  readOnly?: boolean
}

// Every icon-only trigger in the chat UI (feedback, rewind, copy, share) is the kit's
// iconButton('sm'): no background, only the icon's own color shifts on hover. A state (rated,
// copied) is the rest color, passed with tone null.

function MessageBubbleImpl({ message, onRetry, isLast, endsTurn = true, conversationId, allMessages, messageIndex, streaming, onRewind, readOnly }: Props) {
  const { t } = useTranslation()
  const isUser = message.role === 'user'
  // Not on a shared copy: the reader's own sources say nothing about the author's.
  const mentionTypes = useMentionTypes(isUser && !readOnly ? message.mentions : undefined)
  const isError = !isUser && message.error
  // A guardrail held something back. Its own state rather than a flavor of isError: an error is
  // the product failing, and it is rendered in the danger color with a Retry button beside it.
  // This is the product doing exactly what its operator configured, and retrying the identical
  // message would produce the identical result - so it gets a neutral notice and no Retry.
  //
  // A REDACTION IS NOT THAT, since 2026-09-29 (the maintainer's request): the answer still stands
  // with the matched spans cut out, so it renders as an ordinary answer - markdown, sources and
  // actions - and the shield and the reason go on one line under it (RedactionNote). Only a
  // BLOCK, where there is no answer left to show, is the notice.
  const isRedacted = !isUser && message.guardrail?.verdict === 'redact'
  const isGuardrail = !isUser && !!message.guardrail && !isRedacted
  // Only on the message that ends a turn, since 2026-09-30 (maintainer's feedback). The row keeps
  // its height while hidden (see below), so on every narration line between two tool lists it left
  // an empty band nothing explained - the thread's spacing changed from one step to the next. The
  // answer is the thing worth rating, copying and sharing; the lines on the way to it are not.
  // And only on a message that SAYS something: the row a step-limit notice stands on alone has no
  // answer to rate, copy or share.
  const showActions = !isUser && !isError && !isGuardrail && endsTurn && !!message.content
  const showRewind = !readOnly && isUser && !message.queued && message.id != null && !streaming && !!onRewind
  // Copying your own message is worth as much as copying the reply - reworking a long prompt for a
  // fresh conversation means getting the original text back, and selecting it by hand out of a
  // bubble is exactly the chore the assistant side already has a button for. Offered on a queued
  // message too (its text exists and is final), unlike rewind, which needs a persisted id.
  const showUserCopy = isUser && !!message.content
  const showActionsRow = showActions || showRewind || showUserCopy

  // The kit's message layout: a user's right-aligned in primary ink at most 78% wide, the
  // assistant's the column's full width in secondary ink, an error in danger ink, at TEXT.body's 16px -
  // MarkdownRenderer's blocks match it, so agent and user messages stay the same size.
  //
  // NO BACKGROUND AND NO HORIZONTAL PADDING, since 2026-09-14 - the maintainer's call. The text
  // lines up with the thread's own edge (and ToolCallList above it), and there is no hover fill:
  // the action row appearing is enough to show which message is under the pointer.
  //
  // py-2, the action row pt-1 in 28px and mb-1 between messages, since 2026-09-30: a question sat
  // ~80px above the tool list under it and turns ~100px apart (the review's F25), so the thread
  // read as separate cards rather than one conversation. Roughly half of each now.
  //
  // The action row ALWAYS takes its space; only the buttons fade in on hover. It used to collapse
  // to zero height at rest and grow on hover, which pushed every message below it down by a row the
  // moment the pointer crossed a message. focus-within keeps it up while a keyboard user is on one
  // of its buttons, and on a touch screen, where nothing hovers, it is always shown.
  const footer = (
    <>
      {isUser && message.attachmentNames && message.attachmentNames.length > 0 && (
        <div className="flex flex-wrap gap-2 mt-2 justify-end animate-fade-in">
          {message.attachmentNames.map((name, i) => <SentAttachment key={`${name}-${i}`} name={name} />)}
        </div>
      )}
      {/* Eases away once the agent takes the message, rather than in one frame. */}
      <Presence show={isUser && !!message.queued}>
        <Caption className="mt-1">
          {message.injectAccepted ? t('Queued - read at the next step') : t('Queued')}
        </Caption>
      </Presence>
      {/* The sources arrive as the turn ends; the fold eases in under the answer. */}
      <Presence show={!isUser && !isError && !isGuardrail && !!message.sources?.length}>
        {message.sources && <SourceCitations sources={message.sources} />}
      </Presence>
      {isRedacted && <RedactionNote categories={message.guardrail?.categories ?? []} />}
      <Presence show={!isUser && message.stepLimit !== undefined}>
        {message.stepLimit !== undefined && <StepLimitNotice limit={message.stepLimit} spaced={!!message.content} />}
      </Presence>
      {/* A text link, not the destructive variant: Retry is the way back from a failure, and
          danger-quiet is for an action that removes something (CHAT-28). */}
      <Presence show={!!(isError && isLast && onRetry)}>
        <Button variant="hyperlink" className="mt-2" onClick={() => onRetry?.()}>{t('Retry')}</Button>
      </Presence>
    </>
  )

  const actions = showActionsRow ? (
    <>
      {showActions && (
        <MessageFeedbackRow
          messageId={message.id}
          content={message.content}
          conversationId={conversationId}
          allMessages={allMessages}
          messageIndex={messageIndex}
          readOnly={readOnly}
        />
      )}
      {showUserCopy && <MessageCopyButton text={message.content} label={t('Copy message')} />}
      {showRewind && (
        <IconButton
          size="sm"
          icon={RotateCcw}
          label={t('Rewind to this message')}
          tooltip={t('Rewind the conversation to this message and put its text back in the input, so you can edit and resend it. Everything after it is removed.')}
          onClick={() => onRewind!(message.id!)}
        />
      )}
    </>
  ) : undefined

  return (
    <KitChatMessage role={isUser ? 'user' : 'assistant'} error={isError} queued={isUser && message.queued} footer={footer} actions={actions}>
      {/* Who is speaking, for a screen reader (CHAT-06): the thread otherwise reads as one stream.
          The kit ChatMessage has no speaker prop yet, so it is said here. */}
      <span className="sr-only">{isUser ? t('You said:') : t('The assistant said:')}</span>
      {isUser
        ? <p className="whitespace-pre-wrap break-words">{renderMessageContent(message.content, message.mentions, message.skillMentions, mentionTypes)}</p>
        : isError
          ? <p className="whitespace-pre-wrap break-words">{message.content}</p>
          : isGuardrail
            ? <GuardrailNotice message={message} />
            : message.content && <MarkdownRenderer content={message.content} />}
    </KitChatMessage>
  )
}

// ChatWindow re-renders its whole message list on every streamed chunk (the array is rebuilt each
// time), which would otherwise force every settled bubble - not just the actively-streaming one -
// to re-render and re-run MarkdownRenderer's full from-scratch parse on every single chunk. For a
// long response that compounding cost (conversation length x chunk count) is what can freeze/OOM
// the tab. `message`, `isLast`, and `streaming` are compared because each of them already forces a
// real re-render at exactly the moments `allMessages`/`onRetry`/`onRewind` could otherwise matter
// (a new message appended, this bubble's own content changing, or a turn starting/ending) - so
// omitting those three from the comparison doesn't risk showing stale data, it just stops the
// pointless per-chunk re-render of every OTHER bubble in the conversation.
function messageBubblePropsEqual(prev: Props, next: Props): boolean {
  return (
    prev.message === next.message &&
    prev.isLast === next.isLast &&
    prev.endsTurn === next.endsTurn &&
    prev.streaming === next.streaming &&
    prev.conversationId === next.conversationId &&
    prev.messageIndex === next.messageIndex &&
    prev.readOnly === next.readOnly
  )
}

export const MessageBubble = memo(MessageBubbleImpl, messageBubblePropsEqual)

function buildTranscript(messages: ChatMessage[] | undefined, uptoIndex: number | undefined): string {
  if (!messages) return ''
  const scoped = uptoIndex != null ? messages.slice(0, uptoIndex + 1) : messages
  return scoped
    // NOT translated, and this is a correctness point rather than an omission. This transcript is
    // SENT - it goes to the feedback endpoint as the rated exchange - so "User:" and "Assistant:"
    // are wire markers a human reader on the other end parses, not interface copy. Translating
    // them would make a Vietnamese user's feedback unreadable to whoever triages it.
    .map(m => `${m.role === 'user' ? 'User' : 'Assistant'}: ${m.content}`)
    .join('\n')
}

// The minimum context a thumbs-up/down is worth reading with: the rated response plus the user
// message it answers. Walks BACKWARDS from this message rather than taking messageIndex - 1, since
// an assistant reply can follow other assistant-side messages (a retry, an error bubble) and the
// nearest real question may be several entries above. Falls back to the response alone when
// allMessages isn't available (the streaming bubble hasn't been folded into history yet).
function buildRatedExchange(messages: ChatMessage[] | undefined, index: number | undefined,
                            content: string): string {
  const response = `Assistant: ${content}`
  if (!messages || index == null) return response
  for (let i = index - 1; i >= 0; i--) {
    if (messages[i].role === 'user') return `User: ${messages[i].content}\n${response}`
  }
  return response
}

// The copy affordance for one message, either role - the kit's CopyButton (the tick in the brand
// ink, two seconds), so the user side gets exactly the same control as the assistant's.
//
// Media URLs are stripped on the way out (see stripConversationMediaUrls): they are server-local
// /api/v1/chat/... links that only resolve inside this deployment for this conversation, so
// pasting them anywhere else yields dead text.
// `label` arrives ALREADY TRANSLATED from the caller - it differs per call site ("Copy message"
// vs "Copy response") and translating it here would mean t() on a variable, which the checker
// cannot see. Only "Copied", which this component owns, is translated in here.
function MessageCopyButton({ text, label }: { text: string; label: string }) {
  const { t } = useTranslation()
  return <CopyButton getText={() => stripConversationMediaUrls(text)} label={label} copiedLabel={t('Copied')} />
}

function MessageFeedbackRow({ messageId, content, conversationId, allMessages, messageIndex, readOnly }: {
  messageId?: number
  content: string
  conversationId?: string
  allMessages?: ChatMessage[]
  messageIndex?: number
  readOnly?: boolean
}) {
  const submitFeedback = useSubmitFeedback()
  const [rating, setRating] = useState<'GOOD' | 'BAD' | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const { t } = useTranslation()

  // Both thumbs submit immediately and anonymously, carrying the rated exchange (the nearest
  // preceding user message + this response - see buildRatedExchange) rather than a bare mood with
  // no content: a rating nobody can read the answer behind is not usable signal. Anonymous by
  // construction here, unlike the "Leave feedback" modal, which is the user deliberately writing
  // something and gets to decide for itself.
  //
  // Clicking the thumb that is already lit takes the rating back, and clicking the other one flips
  // it - both retract the submitted row first (awaited, so the delete can't land after the insert
  // it was meant to precede), leaving one rating per message at most, or none.
  const handleRate = async (mood: 'GOOD' | 'BAD') => {
    if (messageId == null) return
    const undo = rating === mood
    setRating(undo ? null : mood)
    if (rating !== null && conversationId) {
      await chatApi.retractRating(conversationId, messageId).catch(() => {})
    }
    if (undo) return
    submitFeedback.mutate({
      mood,
      anonymous: true,
      includeConversation: true,
      conversationTranscript: buildRatedExchange(allMessages, messageIndex, content),
      conversationId,
      messageId,
      messageRating: true,
    })
  }

  // The kit's IconToggleButton (CHAT-18): the brand's ink and a filled glyph while given, as every
  // control's own state is drawn - a hand-made pair had a second "on" ink beside the copy tick.
  return (
    <>
      {messageId != null && !readOnly && (
        <>
          <IconToggleButton
            size="sm"
            icon={ThumbsUp}
            filled
            pressed={rating === 'GOOD'}
            onToggle={() => handleRate('GOOD')}
            label={t('Good response')}
            tooltip={rating === 'GOOD'
              ? t('Rated helpful - click again to take it back')
              : t('Rate this response helpful. When diagnostics are sent to Proxyma, it is sent anonymously with this reply and its question.')}
          />
          <IconToggleButton
            size="sm"
            icon={ThumbsDown}
            filled
            pressed={rating === 'BAD'}
            onToggle={() => handleRate('BAD')}
            label={t('Bad response')}
            tooltip={rating === 'BAD'
              ? t('Rated unhelpful - click again to take it back')
              : t('Rate this response unhelpful. When diagnostics are sent to Proxyma, it is sent anonymously with this reply and its question.')}
          />
        </>
      )}
      <MessageCopyButton text={content} label={t('Copy response')} />
      {/* Shares the conversation from its first message through THIS one - which is why it lives
          per-message rather than once at the top of the page. Never offered to a viewer already
          reading a shared link (readOnly): a share is the owner's to grant, not something a
          recipient can pass along.

          Server only. Desktop is one local user with no sign-in, so there is nobody to share WITH -
          the link could only ever be opened by the person who made it. It used to be offered there
          anyway, reframed in the tooltip as "a bookmark back into the conversation at that point",
          which is a real use but a thin one: conversation history already reopens any conversation,
          and the button spent a row of every reply advertising a capability whose whole point is a
          second person. */}
      {FEATURES.auth && messageId != null && conversationId && !readOnly && (
        <ShareButton conversationId={conversationId} messageId={messageId} />
      )}
      {/* Offered once a rating exists, either way round - a thumbs-up someone wants to explain is
          as useful as a complaint. ml-auto parks it at the far end of the action row, away from the
          icon buttons, and it reads as a link (same style as one in an agent reply) rather than a
          sixth button competing with them. */}
      {/* Fades in beside the row once a rating is given, and out when it is taken back. */}
      <Presence show={rating !== null && !readOnly} collapse={false} className="ml-auto">
        <Button variant="hyperlink" onClick={() => setModalOpen(true)}>
          {t('Leave feedback')}
        </Button>
      </Presence>
      {modalOpen && (
        <FeedbackModal
          onClose={() => setModalOpen(false)}
          initialMood={rating ?? undefined}
          chatContext={conversationId ? {
            conversationId,
            messageId,
            getTranscript: () => buildTranscript(allMessages, messageIndex),
          } : undefined}
        />
      )}
    </>
  )
}

// How long the copied confirmation (tick + label) stays up before reverting to the share icon.
const SHARE_COPIED_MS = 5000

// One click: mint the link, copy it, say so - the kit CopyButton, whose `getText` may be a
// request that fails (a toast then, and no tick), held longer and said in words (`status`). There
// is no dialog anymore - the token is idempotent per (conversation, message) on the backend, so a
// second click hands back the same URL rather than a second live link, which left the old
// confirm-then-copy modal with nothing to actually confirm.
function ShareButton({ conversationId, messageId }: { conversationId: string; messageId: number }) {
  const addToast = useToastStore(s => s.addToast)
  const { t } = useTranslation()

  return (
    // Offered on a server only (see the call site), so the sentence is the signed-in one; a desktop
    // arm here could never show (CHAT-24).
    <CopyButton
      icon={Share2}
      label={t('Copy share link for this conversation')}
      tooltip={t('Copy a read-only link to this conversation, from its first message through this reply. Anyone signed in who opens it can read that much and nothing after it, and cannot reply or change anything. The link has no expiry date - it keeps working until you delete the conversation.')}
      copiedLabel={t('Share link copied')}
      status
      copiedMs={SHARE_COPIED_MS}
      getText={async () => {
        const token = await chatApi.shareConversation(conversationId, messageId)
        return `${window.location.origin}/shared/${token}`
      }}
      onError={e => addToast(withReason(t('Could not create the share link'), e), 'danger')}
    />
  )
}

// What a withheld message or answer looks like in the thread.
//
// Muted rather than alarming. The overwhelmingly common case is somebody pasting a log file that
// happened to carry a token, not an attack - so this reads as a notice, not an accusation, and
// there is no Retry button because retrying the same text past the same rule does the same thing.
//
// The categories are shown and the matched text is not. That is not a UI nicety: the reason a
// credential rule fires is that the span it matched IS a credential, so a notice quoting it would
// be the control leaking the thing it exists to contain.
function GuardrailNotice({ message }: { message: ChatMessage }) {
  const categories = message.guardrail?.categories ?? []
  return (
    <NoticeCard icon={ShieldCheck} title={message.content}>
      {categories.length > 0 && (
        // A fact about the message, sentence case and unfilled: the kit Badge's outline variant.
        <div className="flex flex-wrap gap-2">
          {categories.map((c, i) => <Badge key={`${c}-${i}`} variant="outline" label={c} />)}
        </div>
      )}
    </NoticeCard>
  )
}

// The turn stopped because it ran out of steps (ChatSettings.maxIterations).
//
// The same quiet card as a guardrail's, and for the same reason: this is the product doing what it
// was configured to do, not failing - so no danger color and no Retry. It exists because nothing
// said so before (maintainer's feedback, 2026-10-01): the last allowed step is told to answer with
// what it has, and the reader got an answer that trailed off, or no answer at all, with the turn
// simply over.
function StepLimitNotice({ limit, spaced }: { limit: number; spaced: boolean }) {
  const { t } = useTranslation()
  return (
    <div className={cn(spaced && 'mt-3')}>
      <NoticeCard
        icon={CirclePause}
        title={t('Stopped at the limit of {count} step.|Stopped at the limit of {count} steps.', { count: limit })}
        caption={t('The work above may be unfinished. Send a message to continue from here. The limit is "Max agent iterations" in Settings > Agents.')}
      />
    </div>
  )
}

// Under an answer the output guardrail redacted: what kind of thing was cut out, never the text.
function RedactionNote({ categories }: { categories: string[] }) {
  const { t } = useTranslation()
  return (
    <NoticeCard
      inline
      icon={ShieldCheck}
      title={categories.length > 0
        ? t('Redacted by a guardrail: {reasons}', { reasons: categories.join(', ') })
        : t('Redacted by a guardrail')}
    />
  )
}

// One file sent with a message. Opens it in the preview, fetched from the conversation it was
// uploaded to - by its stored name, which is the name shown here (see ChatPage's handleSend). On a
// shared copy (no conversation of the reader's own) it is a plain chip: the file is the owner's,
// and a "Preview" that opened nothing promised what it could not do (CHAT-16).
function SentAttachment({ name }: { name: string }) {
  const conversationId = useChatStore(s => s.conversationId)
  const [open, setOpen] = useState(false)
  const { t } = useTranslation()
  // The kit's file chip - the same chip, at the same 14rem cap, the file had in the composer.
  const chip = (
    <Chip
      variant="file"
      icon={<Paperclip />}
      label={name}
      onClick={conversationId ? () => setOpen(true) : undefined}
      className="max-w-56"
    />
  )
  return (
    <>
      {conversationId ? <Tooltip label={t('Preview {name}', { name })}>{chip}</Tooltip> : chip}
      {open && conversationId && (
        <FilePreviewDialog source={{ name, url: attachmentUrl(conversationId, name) }} onClose={() => setOpen(false)} />
      )}
    </>
  )
}
