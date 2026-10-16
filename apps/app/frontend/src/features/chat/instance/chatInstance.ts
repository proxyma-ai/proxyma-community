import { createChatStore, type ChatStore } from '@/store/chatStore'
import { newChatStream, pause, stop as stopFollowing, type ChatStream } from '../utils/chatStream'
import { createHiddenPause } from '../utils/hiddenPause'
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
   * Loads the open conversation again from the server - what the lost-connection notice offers, and
   * what a stream does whose turn is gone (`lost`, chatStream.ts's `opened`). Set by the mounted
   * useChat, because the stream handler that raises that notice is defined first.
   */
  reload: { current: ((id: string, options?: { lost?: boolean }) => Promise<boolean>) | null }
  /**
   * Picks up the stream a hidden tab paused (chatStream.ts's `pause`), when the tab is shown again.
   * Set by the mounted useChat, like `reload`; a chat no useChat ever drove has no stream to pause.
   */
  resume: { current: (() => void) | null }
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
    resume: { current: null },
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

// Every chat on the page that may hold a stream: the main one, and each keyed one until it goes.
const live = new Set<ChatInstance>([main])

const registry = createInstanceRegistry<ChatInstance>({
  dispose: instance => {
    live.delete(instance)
    stopFollowing(instance.stream)
  },
  graceMs: IDLE_GRACE_MS,
})

// A TAB HIDDEN FOR HALF A MINUTE LETS GO OF ITS CHAT STREAMS (the 0.36.0 feedback). A browser keeps
// six HTTP/1.1 connections per site across ALL its tabs - and Desktop's http://127.0.0.1 is never
// spoken HTTP/2 - while a chat stream holds one for as long as a turn or a background sub-agent
// runs. A few tabs left in the background used the pool up, and the next page's requests waited
// for ever. Shown again, each chat picks up where it was (useChat's `resume`); the turn itself never
// stopped, server-side.
const HIDDEN_GRACE_MS = 30_000

if (typeof document !== 'undefined') {
  const tab = createHiddenPause({
    graceMs: HIDDEN_GRACE_MS,
    pause: () => {
      const now = Date.now()
      for (const instance of live) pause(instance.stream, now)
    },
    resume: () => { for (const instance of live) instance.resume.current?.() },
  })
  const follow = () => {
    if (document.visibilityState === 'hidden') tab.hidden()
    else tab.visible()
  }
  document.addEventListener('visibilitychange', follow)
  // A tab opened in the background starts hidden, with no change to announce it.
  follow()
}

/**
 * The instance for `key`, made on first use. The options are read when it is made; a later call
 * with other options gets the instance as it was made.
 */
export function chatInstanceFor(key: string, options: ChatInstanceOptions = {}): ChatInstance {
  if (key === MAIN_CHAT_KEY) return main
  return registry.get(key, () => {
    const instance = createChatInstance(key, options)
    live.add(instance)
    return instance
  })
}

/** Holds the instance for `key` while something shows it; call the result to let it go. */
export function retainChatInstance(key: string): () => void {
  if (key === MAIN_CHAT_KEY) return () => {}
  return registry.retain(key)
}
