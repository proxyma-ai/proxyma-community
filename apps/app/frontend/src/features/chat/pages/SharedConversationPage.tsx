import { useMemo } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Caption, ErrorState, THREAD_COLUMN, cn, textLink } from '@/components/ui/kit'
import { ChatWindow } from '../components/ChatWindow'
import { ChatInstanceProvider } from '../instance/ChatInstanceContext'
import { chatApi } from '../services/chatApi'
import { keys } from '@/services/queryKeys'
import { stripConversationMediaUrls } from '../utils/stripMediaUrls'
import { formatRelativeTime } from '@/utils/formatRelativeTime'
import type { ChatMessage } from '../types/chat.types'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { usePageTitle } from '@/hooks/usePageTitle'

// The recipient's view of a share link. Read-only in three independent ways, so no single mistake
// re-opens it: the backend accepts this token at exactly one GET endpoint and nowhere else, the
// real conversation id never reaches this page (so the owner-gated /chat/conversations/{id}/**
// routes cannot even be addressed), and ChatWindow renders in readOnly mode with no composer.
//
// A chat instance of its own, so the thread reads nothing of the chat page's - no prompt the chat
// page is waiting on, no turn it is running - when both have been open in this tab.
export function SharedConversationPage() {
  const { token = '' } = useParams<{ token: string }>()
  return (
    <ChatInstanceProvider instanceKey={`shared:${token}`}>
      <SharedConversationView token={token} />
    </ChatInstanceProvider>
  )
}

function SharedConversationView({ token }: { token: string }) {
  const { t } = useTranslation()
  // The tab says what this is - it kept the previous page's title (CHAT-32). Translated HERE, so the
  // catalog checker sees the literal (usePageTitle's own list of call sites is in the kit's
  // checker); the hook's lookup of an already-translated title returns it as it is.
  usePageTitle(t('Shared conversation'))

  const { data, isLoading, isError } = useQuery({
    queryKey: keys.sharedConversation(token),
    queryFn: () => chatApi.getSharedConversation(token),
    enabled: !!token,
    retry: false,
  })

  // Media a tool downloaded is served from the owner-gated conversation-media route, which a
  // viewer cannot read - rendering the raw markdown would give them broken images. Rewriting each
  // reference to its alt text / file name (the same pass the copy button already uses) shows what
  // the media was instead of a broken embed.
  const messages: ChatMessage[] = useMemo(
    () => (data?.messages ?? []).map(m => ({ ...m, content: stripConversationMediaUrls(m.content) })),
    [data],
  )

  if (isError) {
    return (
      // The kit ErrorState - what failed, what to know, the one way on - in the thread's column.
      <div className={cn(THREAD_COLUMN, 'px-3 sm:px-6 py-8')}>
        <ErrorState
          title={t('This shared conversation is no longer available')}
          action={<Link to="/" className={cn(textLink('brand'), 'text-xs')}>{t('Go to chat')}</Link>}
        >
          {/* One literal on one line - this was wrapped across two source lines, which the
              catalog checker cannot match. */}
          {t('The link may be incorrect, or the conversation it pointed to has been deleted. Share links stop working as soon as their conversation is removed.')}
        </ErrorState>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-full overflow-hidden">
      <ChatWindow messages={messages} streaming={false} loading={isLoading} readOnly />
      <div className="flex-shrink-0 px-2 sm:px-6 py-4">
        {/* Left-aligned on a phone, where the link in it is a target (nothing to tap mid-screen),
            centred from `sm` (CHAT-27). The second sentence is ONE string with the link as a slot
            (Rich), so a language can place it - it was a fragment, the link and a bare full stop
            (CHAT-32). */}
        <Caption className={cn(THREAD_COLUMN, 'text-left sm:text-center')}>
          {data?.sharedAt
            ? t('Shared by {who} {when}.', { who: data?.sharedByName ?? t('another user'), when: formatRelativeTime(data.sharedAt, { t }) })
            : t('Shared by {who}.', { who: data?.sharedByName ?? t('another user') })}
          {' '}
          <Rich
            text={t('It cannot be continued from here - {link}.')}
            parts={{ link: <Link to="/" className={textLink('brand', { inline: true })}>{t('start your own chat')}</Link> }}
          />
        </Caption>
      </div>
    </div>
  )
}
