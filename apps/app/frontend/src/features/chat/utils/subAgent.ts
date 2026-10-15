import type { ChatMessage, SubAgentInfo, SubAgentItem, SubAgentStatus, ToolCallInfo } from '../types/chat.types'

// What a sub-agent's events do to its panel. Plain functions over the tool-call lists, so the
// rules are tested without a DOM (`node --test`, subAgent.test.ts) - useChat applies them to the
// store as the events arrive.
//
// A sub-agent lives ON the `delegate` call that started it (ToolCallInfo.agent), wherever that
// call is held: the running turn's list, or the message that has claimed it - which for one
// working in the background may be several turns up the thread. So every change here is "find the
// call carrying this agent, anywhere, and replace its agent".
//
// EVERY CHANGE IS SAFE TO APPLY TWICE. A page that loads a conversation is given what each
// sub-agent had done as of a position in the event stream and then follows the stream from there,
// and a reconnect replays; a tool start for a call already listed and still running is that same
// call, and text arrives as the whole paragraph, never a delta.

export const DELEGATE = 'delegate'

/** The call carrying this sub-agent in one list, or undefined. */
export function agentCall(toolCalls: ToolCallInfo[] | undefined, agentId: string): ToolCallInfo | undefined {
  return toolCalls?.find(tc => tc.agent?.id === agentId)
}

/**
 * The `delegate` call carrying this sub-agent, wherever it is held: the running turn's list first,
 * where a call waits until text claims it into a message, then the messages. The agents map looked
 * in the messages alone, so a sub-agent started in the running turn said its steps were not loaded
 * (0.31.0 feedback).
 */
export function delegationCall(messages: ChatMessage[], toolCalls: ToolCallInfo[], agentId: string): ToolCallInfo | undefined {
  const live = agentCall(toolCalls, agentId)
  if (live) return live
  for (const m of messages) {
    const held = agentCall(m.toolCalls, agentId)
    if (held) return held
  }
  return undefined
}

/** The sub-agent with this id, wherever its call is held. */
export function findAgent(messages: ChatMessage[], toolCalls: ToolCallInfo[], agentId: string): SubAgentInfo | undefined {
  return delegationCall(messages, toolCalls, agentId)?.agent
}

/**
 * Every sub-agent still working, in the order the thread shows them: the messages from the top,
 * then the running turn's calls. The composer draws one robot per agent from this, each a way to
 * its panel (maintainer's request, 2026-10-10), so it is read from the same store the thread draws
 * its panels from - a robot always has a panel to go to. One id is listed once, wherever it is held.
 */
export function runningAgents(messages: ChatMessage[], toolCalls: ToolCallInfo[]): SubAgentInfo[] {
  const seen = new Set<string>()
  const found: SubAgentInfo[] = []
  const take = (list?: ToolCallInfo[]) => list?.forEach(tc => {
    const agent = tc.agent
    if (agent?.status !== 'running' || seen.has(agent.id)) return
    seen.add(agent.id)
    found.push(agent)
  })
  messages.forEach(m => take(m.toolCalls))
  take(toolCalls)
  return found
}

/** Whether any sub-agent, anywhere in the thread, is still working. */
export function anyAgentRunning(messages: ChatMessage[], toolCalls: ToolCallInfo[]): boolean {
  const running = (list?: ToolCallInfo[]) => !!list?.some(tc => tc.agent?.status === 'running')
  return running(toolCalls) || messages.some(m => running(m.toolCalls))
}

/** `toolCalls` with one sub-agent changed; the same array when it is not in this list. */
export function withAgent(
  toolCalls: ToolCallInfo[], agentId: string, change: (agent: SubAgentInfo) => SubAgentInfo,
): ToolCallInfo[] {
  if (!toolCalls.some(tc => tc.agent?.id === agentId)) return toolCalls
  return toolCalls.map(tc => (tc.agent?.id === agentId ? { ...tc, agent: change(tc.agent) } : tc))
}

/** The same over a message list - only the message holding the call is rebuilt. */
export function withAgentInMessages(
  messages: ChatMessage[], agentId: string, change: (agent: SubAgentInfo) => SubAgentInfo,
): ChatMessage[] {
  if (!messages.some(m => m.toolCalls?.some(tc => tc.agent?.id === agentId))) return messages
  return messages.map(m => {
    if (!m.toolCalls) return m
    const next = withAgent(m.toolCalls, agentId, change)
    return next === m.toolCalls ? m : { ...m, toolCalls: next }
  })
}

/**
 * 'sub-agent-start': the `delegate` call with this id has become a sub-agent. The LATEST such call
 * that is not one already - a call id is not unique for long (some servers number their calls and
 * start again each round), and it is always the newest that has just started.
 */
export function startAgent(toolCalls: ToolCallInfo[], callId: string, agent: SubAgentInfo): ToolCallInfo[] {
  if (toolCalls.some(tc => tc.agent?.id === agent.id)) return toolCalls
  for (let i = toolCalls.length - 1; i >= 0; i--) {
    const tc = toolCalls[i]
    if (tc.callId === callId && tc.toolName === DELEGATE && !tc.agent) {
      return toolCalls.map((other, j) => (j === i ? { ...other, agent } : other))
    }
  }
  return toolCalls
}

/** A tool call of the sub-agent's has started. */
export function agentToolStarted(agent: SubAgentInfo, item: SubAgentItem): SubAgentInfo {
  // Already listed and still running: this is that call, heard a second time.
  if (agent.items.some(i => i.kind === 'tool' && i.callId === item.callId && !i.done)) return agent
  return { ...agent, items: [...agent.items, item], awaitingModelSince: undefined, thinkingTokens: undefined }
}

/** A tool call of the sub-agent's has returned - the last one with that id still running. */
export function agentToolFinished(agent: SubAgentInfo, callId: string, result: string, endedAt?: number): SubAgentInfo {
  let at = -1
  agent.items.forEach((i, index) => { if (i.kind === 'tool' && i.callId === callId && !i.done) at = index })
  if (at < 0) return agent
  return {
    ...agent,
    items: agent.items.map((i, index) => (index === at ? { ...i, result, done: true, endedAt } : i)),
  }
}

/** What the sub-agent is writing: the whole of paragraph `segment` so far. */
export function agentText(agent: SubAgentInfo, segment: number, text: string): SubAgentInfo {
  const at = agent.items.findIndex(i => i.kind === 'text' && i.segment === segment)
  const item: SubAgentItem = { kind: 'text', done: true, segment, text }
  const items = at < 0 ? [...agent.items, item] : agent.items.map((i, index) => (index === at ? item : i))
  return { ...agent, items, awaitingModelSince: undefined, thinkingTokens: undefined }
}

/**
 * The sub-agent has ended. What it was writing when it ended IS the report, so that last
 * paragraph gives way to it; a call that never returned is closed as what it was.
 */
export function agentDone(agent: SubAgentInfo, status: SubAgentStatus, report: string, endedAt?: number): SubAgentInfo {
  const last = agent.items[agent.items.length - 1]
  const items = (last?.kind === 'text' && status !== 'stopped' ? agent.items.slice(0, -1) : agent.items)
    .map(i => (i.kind === 'tool' && !i.done ? { ...i, done: true, endedAt, result: i.result ?? STOPPED_RESULT } : i))
  return { ...agent, status, report, endedAt, items, awaitingModelSince: undefined, thinkingTokens: undefined }
}

const STOPPED_RESULT = JSON.stringify({ success: false, error: 'Stopped before it returned' })

/** A sub-agent's step as the row that draws every tool call takes it. */
export function itemAsCall(item: SubAgentItem, index: number): ToolCallInfo {
  return {
    callId: item.callId ?? `step-${index}`,
    toolName: item.toolName ?? '',
    arguments: item.arguments ?? '{}',
    result: item.result,
    done: item.done,
    startedAt: item.startedAt,
    endedAt: item.endedAt,
  }
}

/** The sub-agent's own clock, as a call the elapsed-time readout can take. */
export function agentAsTimedCall(call: ToolCallInfo): ToolCallInfo {
  const agent = call.agent
  if (!agent) return call
  return {
    ...call,
    done: agent.status !== 'running',
    startedAt: agent.startedAt ?? call.startedAt,
    endedAt: agent.endedAt,
  }
}
