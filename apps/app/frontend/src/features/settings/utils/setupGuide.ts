/**
 * WHERE A SERVICE'S SETUP IS EXPLAINED (2026-11-07, the maintainer: "Adding google client is not easy, so we need
 * instruction for it and leave a hyperlink to the instruction on the corresponding add connection modal"). Every
 * service's descriptor names its section of the customer manual (ProtocolDescriptor.docsAnchor - the Google Cloud app's
 * is `google-cloud-setup`); the connection dialog links it in the reader's language where the manual has one. Plain .ts
 * so `node --test` reads it.
 */

const SITE = 'https://proxyma.ai'
/** The languages the manual is written in (apps/landing/src/content/docs); any other reads the English one. */
const MANUAL_LOCALES: readonly string[] = ['vi']

/** The manual's section for a service, or null when it names none. Only letters, digits and hyphens are an anchor. */
export function setupGuideUrl(anchor: string | null | undefined, locale: string): string | null {
  if (!anchor || !/^[a-z0-9-]+$/.test(anchor)) return null
  const lang = locale.toLowerCase().split('-')[0]
  const prefix = MANUAL_LOCALES.includes(lang) ? `/${lang}` : ''
  return `${SITE}${prefix}/docs/manual/#${anchor}`
}
