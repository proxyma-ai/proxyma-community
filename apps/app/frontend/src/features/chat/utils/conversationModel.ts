/**
 * WHICH MODEL A CHAT NAMES - in "Waiting for ...", "... is thinking" and beside the composer.
 *
 * The chat settings' provider and model, unless the open conversation runs on one of its own: a saved
 * agent's (a Constellations lead chat, an agent's Chat tab), which the server states with the
 * conversation's usage (`model`, and `providerName` when it can name the provider). A lead chat named
 * the chat's "claude-sonnet-5-5" while every turn ran on the agent's "claude-haiku-5-5" (the
 * maintainer, 2026-11-03).
 *
 * Plain .ts so `node --test` reads it.
 */

export interface AgentModel {
  /** The provider instance's name, when it can be resolved. */
  provider?: string
  model: string
  /**
   * The conversation's own model, not the chat settings': nothing to open in Settings > Agents for
   * it, since that is not where it is chosen.
   */
  own?: boolean
}

/** What the conversation's usage says of the model it runs on (ConversationUsage). */
export interface UsageModel {
  model?: string | null
  providerName?: string | null
}

/**
 * The model to name: the conversation's own when its usage states one, else the chat's (null where
 * the reader may not see the chat settings - the unnamed wording then).
 */
export function conversationModel(chat: AgentModel | null, usage: UsageModel | null | undefined): AgentModel | null {
  const model = usage?.model?.trim()
  if (!model) return chat
  const provider = usage?.providerName?.trim()
  return provider ? { provider, model, own: true } : { model, own: true }
}

/** "Provider / model", or the model alone when the provider cannot be named. */
export function modelWords(agent: AgentModel, separator = ' / '): string {
  return agent.provider ? `${agent.provider}${separator}${agent.model}` : agent.model
}
