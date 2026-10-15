/**
 * THE DOCUMENT TITLE, one shape everywhere: "Page - Product". The site wrote "Pricing | Proxyma",
 * the app "Chat - Proxyma", the license console "Sales - Proxyma License Manager" and the telemetry
 * console "Telemetry - Proxyma" on both its pages, so a person with several tabs open read three
 * separators and two namings. `product` is the surface's own name ("Proxyma", "Proxyma License
 * Manager"); with no page it is the product alone. Plain .ts, no imports.
 */
export const TITLE_SEPARATOR = ' - '

export function pageTitle(page: string | null | undefined, product: string): string {
  const name = (page ?? '').trim()
  return name ? `${name}${TITLE_SEPARATOR}${product}` : product
}
