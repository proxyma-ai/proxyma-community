import type { ChatMessage, ToolCallInfo } from '../types/chat.types'

// Where things go in the thread while a turn runs. Plain functions over the message list, so the
// rules are tested without a DOM (`node --test`, threadOps.test.ts) - useChat applies them to the
// store as the events arrive.
//
// ONE INVARIANT HOLDS ALL OF IT TOGETHER: queued messages trail the thread. A message sent while a
// turn runs is shown at once, marked queued, and everything the turn goes on to say is placed
// BEFORE it. Until 2026-10-01 new text was appended to "the last message" instead, and once a
// queued bubble was the last message that meant a second copy of the answer started below it - the
// first copy left frozen mid-sentence above - and the steps and answers of the running turn were
// read as belonging to a question the agent had not seen yet.

/** The index of the first queued message, or the list's length when nothing is queued. */
export function settledLength(messages: ChatMessage[]): number {
  const at = messages.findIndex(m => m.queued)
  return at < 0 ? messages.length : at
}

/** Adds a message to the thread proper: after everything said so far, before whatever is queued. */
export function appendSettled(messages: ChatMessage[], message: ChatMessage): ChatMessage[] {
  const at = settledLength(messages)
  return [...messages.slice(0, at), message, ...messages.slice(at)]
}

/** The last message of the thread proper, with its index - undefined for an empty thread. */
export function lastSettled(messages: ChatMessage[]): { index: number; message: ChatMessage } | undefined {
  const index = settledLength(messages) - 1
  return index < 0 ? undefined : { index, message: messages[index] }
}

function replaceAt(messages: ChatMessage[], index: number, message: ChatMessage): ChatMessage[] {
  return messages.map((m, i) => (i === index ? message : m))
}

/**
 * Streamed answer text. `text` is everything this model call has said so far, not a delta.
 *
 * It extends the answer in progress, or - `opensMessage`, the first text since a tool call or a
 * mid-turn message - opens a new one, which takes `toolCalls`: the steps made since the last
 * message claimed its own, so they are drawn above the text they led to.
 */
export function applyText(
  messages: ChatMessage[], text: string, opensMessage: boolean, toolCalls: ToolCallInfo[],
): { messages: ChatMessage[]; opened: boolean } {
  const last = lastSettled(messages)
  if (!opensMessage && last?.message.role === 'assistant' && !last.message.error) {
    return { messages: replaceAt(messages, last.index, { ...last.message, content: text }), opened: false }
  }
  const message: ChatMessage = { role: 'assistant', content: text, toolCalls: toolCalls.length > 0 ? toolCalls : undefined }
  return { messages: appendSettled(messages, message), opened: true }
}

/**
 * Tool calls no message will claim - the turn took a mid-turn message, or ended, before saying
 * anything after them. They get a row of their own with no text, exactly as the stored transcript
 * gives them (ChatService.transcript), so nothing is lost when the next turn starts.
 */
export function claimToolCalls(messages: ChatMessage[], toolCalls: ToolCallInfo[], stepLimit?: number): ChatMessage[] {
  if (toolCalls.length === 0 && stepLimit === undefined) return messages
  return appendSettled(messages, {
    role: 'assistant', content: '', toolCalls: toolCalls.length > 0 ? toolCalls : undefined, stepLimit,
  })
}

/**
 * The running turn has taken a queued message: it joins the thread at this point, after what the
 * agent has said and done so far and before the messages still waiting. A message this page never
 * queued (the turn was resumed after a reload) is shown from the text the server sent.
 *
 * A message that is in the thread and NO LONGER QUEUED stays where it is: it was placed already,
 * by this same event replayed after a reconnect, or sent as a turn of its own. Moving it again put
 * it below steps the agent took after reading it (a feedback screenshot, 0.23.0).
 */
export function placeInjected(messages: ChatMessage[], clientId: string, text: string): ChatMessage[] {
  const index = messages.findIndex(m => m.clientId === clientId)
  if (index >= 0 && !messages[index].queued) return messages
  const bubble: ChatMessage = index >= 0
    ? { ...messages[index], queued: false, injectAccepted: false }
    : { role: 'user', content: text }
  const rest = index >= 0 ? messages.filter((_, i) => i !== index) : messages
  return appendSettled(rest, bubble)
}

/**
 * The turn stopped at its step limit. The notice goes under the answer the last step wrote, or -
 * when it wrote none, or asked for tools instead - on a row after whatever it did last.
 */
export function applyStepLimit(
  messages: ChatMessage[], limit: number, unclaimed: ToolCallInfo[], answerInProgress: boolean,
): ChatMessage[] {
  const last = lastSettled(messages)
  if (answerInProgress && unclaimed.length === 0 && last?.message.role === 'assistant' && !last.message.error) {
    return replaceAt(messages, last.index, { ...last.message, stepLimit: limit })
  }
  return claimToolCalls(messages, unclaimed, limit)
}
