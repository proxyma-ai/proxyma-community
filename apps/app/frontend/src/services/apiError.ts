import { AxiosError } from 'axios'
import { serverText } from '@/i18n/serverText'

/**
 * The server's own reason for a failed request, or null when it gave none.
 *
 * <p>ONE READER FOR EVERY SHAPE THE BACKEND ANSWERS IN, since 2026-09-29. GlobalExceptionHandler
 * returns an IllegalArgumentException or IllegalStateException as a ProblemDetail, whose sentence
 * is in `detail`; controllers that build their own body use `error` or `message`. Most call sites
 * read only `error`, so the refusal's reason was dropped and the reader got "Failed to save." - the
 * problem, not the reason. Some fell back to axios's own `message`, which is "Request failed with
 * status code 400": still not a reason.
 *
 * <p>A machine code (`tier_limit`, `already_registered`) is skipped - it is for the caller to
 * branch on, not a sentence for a person. An HTML body (a proxy's error page) is skipped too.
 *
 * <p>The sentence is already in the reader's language where the catalog has it: the HTTP client
 * translates a refusal's body as it arrives (httpClient.ts). A body that is a bare string is not
 * an object it could rewrite, so that one shape is translated here.
 */
export function apiErrorText(e: unknown): string | null {
  if (!(e instanceof AxiosError)) return null
  const data = e.response?.data as unknown
  if (typeof data === 'string') {
    const trimmed = data.trim()
    return trimmed && !trimmed.startsWith('<') ? serverText(trimmed) : null
  }
  if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>
    for (const key of ['message', 'detail', 'error']) {
      const value = obj[key]
      if (typeof value === 'string' && value.trim() && !/^[a-z_]+$/.test(value.trim())) return value.trim()
    }
  }
  return null
}

/** `what`, followed by the server's reason when it gave one: "Could not save: <reason>". */
export function withReason(what: string, e: unknown): string {
  const reason = apiErrorText(e)
  return reason ? `${what}: ${reason}` : what
}

/** The server answered 404: the thing asked about is no longer there to answer. */
export function isGone(e: unknown): boolean {
  return e instanceof AxiosError && e.response?.status === 404
}
