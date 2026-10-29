import { useRef, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useStore } from 'zustand'
import { History, LogIn, MessageSquarePlus, PanelLeftClose, PanelLeftOpen, Pin, PinOff, Power, Settings, SquarePen, Trash2 } from 'lucide-react'
import {
  AppSidebar, BRAND_LINK, ConfirmDialog, HoverActions, IconButton, Presence, SidebarSection, Tooltip, WorkingBadge, cn, sidebarRow, textLink, useIdleRotation,
} from '@/components/ui/kit'
import { Logo } from '@/components/ui/Logo'
import { AppearanceRow, LanguageRow } from './SidebarPreferences'
import { useNavItems } from './navItems'
import { UserMenu } from '@/features/auth/components/UserMenu'
import { ChatHistoryModal } from '@/features/chat/components/ChatHistoryModal'
import { mainChatInstance } from '@/features/chat/instance/chatInstance'
import { resetChatInstance } from '@/features/chat/hooks/useChat'
import { useActiveConversations } from '@/features/chat/hooks/useActiveConversations'
import { chatApi } from '@/features/chat/services/chatApi'
import { useWorkingAgents } from '@/hooks/useWorkingAgents'
import { keys } from '@/services/queryKeys'
import { useAuthStore } from '@/store/authStore'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useToastStore } from '@/store/toastStore'
import { withReason } from '@/services/apiError'
import type { Conversation } from '@/features/chat/types/chat.types'
import { useFeedbackModalStore } from '@/store/feedbackModalStore'
import { useQuitStore } from '@/store/quitStore'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'

/** How many recent conversations the sidebar lists at first; Show more adds twenty at a time. */
const RECENT = 12
const MORE = 20
/** Whether the sidebar is folded to its icons, kept in this browser. */
const FOLD_KEY = 'proxyma-sidebar-folded'

function readFolded(): boolean {
  try { return localStorage.getItem(FOLD_KEY) === '1' } catch { return false }
}

/**
 * THE SIDEBAR OF THE GLASS SHELL, from `lg` (the maintainer's request, 2026-10-29: "no header at all
 * + glass effect", with the pages and recent chats in it). Top to bottom: the logo and the fold
 * button; New chat; the pages this reader may open, each with the count of agents still working
 * there; the recent conversations, the history a click away; and at the foot Settings and the
 * account (the app menu on Desktop), opening upward.
 *
 * It folds to its icons - remembered in this browser - and the recent chats then leave it. Between
 * `md` and `lg`, and in a narrow desktop window, it is always folded; on a phone the app keeps its
 * header and phone menu instead (AppShell). A visitor who is not signed in gets it too, with Sign in
 * at its foot.
 *
 * A conversation opened from here leaves the chat page's own chat first: the page sends the address
 * back to a conversation it still holds (resetChatInstance).
 */
export function Sidebar({ compact = false }: {
  /** A desktop window narrower than `lg`: folded to its icons, with no way to unfold it there. */
  compact?: boolean
} = {}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore(s => s.user)
  const items = useNavItems()
  const working = useWorkingAgents()
  const [foldedByChoice, setFolded] = useState(readFolded)
  const folded = compact || foldedByChoice
  const [historyOpen, setHistoryOpen] = useState(false)
  const iconRef = useRef<SVGSVGElement>(null)
  useIdleRotation(iconRef)

  const fold = (next: boolean) => {
    setFolded(next)
    try { localStorage.setItem(FOLD_KEY, next ? '1' : '0') } catch { /* storage blocked: it lasts this visit */ }
  }
  const onChat = location.pathname === '/' || location.pathname.startsWith('/chat')
  const openId = useStore(mainChatInstance().store, s => s.conversationId)

  const newChat = () => {
    resetChatInstance(mainChatInstance())
    navigate('/')
  }
  const openConversation = (id: string) => {
    if (id !== mainChatInstance().store.getState().conversationId) resetChatInstance(mainChatInstance())
    navigate(`/chat/${id}`)
  }

  // Folded, the logo, the expand control and the rows under it are one column of 40px squares, a
  // row's gap apart (the maintainer's review, 2026-10-29: the expand button "arranged just like a
  // menu icon", as far from the logo as from New chat). Open, the fold control sits at the right of
  // the logo.
  const head = folded ? (
    <div className="flex flex-col items-center gap-1">
      <NavLink to="/" className={cn(BRAND_LINK, 'w-10 h-10 grid place-items-center')} aria-label={t('Chat')}>
        <Logo iconRef={iconRef} markOnly />
      </NavLink>
      {!compact && (
        <Tooltip label={t('Expand the sidebar')}>
          <button type="button" onClick={() => fold(false)} aria-label={t('Expand the sidebar')} className={sidebarRow({ folded: true })}>
            <PanelLeftOpen size={16} className="shrink-0" aria-hidden="true" />
          </button>
        </Tooltip>
      )}
    </div>
  ) : (
    <div className="flex items-center justify-between gap-1 pb-2 pl-2 pr-1 h-11">
      <NavLink to="/" className={BRAND_LINK}>
        <Logo iconRef={iconRef} />
      </NavLink>
      <IconButton icon={PanelLeftClose} label={t('Collapse the sidebar')} onClick={() => fold(true)} />
    </div>
  )

  const row = (key: string, content: React.ReactNode, label: string) =>
    folded ? <Tooltip key={key} label={label}>{content as React.ReactElement}</Tooltip> : <span key={key} className="contents">{content}</span>

  // THE FOOT (the maintainer, 2026-10-30: the account menu holds only the account): Send feedback,
  // Appearance, Language and Settings as rows of the panel, then under a hairline the account - its
  // menu has the address and Sign out - or, on Desktop, which has no account, Quit; for a visitor
  // who is not signed in, Sign in. A visitor has no Settings and sends no feedback.
  const guest = FEATURES.auth && !user
  const action = (key: string, icon: typeof Settings, label: string, onClick: () => void, danger = false) => {
    const Icon = icon
    return row(key, (
      <button type="button" onClick={onClick} aria-label={folded ? label : undefined}
        className={cn(sidebarRow({ folded }), danger && 'text-danger mouse:hover:text-danger')}>
        <Icon size={16} className="shrink-0" aria-hidden="true" />
        {!folded && <span className="flex-1 min-w-0 truncate text-left">{label}</span>}
      </button>
    ), label)
  }
  const foot = (
    <>
      {!guest && action('feedback', MessageSquarePlus, t('Send feedback'), () => useFeedbackModalStore.getState().open())}
      <AppearanceRow folded={folded} />
      <LanguageRow folded={folded} />
      {!guest && action('settings', Settings, t('Settings'), () => useSettingsModalStore.getState().open())}
      <div className={cn('pt-1 border-t border-line/60', folded ? 'w-10' : 'w-full')}>
        {guest ? row('sign-in', (
          <NavLink to="/login" aria-label={folded ? t('Sign in') : undefined}
            className={sidebarRow({ active: location.pathname === '/login', folded })}>
            <LogIn size={16} className="shrink-0" aria-hidden="true" />
            {!folded && <span className="flex-1 min-w-0 truncate">{t('Sign in')}</span>}
          </NavLink>
        ), t('Sign in'))
          : FEATURES.auth
            ? <UserMenu inSidebar folded={folded} />
            : action('quit', Power, t('Quit Proxyma'), () => useQuitStore.getState().ask(), true)}
      </div>
    </>
  )

  return (
    <>
      <AppSidebar ariaLabel={t('Main')} folded={folded} head={head} foot={foot}>
        {row('new', (
          <button type="button" onClick={newChat} aria-label={folded ? t('New chat') : undefined} className={sidebarRow({ folded })}>
            <SquarePen size={16} className="shrink-0" aria-hidden="true" />
            {!folded && <span className="flex-1 min-w-0 truncate text-left">{t('New chat')}</span>}
          </button>
        ), t('New chat'))}
        {items.map(item => {
          const busy = working.find(w => w.navTo === item.to)
          return row(item.to, (
            <NavLink to={item.to} aria-current={item.isActive ? 'page' : undefined} aria-label={folded ? item.label : undefined}
              className={sidebarRow({ active: item.isActive, folded })}>
              <item.icon size={16} className="shrink-0" aria-hidden="true" />
              {!folded && <span className="flex-1 min-w-0 truncate">{item.label}</span>}
              <Presence show={!!busy} collapse={false} className={folded ? 'absolute top-0.5 right-0.5' : undefined}>
                {busy && (
                  <WorkingBadge count={busy.count}
                    label={t('{count} conversation still working - go to the latest|{count} conversations still working - go to the latest', { count: busy.count })}
                    onClick={() => navigate(busy.path)} />
                )}
              </Presence>
            </NavLink>
          ), item.label)
        })}
        {!folded && (
          <RecentChats activeId={onChat ? openId : undefined} onOpen={openConversation} onHistory={() => setHistoryOpen(true)}
            onDeletedOpen={newChat} />
        )}
      </AppSidebar>
      {historyOpen && (
        <ChatHistoryModal
          activeId={onChat ? openId ?? undefined : undefined}
          onSelect={id => { setHistoryOpen(false); openConversation(id) }}
          onClose={() => setHistoryOpen(false)}
        />
      )}
    </>
  )
}

/**
 * THE RECENT CONVERSATIONS (the maintainer, 2026-10-30: "pinnable, deletable, and show more +
 * scrollable"): pinned ones first, then the newest, one line each with a blinking dot on one whose
 * turn is running; Pin and Delete on each row under the pointer (always on glass), a delete asking
 * first as the history does; Show more adds twenty, and the list scrolls in its own box under the
 * pages, which stay put. The history dialog, with every conversation, is the heading's action.
 *
 * A navigation list, not a list of records: the twenty-a-page rule (frontend.md) exempts it, and the
 * maintainer asked for Show more here.
 */
function RecentChats({ activeId, onOpen, onHistory, onDeletedOpen }: {
  activeId?: string | null
  onOpen: (id: string) => void
  onHistory: () => void
  /** The open conversation was deleted: leave it for a new chat. */
  onDeletedOpen: () => void
}) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const [shown, setShown] = useState(RECENT)
  const [confirming, setConfirming] = useState<Conversation | null>(null)
  // Under the conversations key, so every change to the list (a title, a pin, a turn) refreshes it.
  const list = useInfiniteQuery({
    queryKey: [...keys.conversations(), 'sidebar'],
    queryFn: ({ pageParam }) => chatApi.listConversations(pageParam),
    initialPageParam: 0,
    getNextPageParam: last => (last.hasMore ? last.page + 1 : undefined),
  })
  const running = useActiveConversations()
  const all = (list.data?.pages ?? []).flatMap(p => p.items)
  // Pinned first, each half in the server's order (newest first).
  const ordered = [...all.filter(c => c.pinned), ...all.filter(c => !c.pinned)]
  const recent = ordered.slice(0, shown)
  const canShowMore = ordered.length > shown || !!list.hasNextPage
  const showMore = () => {
    const next = shown + MORE
    setShown(next)
    if (next > all.length && list.hasNextPage) void list.fetchNextPage()
  }
  const refresh = () => qc.invalidateQueries({ queryKey: keys.conversations() })
  const pin = useMutation({
    mutationFn: ({ id, pinned }: { id: string; pinned: boolean }) => chatApi.setPinned(id, pinned),
    onSuccess: refresh,
    onError: (e: unknown) => useToastStore.getState().addToast(withReason(t('Failed to update pin'), e), 'danger'),
  })
  const del = useMutation({
    mutationFn: chatApi.deleteConversation,
    onSuccess: (_, id) => {
      void refresh()
      if (id === activeId) onDeletedOpen()
    },
    onError: (e: unknown) => useToastStore.getState().addToast(withReason(t('Failed to delete conversation'), e), 'danger'),
  })
  return (
    <div className="flex-1 min-h-0 flex flex-col gap-0.5 animate-fade-in">
      <SidebarSection
        title={t('Recent chats')}
        action={<IconButton icon={History} label={t('Conversation history')} size="sm" onClick={onHistory} />}
      />
      <div className="flex-1 min-h-0 overflow-y-auto overflow-x-hidden flex flex-col gap-0.5">
        {recent.length === 0 ? (
          <p className="px-2.5 py-1 text-xs text-ink-caption">{t('Your conversations appear here.')}</p>
        ) : recent.map(c => (
          <div key={c.id} className="group relative shrink-0">
            <Tooltip label={c.title || t('New conversation')} fullWidth>
              <button type="button" onClick={() => onOpen(c.id)} aria-current={c.id === activeId ? 'page' : undefined}
                className={cn(sidebarRow({ active: c.id === activeId }), 'h-8 touch:h-10 touch:pr-16 mouse:group-hover:pr-16 group-focus-within:pr-16')}>
                {c.pinned && <Pin size={12} className="shrink-0 text-ink-caption fill-current" aria-label={t('Pinned')} />}
                <span className="flex-1 min-w-0 truncate text-left">{c.title || t('New conversation')}</span>
                {running.includes(c.id) && <span aria-label={t('Working')} className="shrink-0 w-1.5 h-1.5 rounded-full bg-success animate-pulse" />}
              </button>
            </Tooltip>
            <HoverActions className="absolute right-1 top-1/2 -translate-y-1/2">
              <IconButton size="sm" icon={c.pinned ? PinOff : Pin} label={c.pinned ? t('Unpin') : t('Pin')}
                onClick={() => pin.mutate({ id: c.id, pinned: !c.pinned })} busy={pin.isPending && pin.variables?.id === c.id} />
              <IconButton size="sm" tone="danger" icon={Trash2} label={t('Delete')} onClick={() => setConfirming(c)} />
            </HoverActions>
          </div>
        ))}
        <Presence show={canShowMore && recent.length > 0}>
          <button type="button" onClick={showMore} disabled={list.isFetchingNextPage}
            className={cn(textLink('quiet'), 'text-xs mx-2.5 my-1 self-start')}>
            {t('Show more')}
          </button>
        </Presence>
      </div>
      {confirming && (
        <ConfirmDialog
          title={t('Delete conversation')}
          description={t('You are about to {verb}:', { verb: t('delete') })}
          subject={confirming.title}
          consequences={[t('The conversation and every message in it are deleted. This cannot be undone.')]}
          confirmLabel={t('Delete')}
          cancelLabel={t('Cancel')}
          pendingLabel={t('Deleting...')}
          pending={del.isPending}
          onConfirm={() => del.mutate(confirming.id, { onSuccess: () => setConfirming(null) })}
          onCancel={() => setConfirming(null)}
        />
      )}
    </div>
  )
}
