import { useLocation } from 'react-router-dom'
import { useStore } from 'zustand'
import { mainChatInstance } from '@/features/chat/instance/chatInstance'
import { useActiveConversations } from '@/features/chat/hooks/useActiveConversations'

/**
 * Agents working where you are not looking, and where to find them.
 *
 * `navTo` is the header destination that owns them - the nav word that carries the badge - `path`
 * is the most recently started one to go back to, and `count` how many there are.
 */
export interface WorkingAgent {
  navTo: string
  path: string
  count: number
}

/**
 * Every agent still working, so the header can say so (the maintainer's request, 2026-09-27).
 *
 * SINCE 2026-09-28 CONVERSATIONS RUN SIDE BY SIDE, so this counts every conversation of the
 * caller's with a turn running (from the server - useActiveConversations), not only the one this
 * tab is following. The conversation on screen is left out: it shows its own progress, and a badge
 * pointing at the page you are reading goes nowhere. The open conversation's own streaming flag is
 * folded in too, so a turn shows the moment it starts rather than at the next poll.
 *
 * Deliberately a list: dedicated agent pages are planned, each adding its own entry under its own
 * nav destination, and the header needs no change.
 */
export function useWorkingAgents(): WorkingAgent[] {
  const location = useLocation()
  // The CHAT PAGE'S chat, wherever this is read - the dot points back to /chat/{id}. A panel's chat
  // (a conversation with a surface) is not listed: the server leaves those out of the active list.
  const streaming = useStore(mainChatInstance().store, s => s.streaming)
  const openId = useStore(mainChatInstance().store, s => s.conversationId)
  const active = useActiveConversations()

  const ids = streaming && openId && !active.includes(openId) ? [openId, ...active] : active
  const onChat = location.pathname === '/' || location.pathname.startsWith('/chat')
  const elsewhere = onChat ? ids.filter(id => id !== openId) : ids
  if (elsewhere.length === 0) return []
  return [{ navTo: '/', path: `/chat/${elsewhere[0]}`, count: elsewhere.length }]
}
