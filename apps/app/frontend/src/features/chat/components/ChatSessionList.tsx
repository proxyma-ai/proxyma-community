import { useEffect, useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Trash2, Pin, PinOff } from 'lucide-react'
import type { ReactNode } from 'react'
import {
  EmptyState, FadeSwap, HoverActions, IconButton, InlineConfirm, List, ListRow, ListRowSkeleton, MetaList, Presence, Readout, StatusDot,
  pageCount, usePageFor,
} from '@/components/ui/kit'
import { ListPager } from '@/components/ui/ListPager'
import { keys } from '@/services/queryKeys'
import { chatApi, type ConversationListSource } from '../services/chatApi'
import { useToastStore } from '@/store/toastStore'
import { formatRelativeTime } from '@/utils/formatRelativeTime'
import type { Conversation } from '../types/chat.types'
import { useActiveConversations } from '../hooks/useActiveConversations'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useTranslation } from '@/i18n'
import { withReason } from '@/services/apiError'

interface Props {
  activeId?: string
  onSelect: (id: string) => void
  /** The one control that fills an empty list - the history dialog passes "Ask a question". */
  emptyAction?: ReactNode
  /**
   * Whose conversations: the chat page's when absent, else the ones a page keeps its own record of -
   * a key account's panel lists the conversations bound to the account and nothing else.
   */
  source?: ConversationListSource
  /** Told of each conversation deleted from the list - a panel moves off one it has open. */
  onDeleted?: (id: string) => void
}

// The chat page's own list: the bare 'conversations' key, which every invalidation refreshes.
const CHAT_PAGE: ConversationListSource = {
  queryKey: keys.conversations(),
  list: page => chatApi.listConversations(page),
}

export function ChatSessionList({ activeId, onSelect, emptyAction, source = CHAT_PAGE, onDeleted }: Props) {
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const { t } = useTranslation()
  // Conversations with a turn running now - each gets a blinking dot (since 2026-09-28, when they
  // began running side by side). The open one's own flag too, so its dot needs no poll to appear.
  const active = useActiveConversations(source.surface)
  const openStreamingId = useChatStore(s => (s.streaming ? s.conversationId : undefined))
  const isWorking = (id: string) => id === openStreamingId || active.includes(id)
  // NUMBERED PAGES of twenty (LIST_PAGE_SIZE, 2026-10-14 - it loaded more on scrolling to the end).
  // The page is kept while the list refreshes (a new title, a pin, another turn finishing) and goes
  // back to the first only for another list. The rows shown stay while the next page loads.
  const { page, setPage } = usePageFor(source.queryKey)
  const { data, isLoading } = useQuery({
    queryKey: [...source.queryKey, { page }],
    queryFn: () => source.list(page),
    placeholderData: keepPreviousData,
  })
  const total = data?.totalItems ?? data?.items.length ?? 0
  const pages = pageCount(total)
  // A delete that empties the last page moves the reader to the page before it.
  useEffect(() => {
    if (data && page > 0 && page >= pages) setPage(Math.max(0, pages - 1))
  }, [data, page, pages, setPage])
  const del = useMutation({
    mutationFn: chatApi.deleteConversation,
    onSuccess: (_, id) => {
      void qc.invalidateQueries({ queryKey: keys.conversations() })
      onDeleted?.(id)
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to delete conversation'), e), 'danger'),
  })
  const pin = useMutation({
    mutationFn: ({ id, pinned }: { id: string; pinned: boolean }) => chatApi.setPinned(id, pinned),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.conversations() }),
    onError: (e: unknown) => addToast(withReason(t('Failed to update pin'), e), 'danger'),
  })
  // Confirmation is inline (the kit InlineConfirm in place of the row's actions), not a modal -
  // deleting a conversation has no sub-consequences worth a full dialog's weight.
  const [confirmingId, setConfirmingId] = useState<string | null>(null)

  const conversations = data?.items ?? []

  return (
    // A flex child that may shrink, not `h-full`: the dialog around it has a max height and no
    // height, so a percentage had nothing to resolve against and the list grew past the panel
    // instead of scrolling inside it.
    <div className="flex flex-col flex-1 min-h-0 overflow-hidden">
      <div className="flex-1 min-h-0 overflow-y-auto">
        {/* Loading, empty and the list swap by a fade rather than in one frame. */}
        <FadeSwap swapKey={isLoading ? 'loading' : conversations.length === 0 ? 'empty' : 'list'}>
        {isLoading ? (
          // The kit's row skeleton, on the rows' own geometry and inset.
          <div className="px-5 divide-y divide-line/20" role="status" aria-busy="true">
            <span className="sr-only">{t('Loading conversations')}</span>
            {Array.from({ length: 6 }, (_, i) => <ListRowSkeleton key={i} />)}
          </div>
        ) : conversations.length === 0 ? (
          // A sentence and the action that fills the list (app UI review, F12) - it was a speech
          // bubble over centered disabled-grey text, which said what was missing and not how to
          // change that.
          <EmptyState className="px-5" action={emptyAction}>
            {t('Your conversations are listed here, newest first, from the first question you ask.')}
          </EmptyState>
        ) : (
          <>
            {/* The kit's rows, each one that OPENS and carries its own actions (ListRow with onClick
                and action): the text is the button, pin and delete sit beside it, and the whole row
                takes the hover fill - a stretched title button did the first two by hand and lost
                the third (CHAT-34). `inset` keeps the fill inside the list, whose `px-3` and the
                row's own `px-2` line the text up with the dialog's title at 20px. The actions show
                on hover or focus under a mouse and ALWAYS on a touch screen (HoverActions). */}
            <List className="px-3">
              {conversations.map((c: Conversation) => {
                const deleting = del.isPending && del.variables === c.id
                const pinLabel = c.pinned
                  ? t('Unpin - this conversation will then be deleted after 7 days without activity')
                  : t('Pin - keeps this conversation forever. Unpinned conversations are deleted after 7 days without activity')
                return (
                  <ListRow
                    key={c.id}
                    selected={activeId === c.id}
                    onClick={() => onSelect(c.id)}
                    inset
                    titleClamp={2}
                    leading={isWorking(c.id) ? <StatusDot label={t('Still working')} /> : undefined}
                    title={c.title}
                    // The facts UNDER the title, as every list row's meta line (maintainer, 2026-10-06):
                    // they sat at the right of the title, squeezing it, and said only the time. The
                    // pin mark leads, then when it was last active, then how many messages the person
                    // sent - `messageCount` counts their own messages, one per exchange, never the
                    // assistant's replies or the hidden rows a skill or condensation stores.
                    caption={(
                      <span className="flex items-center gap-1.5 min-w-0">
                        {/* Fades in and out as the conversation is pinned and unpinned. */}
                        <Presence show={!!c.pinned} collapse={false} className="shrink-0 flex">
                          <Pin size={12} className="text-brand-accent-legible fill-current" aria-hidden="true" />
                          <span className="sr-only">{t('Pinned')}</span>
                        </Presence>
                        <MetaList
                          className="text-xs text-ink-caption truncate"
                          items={[
                            <Readout key="when">{formatRelativeTime(c.updatedAt, { compact: true, t })}</Readout>,
                            <Readout key="count">{t('{count} message|{count} messages', { count: c.messageCount })}</Readout>,
                          ]}
                        />
                      </span>
                    )}
                    action={confirmingId === c.id ? (
                      <InlineConfirm
                        cancelLabel={t('Cancel')}
                        confirmLabel={t('Delete')}
                        pendingLabel={t('Deleting...')}
                        pending={deleting}
                        onCancel={() => setConfirmingId(null)}
                        onConfirm={() => del.mutate(c.id, { onSuccess: () => setConfirmingId(null) })}
                      />
                    ) : (
                      <HoverActions>
                        {/* Spells out the retention rule at the exact control that governs it -
                            pinning is the ONLY thing that exempts a conversation from
                            ConversationRetentionSweep's 7-day idle deletion, so a bare "Pin"
                            tooltip hides the one consequence that actually matters. */}
                        <IconButton
                          size="sm"
                          icon={c.pinned ? PinOff : Pin}
                          label={c.pinned ? t('Unpin') : t('Pin')}
                          tooltip={pinLabel}
                          onClick={() => pin.mutate({ id: c.id, pinned: !c.pinned })}
                          busy={pin.isPending && pin.variables?.id === c.id}
                        />
                        <IconButton
                          size="sm"
                          tone="danger"
                          icon={Trash2}
                          label={t('Delete')}
                          onClick={() => setConfirmingId(c.id)}
                          disabled={deleting}
                        />
                      </HoverActions>
                    )}
                  />
                )
              })}
            </List>
          </>
        )}
        </FadeSwap>
      </div>
      {/* Under the scrolling rows, so it stays in reach at any scroll position. */}
      <ListPager
        className="px-5 border-t border-line/20"
        page={page}
        pages={pages}
        onPage={setPage}
        countText={t('{count} conversation|{count} conversations', { count: total })}
      />
    </div>
  )
}
