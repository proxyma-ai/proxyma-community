// What a model call's cost and cache figures MEAN, and the rate form behind them - for the call
// log (LlmCallLog) and a model's rate dialog (ProvidersTab).
//
// The server prices a call in one order: the cost the provider's own response states (OpenRouter,
// LiteLLM) is used as it is; otherwise the rate entered in Settings > AI Providers; otherwise the
// built-in list price of a known model; otherwise $0. Each log row says which of those it was
// (`costSource`), and a row written before that existed says nothing - which is decided here, as is
// what a call's cache figures say, and how the rate form turns into the request and back.
//
// Pure and import-free, so `node --test` reaches it (callCost.test.ts).

/** Where a call's cost came from, as the server records it. Null on a row older than the field. */
export type CostSource = 'REPORTED' | 'RATE' | 'LIST' | 'NONE'

/**
 * What the log says beside a cost: `reported` (the provider stated it), `list` (the built-in list
 * price, no rate entered), `rate` (the rate entered - the ordinary case, nothing extra shown) or
 * `unpriced` (nothing priced it, so its $0 is a missing price rather than a free call).
 */
export type CostOrigin = 'reported' | 'list' | 'rate' | 'unpriced'

export function costOrigin(row: { costSource?: CostSource | null; priced: boolean }): CostOrigin {
  switch (row.costSource) {
    case 'REPORTED': return 'reported'
    case 'LIST': return 'list'
    case 'RATE': return 'rate'
    case 'NONE': return 'unpriced'
    // A row from before the field: `priced` is all it has, and then it meant an entered rate.
    default: return row.priced ? 'rate' : 'unpriced'
  }
}

/**
 * How long the cache a call WROTE lives: five minutes, an hour, some of each, or `unknown` for a row
 * written before the one-hour part was recorded. `none` when nothing was written.
 */
export type CacheWriteLifetime = 'none' | 'short' | 'long' | 'mixed' | 'unknown'

export interface CacheOutcome {
  /** Something was read back from the cache. */
  hit: boolean
  write: CacheWriteLifetime
}

export function cacheOutcome(row: {
  cacheReadTokens: number
  cacheWriteTokens: number
  longCacheWriteTokens?: number | null
}): CacheOutcome {
  const hit = row.cacheReadTokens > 0
  const written = row.cacheWriteTokens
  if (!(written > 0)) return { hit, write: 'none' }
  const long = row.longCacheWriteTokens
  if (long == null) return { hit, write: 'unknown' }
  if (long <= 0) return { hit, write: 'short' }
  if (long >= written) return { hit, write: 'long' }
  return { hit, write: 'mixed' }
}

/**
 * What the server's prompt-cache clock (PromptCacheClock) made of a call: it read the cache, wrote
 * the first one, or wrote again because the cache had expired, because its prompt no longer started
 * with the one before (the tools, the system prompt or the history changed), or because the provider
 * did not keep what it wrote. Null for a call with no cache activity, or one recorded before.
 */
export type CacheVerdict =
  | 'HIT' | 'FIRST' | 'EXPIRED' | 'CHANGED_TOOLS' | 'CHANGED_SYSTEM' | 'CHANGED_MESSAGES' | 'NOT_KEPT'

/** Whether a verdict is a miss somebody could act on - everything but a hit and a first write. */
export function isAvoidableMiss(verdict: CacheVerdict | null | undefined): boolean {
  return verdict === 'CHANGED_TOOLS' || verdict === 'CHANGED_SYSTEM' || verdict === 'CHANGED_MESSAGES'
    || verdict === 'NOT_KEPT'
}

/** A verdict the page knows, or null - a value from a newer server is shown as nothing. */
export function cacheVerdict(value: string | null | undefined): CacheVerdict | null {
  switch (value) {
    case 'HIT': case 'FIRST': case 'EXPIRED': case 'CHANGED_TOOLS': case 'CHANGED_SYSTEM':
    case 'CHANGED_MESSAGES': case 'NOT_KEPT':
      return value
    default:
      return null
  }
}

/** A call that neither read nor wrote the cache. */
export function noCache(outcome: CacheOutcome): boolean {
  return !outcome.hit && outcome.write === 'none'
}

// ---- the rate form -------------------------------------------------------------------------

/** DeepSeek's time-of-day pricing: the rates entered are the peak price, and off-peak is half. */
export type PricingSchedule = 'DEEPSEEK_OFF_PEAK'

/** A model's rates as the pricing endpoints take and return them, every price per million tokens. */
export interface RateFields {
  inputPricePerMillion: number
  outputPricePerMillion: number
  cacheReadPricePerMillion: number
  /** The five-minute cache write. */
  cacheWritePricePerMillion: number
  /** The one-hour cache write; null is 1.6x the five-minute rate. */
  cacheWriteLongPricePerMillion: number | null
  /** Prompt size above which the long-context rates price the whole call; null is no long tier. */
  longContextThresholdTokens: number | null
  /** The long-context rates; each null is the standard rate. */
  longInputPricePerMillion: number | null
  longOutputPricePerMillion: number | null
  longCacheReadPricePerMillion: number | null
  longCacheWritePricePerMillion: number | null
  pricingSchedule: PricingSchedule | null
}

/** The rate dialog's fields as typed - every number a string, blank where nothing is given. */
export interface RateForm {
  input: string
  output: string
  cacheRead: string
  cacheWrite: string
  cacheWriteLong: string
  threshold: string
  longInput: string
  longOutput: string
  longCacheRead: string
  longCacheWrite: string
  schedule: '' | PricingSchedule
}

export const EMPTY_RATE_FORM: RateForm = {
  input: '', output: '', cacheRead: '', cacheWrite: '', cacheWriteLong: '',
  threshold: '', longInput: '', longOutput: '', longCacheRead: '', longCacheWrite: '',
  schedule: '',
}

function text(value: number | null | undefined): string {
  return value == null ? '' : String(value)
}

/** A stored row (or a list price) as the form shows it. Nothing given is blank. */
export function rateFormOf(rates: Partial<RateFields> | null | undefined): RateForm {
  if (!rates) return EMPTY_RATE_FORM
  const threshold = rates.longContextThresholdTokens
  const tiered = threshold != null && threshold > 0
  return {
    input: text(rates.inputPricePerMillion),
    output: text(rates.outputPricePerMillion),
    cacheRead: text(rates.cacheReadPricePerMillion),
    cacheWrite: text(rates.cacheWritePricePerMillion),
    cacheWriteLong: text(rates.cacheWriteLongPricePerMillion),
    threshold: tiered ? String(threshold) : '',
    longInput: tiered ? text(rates.longInputPricePerMillion) : '',
    longOutput: tiered ? text(rates.longOutputPricePerMillion) : '',
    longCacheRead: tiered ? text(rates.longCacheReadPricePerMillion) : '',
    longCacheWrite: tiered ? text(rates.longCacheWritePricePerMillion) : '',
    schedule: rates.pricingSchedule ?? '',
  }
}

/** A required price: blank or not a number is 0, as the form has always sent it. */
function required(value: string): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/** An optional price: blank (or not a number) is "not given". */
function optional(value: string): number | null {
  if (value.trim() === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

/**
 * The form as the request sends it - EVERY field, since an edit replaces all of a row's rates. A
 * threshold of 0 or blank is no long tier, and the long rates go only with a threshold.
 */
export function ratePayload(form: RateForm): RateFields {
  const raw = optional(form.threshold)
  const threshold = raw != null && raw > 0 ? Math.round(raw) : null
  const long = (value: string) => (threshold == null ? null : optional(value))
  return {
    inputPricePerMillion: required(form.input),
    outputPricePerMillion: required(form.output),
    cacheReadPricePerMillion: required(form.cacheRead),
    cacheWritePricePerMillion: required(form.cacheWrite),
    cacheWriteLongPricePerMillion: optional(form.cacheWriteLong),
    longContextThresholdTokens: threshold,
    longInputPricePerMillion: long(form.longInput),
    longOutputPricePerMillion: long(form.longOutput),
    longCacheReadPricePerMillion: long(form.longCacheRead),
    longCacheWritePricePerMillion: long(form.longCacheWrite),
    pricingSchedule: form.schedule === '' ? null : form.schedule,
  }
}

/** Whether a rate form has a long-context tier worth showing open. */
export function hasLongTier(form: RateForm): boolean {
  const n = Number(form.threshold)
  return form.threshold.trim() !== '' && Number.isFinite(n) && n > 0
}
