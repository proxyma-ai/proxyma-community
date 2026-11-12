import type { ChatMessage, ToolCallInfo } from '../types/chat.types'
import type { Translate } from '@/i18n'
import { guardrailReasons } from '../../audit/utils/auditLabels.ts'

// WHAT THE GUARDRAILS HELD BACK IN A TURN, as the notes under its answer say it.
//
// The maintainer's expectation (2026-11-08): "if something's blocked or redacted by guardrails, then
// we will see a shield icon with the guardrail message below the assistant response text." A
// redacted answer had its note (RedactionNote) and a withheld one its card (GuardrailNotice); a
// SOURCE withheld by the retrieval guardrail had nothing under the answer - only that tool's row,
// in red, as if the call had failed.
//
// No event says so, deliberately (ChatService.guardRetrieved: a mid-stream guardrail event would
// replace the answer bubble). The turn's own tool results do: GuardrailService.withheldToolResult
// hands the model `{"success":false,"guardrailBlocked":true,"categories":[...],"error":"..."}`,
// and that is what the transcript stores - so the note is read from the results, live and after a
// reload alike. A result stored before the `categories` list existed names them only in its
// sentence, "withheld by the configured guardrails (prompt-injection)", which is parsed instead.
//
// Plain .ts, so `node --test` reaches it - guardrailNotes.test.ts.

/** The sources a turn's guardrails held back: how many results, and every category, once each. */
export interface WithheldSources {
  count: number
  categories: string[]
}

// The parenthesis of the server's sentence, for a result stored before the list.
const SENTENCE_CATEGORIES = /withheld by the configured guardrails \(([^)]*)\)/

/**
 * The categories of a result the retrieval guardrail withheld - an empty list when it named none -
 * or null when the result is anything else: a real result, another failure, not JSON at all.
 */
export function withheldCategories(result: string | undefined): string[] | null {
  if (!result) return null
  let parsed: unknown
  try {
    parsed = JSON.parse(result)
  } catch {
    return null
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
  const obj = parsed as Record<string, unknown>
  if (obj.guardrailBlocked !== true) return null
  if (Array.isArray(obj.categories)) {
    return unique(obj.categories.filter((c): c is string => typeof c === 'string'))
  }
  const match = typeof obj.error === 'string' ? SENTENCE_CATEGORIES.exec(obj.error) : null
  return match ? unique(match[1].split(',')) : []
}

/** Whether this call's result is one the retrieval guardrail withheld. */
export function isWithheldCall(tc: Pick<ToolCallInfo, 'done' | 'result'>): boolean {
  return tc.done && withheldCategories(tc.result) !== null
}

/** What a set of tool calls had withheld, merged; null when none was. */
export function withheldSources(calls: readonly Pick<ToolCallInfo, 'done' | 'result'>[]): WithheldSources | null {
  let count = 0
  const categories: string[] = []
  for (const tc of calls) {
    if (!tc.done) continue
    const found = withheldCategories(tc.result)
    if (found === null) continue
    count++
    categories.push(...found)
  }
  return count === 0 ? null : { count, categories: unique(categories) }
}

// A message that draws a bubble - ChatWindow's `stepsOnly` turned round: a row of tool calls and
// nothing else has no answer for a note to stand under.
const drawsBubble = (m: ChatMessage): boolean =>
  m.role === 'assistant' && (!!m.content || !!m.error || !!m.guardrail || m.stepLimit !== undefined)

/**
 * Which answer carries a turn's withheld-source note: message index to what was held back.
 *
 * A turn is what follows a person's message up to the next one (ChatWindow's sections), and its
 * calls are spread over every assistant message in it - each claims the calls made just before
 * its text (threadOps). The note goes ONCE, on the turn's last message that draws a bubble, and
 * only once the turn has ended: the last turn while `streaming` gets none yet, so no intermediate
 * line of narration ever carries it. `settled` is where the queued messages start.
 */
export function withheldByAnswer(
  messages: readonly ChatMessage[], settled: number, streaming: boolean,
): Map<number, WithheldSources> {
  const notes = new Map<number, WithheldSources>()
  const flush = (from: number, to: number) => {
    const turn = messages.slice(from, to)
    const held = withheldSources(turn.flatMap(m => (m.role === 'assistant' && m.toolCalls) || []))
    if (!held) return
    for (let i = to - 1; i >= from; i--) {
      if (drawsBubble(messages[i])) {
        notes.set(i, held)
        return
      }
    }
  }
  let start = 0
  for (let i = 0; i < settled; i++) {
    if (messages[i].role !== 'user') continue
    flush(start, i)
    start = i + 1
  }
  if (!streaming) flush(start, settled)
  return notes
}

/** Whether two notes say the same - so a bubble re-renders when, and only when, its note changes. */
export function sameWithheld(a: WithheldSources | undefined, b: WithheldSources | undefined): boolean {
  if (a === b) return true
  if (!a || !b) return false
  return a.count === b.count && a.categories.join('\n') === b.categories.join('\n')
}

/** The two notes a guardrail leaves under an answer, in the order they are drawn. */
export const GUARDRAIL_NOTE_ORDER = ['redacted', 'withheld'] as const
export type GuardrailNoteKind = typeof GUARDRAIL_NOTE_ORDER[number]

/**
 * The notes under an answer, in GUARDRAIL_NOTE_ORDER: the REDACTION first, since it is about the
 * very text above it, then the source held back on the way to that text. Two notes rather than one
 * merged sentence - they are two different events with two different reasons, and "Redacted and
 * held back by guardrails: secret, prompt-injection" no longer says which reason was which.
 *
 * The reasons are the categories in words, as the Logs page names them (auditLabels'
 * guardrailReasons) - never the server's codes. `redacted` is the answer's own categories, or
 * undefined when it was not redacted.
 */
export function guardrailNotes(
  redacted: readonly string[] | undefined, withheld: WithheldSources | undefined, t: Translate,
): { kind: GuardrailNoteKind; title: string }[] {
  const notes: { kind: GuardrailNoteKind; title: string }[] = []
  if (redacted) {
    notes.push({ kind: 'redacted', title: redacted.length > 0
      ? t('Redacted by a guardrail: {reasons}', { reasons: guardrailReasons(redacted, t) })
      : t('Redacted by a guardrail') })
  }
  if (withheld) {
    const reasons = guardrailReasons(withheld.categories, t)
    const title = withheld.count === 1
      ? (reasons ? t('A source was held back by a guardrail: {reasons}', { reasons }) : t('A source was held back by a guardrail'))
      : (reasons ? t('Some sources were held back by guardrails: {reasons}', { reasons }) : t('Some sources were held back by guardrails'))
    notes.push({ kind: 'withheld', title })
  }
  return notes
}

function unique(values: readonly string[]): string[] {
  const seen: string[] = []
  for (const raw of values) {
    const v = raw.trim()
    if (v && !seen.includes(v)) seen.push(v)
  }
  return seen
}
