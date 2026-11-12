/**
 * A DEPLOYED AGENT IS READ, NOT TALKED TO (the maintainer, 2026-11-04: "A sub-agent's transcript (via
 * the agents map or the main chat interface) should show a modal instead of opening it in a new
 * conversation. The intention is not to allow the user to talk with it.").
 *
 * Every deployed agent is a conversation of its own. The page opens one in a read-only transcript
 * dialog (AgentTranscriptDialog) - from its row in the agents map, from its panel in the thread, and
 * from an old link to its conversation, which goes to the conversation the person opened with the
 * dialog over it. The agents a deployed agent deployed open in the SAME dialog, one step deeper, with a
 * way back - never a dialog stacked on a dialog for every level. The server refuses a person's message
 * into such a conversation (ChatService.DEPLOYED_AGENT_REFUSAL).
 *
 * Plain .ts with no imports, so `node --test` runs it - agentTranscript.test.ts.
 */

/** The conversations the dialog has walked down, the one shown LAST. Empty: the dialog is closed. */
export type TranscriptTrail = readonly string[]

/**
 * The trail once `conversationId` is opened in it: the first step of a closed dialog; the same trail
 * for the one already shown; back to it for one walked through already (a link to an ancestor); else
 * one step deeper.
 */
export function openInTrail(trail: TranscriptTrail, conversationId: string): TranscriptTrail {
  if (!conversationId) return trail
  const at = trail.indexOf(conversationId)
  if (at >= 0) return trail.slice(0, at + 1)
  return [...trail, conversationId]
}

/** One step back; the dialog closes (an empty trail) after its first step. */
export function backInTrail(trail: TranscriptTrail): TranscriptTrail {
  return trail.slice(0, -1)
}

/** The conversation shown, or undefined for a closed dialog. */
export function shownInTrail(trail: TranscriptTrail): string | undefined {
  return trail.length > 0 ? trail[trail.length - 1] : undefined
}

/** Where Back goes, or undefined on the first step - where there is no Back. */
export function backTarget(trail: TranscriptTrail): string | undefined {
  return trail.length > 1 ? trail[trail.length - 2] : undefined
}

/** What a click on an agent's name in the agents map does. */
export type MapPick =
  /** The main agent, whose conversation is the one open: its latest turn (threadReveal.ts). */
  | { kind: 'reveal' }
  /** The main agent from elsewhere: its conversation, which the person talks to. */
  | { kind: 'navigate'; to: string }
  /** A deployed agent: its transcript, read-only. */
  | { kind: 'transcript'; conversationId: string }

export function mapPick({ target, openConversationId, rootConversationId }: {
  /** The conversation of the agent clicked. */
  target: string
  /** The conversation the page has open. */
  openConversationId: string
  /** The conversation the person opened, at the top of the tree. */
  rootConversationId: string
}): MapPick {
  if (target !== rootConversationId) return { kind: 'transcript', conversationId: target }
  return target === openConversationId ? { kind: 'reveal' } : { kind: 'navigate', to: target }
}

/**
 * Where the chat page goes when the conversation it was asked for is a deployed agent's (an old link,
 * a bookmark): the conversation the person opened at the top of its tree, with the agent's transcript
 * open over it - never a composer under a deployed agent. Null for any other conversation.
 */
export function deployedAgentRedirect({ conversationId, self, rootConversationId }: {
  conversationId: string | undefined
  /** The agent tree's `self` for that conversation: set only when it is a deployed agent's. */
  self: { conversationId: string } | null | undefined
  rootConversationId: string | undefined
}): { to: string; transcript: string } | null {
  if (!conversationId || !self || self.conversationId !== conversationId) return null
  if (!rootConversationId || rootConversationId === conversationId) return null
  return { to: rootConversationId, transcript: conversationId }
}

/** How a thread is drawn: the person's own chat, a share link's read-only copy, or a deployed agent's transcript. */
export type ThreadView = 'chat' | 'shared' | 'transcript'

/** What a thread offers the reader, by how it is drawn. */
export interface ThreadAffordances {
  /** Rate, share and feedback under a message, Retry under a failed answer, rewind a question. */
  messageActions: boolean
  /** The assistant's own questions and the step-limit question, answered in place. */
  questions: boolean
  /**
   * A write waiting for approval, decided in place. A transcript keeps it: a deployed agent in Ask
   * mode asks in its own conversation and waits there, and approving a write is no message to it -
   * without it the agent would wait out the approval's half hour and be told no.
   */
  approvals: boolean
  /** A deployed agent's panel in the thread: its controls and the way into its transcript. */
  agentControls: boolean
}

export function threadAffordances(view: ThreadView): ThreadAffordances {
  switch (view) {
    case 'chat': return { messageActions: true, questions: true, approvals: true, agentControls: true }
    case 'transcript': return { messageActions: false, questions: false, approvals: true, agentControls: true }
    case 'shared': return { messageActions: false, questions: false, approvals: false, agentControls: false }
  }
}
