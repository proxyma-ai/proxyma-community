/**
 * What the row under a running turn says - the maintainer's rule of 2026-09-29, as one function so
 * it can be tested without a DOM (`node --test`, see .gitlab/ci/test.yml).
 *
 * | Turn is...                                        | Row                                   |
 * |---------------------------------------------------|---------------------------------------|
 * | not running                                       | nothing                               |
 * | condensing the conversation                       | nothing (the condense row says it)    |
 * | waiting on the user (approval, enable a tool)     | nothing (the prompt is the row)       |
 * | model is thinking                                 | brain, "<model> is thinking", tokens  |
 * | a model call is out, provider has not answered    | dish, "Waiting for <model>", seconds  |
 * |   ... and the provider failed and is retried      |   ... plus "retry N"                  |
 * | a tool call is running                            | nothing (the tool row has its spinner)|
 * | text arrived less than QUIET_MS ago (responding)  | nothing (the text is the proof)       |
 * | none of the above for QUIET_MS                    | the star names                        |
 *
 * Order matters where two hold at once: a prompt waiting on the user outranks everything, because
 * nothing moves until they answer; thinking and the provider wait outrank a tool still settling.
 */

export type WaitFace = 'model' | 'thinking' | 'idle'

/** How long a turn may be silent before it counts as waiting on nothing the row can name. */
export const QUIET_MS = 2000

export interface WaitSignals {
  streaming: boolean
  condensing: boolean
  awaitingUser: boolean
  thinking: boolean
  awaitingModel: boolean
  toolRunning: boolean
  /** Milliseconds since the stream last said anything. */
  quietMs: number
}

export function waitFace(s: WaitSignals): WaitFace | null {
  if (!s.streaming || s.condensing || s.awaitingUser) return null
  if (s.thinking) return 'thinking'
  if (s.awaitingModel) return 'model'
  if (s.toolRunning) return null
  return s.quietMs >= QUIET_MS ? 'idle' : null
}
