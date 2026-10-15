import { INTERRUPTED } from './settleUnfinishedToolCalls'

// A failed tool call's error, as its row shows it - the `error` field of its JSON result, or
// "Failed". It lived in ToolCallRow.tsx, a component file (CHAT-25).
//
// `t` is optional: `obj.error` is the backend's own English and is returned untouched, so the
// only translatable value here takes no parameters and `(s) => s` is a correct fallback.
export function extractErrorSummary(result: string, t?: (s: string) => string): string {
  try {
    const obj = JSON.parse(result)
    if (obj && typeof obj.error === 'string' && obj.error.trim()) {
      const error = obj.error.trim()
      return error === INTERRUPTED && t ? t(INTERRUPTED) : error
    }
  } catch {
    // fall through
  }
  return t ? t('Failed') : 'Failed'
}
