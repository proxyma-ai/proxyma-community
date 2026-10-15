import { createChatStore, type ChatStore } from '@/store/chatStore'
import { newChatStream, stop as stopFollowing, type ChatStream } from '../utils/chatStream'
import { createInstanceRegistry } from './instanceRegistry'

/**
 * ONE CHAT: its state, its event stream, and what its stream handlers keep between events.
 *
 * <p>There was one of each per page until the key-account chat panel (docs/architecture.html#spec-11-8)
 * - the store a global zustand store, the stream and its handlers' memory module-level in
 * useChat.ts - so a second chat on screen would have written into the first. Each chat on a page is
 * an instance now: the chat page uses the MAIN one, which lives for the page's whole life exactly as
 * the module-level state did; a panel names its own by key (ChatInstanceProvider), held while
 * something shows it and for a grace period after (instanceRegistry.ts), so a remount mid-turn keeps
 * its stream and its thread.
 *
 * <p>Everything here is the hook's memory, not React state: useChat reads and writes it through the
 * instance it is given, and every component reads the store of the instance it is rendered in.
 */
export interface ChatInstance {
  /** Which chat this is - MAIN_CHAT_KEY for the chat page. */
  key: string
  /**
   * Where this chat's conversations are held when it is not the chat page (the backend's
   * ConversationSurface, e.g. `key-account`) - sent with every message, and recorded by the server
   * from a conversation's first message only. Undefined for the chat page's own.
   */
  surface: string | undefined
  store: ChatStore
  /** The one event stream this chat follows - see chatStream.ts. */
  stream: ChatStream
  /** The running answer's text so far - see useChat's 'text' handler. */
  textAcc: { current: string }
  /**
   * True from a click on the context-window indicator until that condensation's stream ends. A
   * manual condensation is not a turn, and three things differ for it: it stays "condensing" until
   * `done`, a run that freed nothing says so, and an error is a toast rather than an assistant bubble
   * with a Retry that would resend the previous message.
   */
  manualCondense: { current: boolean }
  /**
   * Set by a tool call: the next 'text' opens a new message (a new ReAct iteration) rather than
   * extending the open one. See useChat's 'tool-start'.
   */
  newSegmentPending: { current: boolean }
  /**
   * Loads the open conversation again from the server - what the lost-connection notice offers. Set
   * by the mounted useChat, because the stream handler that raises that notice is defined first.
   */
  reload: { current: ((id: string) => Promise<boolean>) | null }
  /** Queued messages already offered to the running turn - see useChat's offerToTurn. */
  offered: { current: Set<string> }
}

export interface ChatInstanceOptions {
  surface?: string
}

/** The chat page's chat. */
export const MAIN_CHAT_KEY = 'main'

export function createChatInstance(key: string, options: ChatInstanceOptions = {}): ChatInstance {
  return {
    key,
    surface: options.surface || undefined,
    store: createChatStore(),
    stream: newChatStream(),
    textAcc: { current: '' },
    manualCondense: { current: false },
    newSegmentPending: { current: false },
    reload: { current: null },
    offered: { current: new Set<string>() },
  }
}

// The chat page's instance: made once, never disposed - what the module-level state was.
const main = createChatInstance(MAIN_CHAT_KEY)

/**
 * The chat page's chat - for what genuinely belongs to it wherever it is read: the header's
 * working-agent dot, the history list's "this one is streaming". Everything inside a chat reads
 * the instance it is rendered in instead (useChatInstance).
 */
export function mainChatInstance(): ChatInstance {
  return main
}

// Long enough for an unmount and the mount that replaces it (AppShell remounts the page on every
// pathname change) and for a slow first commit; short enough that a panel nobody shows stops
// following its conversation soon after. Disposing only STOPS FOLLOWING - the turn goes on
// server-side, and a panel shown again resumes it from the server.
const IDLE_GRACE_MS = 10_000

const registry = createInstanceRegistry<ChatInstance>({
  dispose: instance => stopFollowing(instance.stream),
  graceMs: IDLE_GRACE_MS,
})

/**
 * The instance for `key`, made on first use. The options are read when it is made; a later call
 * with other options gets the instance as it was made.
 */
export function chatInstanceFor(key: string, options: ChatInstanceOptions = {}): ChatInstance {
  if (key === MAIN_CHAT_KEY) return main
  return registry.get(key, () => createChatInstance(key, options))
}

/** Holds the instance for `key` while something shows it; call the result to let it go. */
export function retainChatInstance(key: string): () => void {
  if (key === MAIN_CHAT_KEY) return () => {}
  return registry.retain(key)
}
