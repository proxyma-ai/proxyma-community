import { createContext, useContext } from 'react'

/**
 * Opens a deployed agent's transcript (AgentTranscriptDialog) by its conversation's id - what a
 * deployed agent's panel in the thread calls (AgentPanel). Provided by whatever shows a chat (the chat
 * page, a chat panel) and, one step deeper, by the transcript dialog itself, so an agent's own agents
 * open in the same dialog rather than one dialog on another. Null where nothing provides it: no way in
 * is offered then.
 */
export const AgentTranscriptContext = createContext<((conversationId: string) => void) | null>(null)

export function useOpenAgentTranscript(): ((conversationId: string) => void) | null {
  return useContext(AgentTranscriptContext)
}
