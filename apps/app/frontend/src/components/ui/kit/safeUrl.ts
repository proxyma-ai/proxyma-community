/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/safeUrl.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * WHICH ADDRESSES A LINK MAY CARRY (2026-10-14, the security review's swx-11, swx-12 and the
 * post-login target).
 *
 * React 18 still renders a `javascript:` href - it warns and does it anyway - so every link built
 * from data is one stored value away from running script on the page's origin. These answer the
 * question once, for every caller, instead of each caller trusting that something upstream
 * checked. Plain `.ts` with no imports, so `node --test` reaches it.
 *
 * Each one parses with the WHATWG `URL` parser, which is the parser the browser itself uses for an
 * `href`: so "java\tscript:", a leading control character or an upper-case scheme are judged as
 * the browser would read them, not as a regular expression guesses.
 */

const EXTERNAL_PROTOCOLS = new Set(['http:', 'https:', 'mailto:'])

function parseAbsolute(href: string): URL | null {
  try {
    return new URL(href)
  } catch {
    return null
  }
}

/**
 * The href itself when it is an ABSOLUTE http, https or mailto address, and `undefined` for
 * anything else - `javascript:`, `data:`, `vbscript:`, `file:`, a relative path, a blank. What
 * leaves the app is always a whole address, so a relative one is refused too: it would resolve
 * against the page and pose as a server path.
 */
export function safeExternalHref(href: string | null | undefined): string | undefined {
  if (typeof href !== 'string' || href.trim() === '') return undefined
  const url = parseAbsolute(href)
  return url && EXTERNAL_PROTOCOLS.has(url.protocol) ? href : undefined
}

/** An absolute http or https address, parsed - for a caller that also needs its host. */
export function httpUrl(href: string | null | undefined): URL | null {
  if (typeof href !== 'string') return null
  const url = parseAbsolute(href.trim())
  return url && (url.protocol === 'http:' || url.protocol === 'https:') ? url : null
}

// One plain address: a local part and a dotted domain, nothing that a mailto URL reads as a
// separator. `?` starts the headers (bcc, subject, body), `&`, `,` and `;` add recipients, `%`
// would smuggle either in encoded, and `#` ends the address.
const PLAIN_ADDRESS = /^[A-Za-z0-9._+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/

/**
 * `mailto:` plus the address when it is ONE plain address, and `undefined` otherwise. An address
 * somebody else typed - a feedback form's contact field - can carry `?bcc=` and a body, and a
 * reply sent from that link copies whoever they chose. Anything this refuses is still shown, as
 * text; it is only not a link.
 */
export function mailtoHref(address: string | null | undefined): string | undefined {
  if (typeof address !== 'string') return undefined
  const plain = address.trim()
  return plain.length <= 254 && PLAIN_ADDRESS.test(plain) ? `mailto:${plain}` : undefined
}

const SAME_ORIGIN_PROBE = 'https://same-origin.invalid'

/**
 * The path itself when it stays on this origin - `/shared/abc?x=1` - and `null` otherwise. A
 * leading `//` is another host, and so is `/\evil.example`: the URL parser reads a backslash as a
 * slash in an http URL, so the browser turns it into `//evil.example`. Control characters and
 * backslashes are refused outright rather than reasoned about.
 */
export function safeLocalPath(path: string | null | undefined): string | null {
  if (typeof path !== 'string' || !path.startsWith('/')) return null
  if (/[\\\u0000-\u001f\u007f]/.test(path)) return null
  try {
    const url = new URL(path, SAME_ORIGIN_PROBE)
    return url.origin === SAME_ORIGIN_PROBE ? path : null
  } catch {
    return null
  }
}
