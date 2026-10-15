// Server-local file paths (e.g. the offloaded-result envelope's "filePath", the follow-up
// read/grep call's own "filePath" argument, or a tenant tool's
// "currentMonthPdfPath"/"previousMonthPdfPath") are an implementation detail - never surface an
// absolute filesystem path in the chat UI. Matched by key suffix rather than an exact-name list
// so a future tool's own "...Path"-named argument is covered without a code change here. The key
// is dropped entirely (not replaced with a placeholder like "(hidden)") - a redacted path adds no
// information for the user, so omitting it reads cleaner than a stub value.
//
// Shared by ToolCallList (the per-row argument summary) and ToolCallDetailModal (the full
// input/output JSON view) - previously each kept its own copy, and only one got updated when the
// key-matching rule was widened, leaving the other still leaking full paths in its summary line.
export function redactFilePaths<T>(value: T): T {
  if (Array.isArray(value)) return value.map(redactFilePaths) as unknown as T
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (k.toLowerCase().endsWith('path')) continue
      out[k] = redactFilePaths(v)
    }
    return out as T
  }
  return value
}
