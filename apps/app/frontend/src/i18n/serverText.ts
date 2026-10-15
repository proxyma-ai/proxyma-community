import { catalogs } from './index.ts'
import { DEFAULT_LOCALE, isLocale } from './kit/locale.ts'
import { translateServerText } from './serverMessage.ts'

/**
 * A sentence the server wrote, in the language the page is showing - see serverMessage.ts for
 * what that covers and why it is done here rather than on the server.
 *
 * NOT A HOOK, deliberately. The two places most server text arrives are not components: the HTTP
 * client's error path and the chat stream's handlers. The language is read off `<html lang>`,
 * which I18nProvider keeps set to the one in force, so this agrees with what `t` would use. A
 * component that shows a stored sentence (a failed sync's reason) calls it while rendering and is
 * re-rendered with everything else when the language changes.
 */
export function serverText(text: string): string
export function serverText(text: string | null | undefined): string | null | undefined
export function serverText(text: string | null | undefined): string | null | undefined {
  if (!text) return text
  const lang = typeof document === 'undefined' ? DEFAULT_LOCALE : document.documentElement.lang
  const locale = isLocale(lang) ? lang : DEFAULT_LOCALE
  return translateServerText(text, catalogs[locale])
}
