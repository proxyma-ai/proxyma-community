import { http } from '@/services/httpClient'

export interface LatencyStats {
  samples: number
  p50: number
  p95: number
  max: number
}

/**
 * One matched span, and the only thing here that ever quotes a message.
 *
 * ALWAYS EMPTY unless the deployment has turned `guardrail.captureMatchedText` on, which is off by
 * default. See GuardrailEvidence on the backend, which carries the whole argument for why this
 * exists and what bounds it. The UI treats an empty list as the normal case, not as an error.
 */
export interface GuardrailEvidence {
  rule: string
  category: string
  /** Truncated by the backend to 120 characters. */
  match: string
  truncated: boolean
}

export interface RecentDecision {
  at: string
  // RETRIEVAL was missing here until 2026-09-09 while the backend had emitted it all along - the
  // stage that checks a document on its way into the agent loop. A union that does not list a
  // value the server really sends is a switch branch nobody writes and a filter nobody applies.
  stage: 'INPUT' | 'OUTPUT' | 'RETRIEVAL'
  verdict: 'allow' | 'redact' | 'block'
  // false when the deployment is in monitor mode and this was observed rather than applied.
  enforced: boolean
  categories: string[]
  /** Rule ids, e.g. `secret.aws-access-key`. Never empty for a decision the rules made. */
  rules: string[]
  evidence: GuardrailEvidence[]
  millis: number
}

export interface GuardrailMetrics {
  since: string
  checks: number
  allowed: number
  redacted: number
  blocked: number
  // Decisions that would have blocked or redacted but did not, because mode is MONITOR.
  observedOnly: number
  // Classifier calls that failed and were allowed through - the fail-open count, made visible.
  classifierFailures: number
  byRule: Record<string, number>
  byCategory: Record<string, number>
  latencyMs: Partial<Record<'rules' | 'examples' | 'retrieval' | 'classifier' | 'total', LatencyStats>>
  recent: RecentDecision[]
}

// In memory on the server and reset with the process - see GuardrailMetrics on the backend for
// why, and note that the UI says so rather than letting somebody assume otherwise.
export const guardrailMetricsApi = {
  get: () => http.get<GuardrailMetrics>('/api/v1/guardrail/metrics').then(r => r.data),
  reset: () => http.delete<void>('/api/v1/guardrail/metrics').then(r => r.data),
}
