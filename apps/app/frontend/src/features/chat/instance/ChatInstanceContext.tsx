import { createContext, useContext, useEffect, type ReactNode } from 'react'
import { useStore } from 'zustand'
import type { ChatState } from '@/store/chatStore'
import { chatInstanceFor, mainChatInstance, retainChatInstance, type ChatInstance } from './chatInstance'

// The chat page's instance unless a provider says otherwise - so the chat page, its history dialog
// and anything else rendered outside a provider behave exactly as they did with one global store.
const ChatInstanceContext = createContext<ChatInstance>(mainChatInstance())

/**
 * Renders `children` as the chat named `instanceKey`: everything inside reads and writes that
 * chat's state and stream (useChat, useChatStore, the thread, the composer, the prompts). One key is
 * one chat - two providers with the same key show the same chat - and the instance outlives a
 * remount (see chatInstance.ts). `surface` is read when the instance is first made.
 */
export function ChatInstanceProvider({ instanceKey, surface, children }: {
  instanceKey: string
  surface?: string
  children: ReactNode
}) {
  const instance = chatInstanceFor(instanceKey, { surface })
  useEffect(() => retainChatInstance(instanceKey), [instanceKey])
  return <ChatInstanceContext.Provider value={instance}>{children}</ChatInstanceContext.Provider>
}

/** The chat this component is rendered in. */
export function useChatInstance(): ChatInstance {
  return useContext(ChatInstanceContext)
}

/** A slice of the state of the chat this component is rendered in. */
export function useChatStore<T>(selector: (state: ChatState) => T): T {
  return useStore(useChatInstance().store, selector)
}
