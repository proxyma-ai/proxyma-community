/**
 * How a provider's custom header is SHOWN in its list (0.28.0 feedback: the headers became a list
 * like the model prices, each row the header's name over its value).
 *
 * A value that is a credential is shown by its first and last characters only - enough to tell two
 * keys apart, not enough to copy one off a screen share. Which values are credentials is decided by
 * the header's NAME (Authorization, X-Api-Key, anything carrying token, secret, key, auth, cookie or
 * password), because the value of a credential and the value of "HTTP-Referer" can look alike.
 *
 * The server applies the SAME name rule (ProviderHeaders.java - keep the two patterns alike): it
 * stores every header encrypted and returns a credential's value already masked, so the form holds
 * the mask, and a mask saved back unchanged keeps the stored value. This adds the display of an
 * ordinary header beside it. Plain `.ts` with no imports, so `node --test` reaches it.
 */

const SECRET_NAME = /(auth|token|secret|password|passwd|cookie|session|signature|credential|api[-_]?key|(^|[-_])key($|[-_]))/i

/** Whether a header of this name carries a credential. */
export function isSecretHeader(name: string): boolean {
  return SECRET_NAME.test(name.trim())
}

/**
 * The value as the list shows it: in full for an ordinary header, and for a credential its first
 * and last few characters around a fixed `***` - the same mask everywhere else in the product, so a
 * short secret does not reveal its length. "Bearer sk-abc123xyz" keeps its scheme word visible,
 * since that says what kind of credential it is and nothing about it.
 */
export function displayHeaderValue(name: string, value: string): string {
  if (!isSecretHeader(name)) return value
  const v = value.trim()
  if (!v) return ''
  const scheme = /^(Bearer|Basic|Token|Bot)\s+(.+)$/i.exec(v)
  if (scheme) return `${scheme[1]} ${maskSecret(scheme[2])}`
  return maskSecret(v)
}

function maskSecret(secret: string): string {
  if (secret.length <= 4) return '***'
  if (secret.length <= 10) return `${secret[0]}***${secret[secret.length - 1]}`
  return `${secret.slice(0, 3)}***${secret.slice(-3)}`
}
