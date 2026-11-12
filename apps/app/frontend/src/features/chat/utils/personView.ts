import type { ChatMessage } from '../types/chat.types'

/**
 * A THREAD OF WHAT THE PERSON AND THE AGENT SAID AND DID, WITHOUT ITS TEAMMATES' MAIL (the maintainer,
 * 2026-11-03: "the right chat should contain only the messages between the leader and the user, never
 * the leader's internal messages + its teammates") - a Constellations project's lead chat. The model
 * still reads everything; this is only what the thread draws:
 *
 * - the person's own messages;
 * - the agent's answers, with the tool calls it made - since 2026-11-07 (the maintainer: "The right chat
 *   pannel does not render tool calls which ambigous to the user"), a turn showed "thinking" while the
 *   lead looked up, answered and withdrew questions, and nothing said what it had done;
 * - what the agent told the person from another of its conversations (mail of the kind `told`);
 * - a failed turn's reason and a step-limit notice, which are the person's to act on.
 *
 * Gone: every other mail - a teammate's message or reply, a deployed agent's report, an alarm, the
 * workspace memory the agent was given. The server marks each of those as mail (AgentInbox's kinds on
 * the transcript's `agentMail`), so the rule is "draw the person and the agent's own words and acts",
 * never a reading of the text.
 *
 * Plain .ts so `node --test` reads it.
 */
export function personView(messages: ChatMessage[]): ChatMessage[] {
  const out: ChatMessage[] = []
  for (const m of messages) {
    if (m.role === 'user') {
      out.push(m)
      continue
    }
    const told = m.agentMail?.filter(e => e.kind === 'told' && !!e.text)
    const hasWords = !!m.content || !!m.error || !!m.guardrail || m.stepLimit !== undefined
    const acted = (m.toolCalls?.length ?? 0) > 0
    if (!hasWords && !acted && !(told && told.length > 0)) continue
    const kept: ChatMessage = { ...m }
    delete kept.agentMail
    if (told && told.length > 0) kept.agentMail = told
    out.push(kept)
  }
  return out
}
