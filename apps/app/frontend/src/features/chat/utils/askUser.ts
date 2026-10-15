// The assistant's questions (askUser) as the page holds them, and the answer it sends back.
//
// Pure, so `node --test` reaches it: what a model writes as a question is not what the schema
// asked for often enough to matter, and which picks become the answer is the whole of what the
// model reads next.
//
// A QUESTION THAT TAKES SEVERAL PICKS ANSWERS WITH A LIST, since 2026-10-02. It was the labels
// joined by a comma - "Confluence, Files, local" for two picks, one of which holds a comma - so
// the model could not tell two choices from three.

import type { AskUserOption, AskUserQuestion, StepLimitQuestion } from '../types/chat.types'

/** The key "Other" is held under - no option label can be this, since labels are shown text. */
export const OTHER = '\u0000other'

// A model may write the flag as a string. Only "true" turns it on: `!!'false'` is true, which
// would hand every question checkboxes.
const isTrue = (v: unknown) => v === true || (typeof v === 'string' && v.trim().toLowerCase() === 'true')

function readOptions(raw: unknown): AskUserOption[] {
  if (!Array.isArray(raw)) return []
  const seen = new Set<string>()
  const options: AskUserOption[] = []
  for (const o of raw) {
    // A bare string is a label; some models write the list that way.
    const label = (typeof o === 'string' ? o : typeof o?.label === 'string' ? o.label : '').trim()
    // A label is the option's identity here - a repeat could never be picked on its own.
    if (!label || seen.has(label)) continue
    seen.add(label)
    const description = typeof o?.description === 'string' && o.description.trim() ? o.description.trim() : undefined
    options.push(description ? { label, description } : { label })
  }
  return options
}

/**
 * The questions of one askUser call, from its arguments JSON. Anything unreadable is left out, and
 * an unreadable payload is no questions - the caller then shows no prompt and the server times
 * the call out.
 */
export function parseQuestions(data: string | null | undefined): AskUserQuestion[] {
  let payload: unknown
  try {
    payload = JSON.parse(data || '{}')
  } catch {
    return []
  }
  const raw = (payload as { questions?: unknown } | null)?.questions
  if (!Array.isArray(raw)) return []
  const questions: AskUserQuestion[] = []
  for (const q of raw) {
    const question = typeof q?.question === 'string' ? q.question.trim() : ''
    if (!question || !Array.isArray(q.options)) continue
    questions.push({
      question,
      ...(typeof q.header === 'string' && q.header.trim() ? { header: q.header.trim() } : {}),
      options: readOptions(q.options),
      ...(isTrue(q.multiSelect) ? { multiSelect: true } : {}),
    })
  }
  return questions
}

/** The answer that lets a turn at its step limit go on - ChatService.CONTINUE_ANSWER, in every language. */
export const CONTINUE_ANSWER = 'Continue'

/**
 * Whether a question's payload is the turn asking to go on past its step limit, and with what
 * numbers. Null for the assistant's own questions, and for a payload whose numbers are not ones.
 */
export function stepLimitOf(data: string | null | undefined): StepLimitQuestion | null {
  let payload: unknown
  try {
    payload = JSON.parse(data || '{}')
  } catch {
    return null
  }
  const limit = (payload as { stepLimit?: unknown } | null)?.stepLimit
  const more = (payload as { moreSteps?: unknown } | null)?.moreSteps
  if (typeof limit !== 'number' || !Number.isInteger(limit) || limit <= 0) return null
  return { limit, more: typeof more === 'number' && Number.isInteger(more) && more > 0 ? more : limit }
}

/** What was picked, in the order the options are shown, with the typed answer last. */
export function picksOf(q: AskUserQuestion, picked: string[], other: string): string[] {
  const picks = q.options.map(o => o.label).filter(label => picked.includes(label))
  const typed = other.trim()
  if (picked.includes(OTHER) && typed) picks.push(typed)
  return picks
}

/**
 * The answer the model reads: the one pick for a question that takes one, a list for a question
 * that takes several - a list even when one was picked, so the shape says which kind it was.
 * Null while nothing is picked.
 */
export function answerOf(q: AskUserQuestion, picked: string[], other: string): string | string[] | null {
  const picks = picksOf(q, picked, other)
  if (picks.length === 0) return null
  return q.multiSelect ? picks : picks[0]
}

/** The picks after a click on `label`: one replaces the other, or several toggle. */
export function togglePick(q: AskUserQuestion, picked: string[], label: string): string[] {
  if (!q.multiSelect) return [label]
  return picked.includes(label) ? picked.filter(l => l !== label) : [...picked, label]
}

/**
 * The question to show after `from` when several are asked: the next one not yet answered, going
 * round past the last - so answering the third of four comes back for a skipped first. Null when
 * every other question has its answer (the card then offers Send).
 *
 * Several questions are TABS since 2026-10-07 (maintainer's feedback: "each question should have a
 * tab, rather than display all questions in a single page").
 */
export function nextUnanswered(answered: boolean[], from: number): number | null {
  const n = answered.length
  for (let step = 1; step < n; step++) {
    const i = (from + step) % n
    if (!answered[i]) return i
  }
  return null
}
