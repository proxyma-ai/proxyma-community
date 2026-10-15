import { AxiosError } from 'axios'
import { apiErrorText } from '@/services/apiError'
import type { Translate } from '@/i18n'

/**
 * WHY A SIGN-IN, A SIGN-UP, A SETUP OR A RESET WAS REFUSED - one reader for the five forms that
 * each parsed the response by hand (two of them identically, fourteen lines apiece).
 *
 * The server's own sentence first, in any of the shapes it answers in (`message`, a ProblemDetail's
 * `detail`, `error`) and in the reader's language where the catalog has it - the HTTP client
 * translates a refusal as it arrives, so the old note that "the backend speaks English" no longer
 * held. Then, where a proxy in front of the server answered with an HTML page instead, that page's
 * `<title>`; then the status; then the form's own fallback.
 */
export function authErrorText(e: unknown, t: Translate, fallback: string): string {
  const reason = apiErrorText(e)
  if (reason) return reason
  if (!(e instanceof AxiosError) || !e.response) return fallback
  const { status, statusText, data } = e.response
  if (typeof data === 'string' && data.trim().startsWith('<')) {
    const title = data.match(/<title>(.*?)<\/title>/i)?.[1]?.trim()
    return title || (status ? t('Server error ({status})', { status }) : t('Server error (unknown status)'))
  }
  // `statusText` is the HTTP reason phrase off the wire - a value, not a translatable word.
  if (status >= 500) return t('Server unavailable ({status} {statusText})', { status, statusText: statusText || '' })
  return fallback
}
