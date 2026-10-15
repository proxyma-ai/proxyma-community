// What a chat panel (ChatPanel) decides before it sends a message - kept out of the component so
// it is tested without a DOM (`node --test`, panelSend.test.ts).

/**
 * The skills a panel's message is sent with: what the user picked, then the panel's own (its
 * skill, picked on every message), each once and in that order. Undefined when there are none, as
 * the chat page sends it.
 */
export function withPanelSkills(picked: readonly string[] | undefined, fixed: readonly string[] | undefined): string[] | undefined {
  const all: string[] = []
  for (const name of [...(picked ?? []), ...(fixed ?? [])]) {
    if (!all.some(n => n.toLowerCase() === name.toLowerCase())) all.push(name)
  }
  return all.length > 0 ? all : undefined
}

/**
 * Whether the page has to bind the conversation before this message is sent: the panel started it
 * (the page named none), the page asked to be told, nothing has been said in it yet, and its id
 * has not been handed over already - a send the page refused once is offered to it again.
 */
export function bindsBeforeSend(state: {
  /** The conversation the page named, if any. */
  pageConversationId: string | undefined
  /** Whether the page passed onConversationCreated. */
  pageBinds: boolean
  messageCount: number
  streaming: boolean
  /** The id this send will go out under. */
  conversationId: string
  /** The id already handed to the page, if any. */
  announcedId: string | null
}): boolean {
  if (state.pageConversationId || !state.pageBinds) return false
  if (state.messageCount > 0 || state.streaming) return false
  return state.announcedId !== state.conversationId
}
