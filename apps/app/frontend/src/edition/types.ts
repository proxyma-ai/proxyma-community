import type { ComponentType, LazyExoticComponent, ReactNode } from 'react'

/**
 * THE EDITION SEAM - what the app asks of the edition it was built as, and nothing else.
 *
 * Three implementations, chosen at BUILD time by the `@edition` alias (vite.config.ts, tsconfig.json):
 *
 *   src/edition/community.tsx          Desktop and the Home Server (`npm run build`). Open source.
 *   ee/frontend/src/edition.tsx         Proxyma Enterprise (`npm run build:server`). Closed source,
 *                                       in the private repository only (docs/architecture.html#oss-boundary).
 *   ee/frontend/src/edition.addons.tsx  the official Desktop and Home Server (`npm run build:addons`):
 *                                       the Community app plus its add-ons, each activated by its own
 *                                       key. Closed source, as above.
 *
 * The Community code never imports the Enterprise code; it imports `@edition`, and a tree without
 * ee/ resolves that to the Community file. So key accounts and Administration are not dead chunks
 * in the desktop bundle kept out of sight by a flag - they are not in its module graph at all.
 *
 * Kept SMALL on purpose: every member here is a place the Community app knows an Enterprise feature
 * exists. A new Enterprise page is a route below, not a new member.
 */

/** A page only the edition has, mounted inside the app shell. */
export interface EditionRoute {
  path: string
  /** The page, loaded when its route is first visited. */
  page: LazyExoticComponent<ComponentType>
  /** Shaped like the page, shown while its chunk loads. */
  fallback: ReactNode
  /**
   * Who may open it.
   *
   *   'signed-in', 'admin'   Enterprise Server's pages: server mode only (RequireServerMode), then a
   *                          session (RequireAuth) or an administrator's (RequireAdmin).
   *   'anyone'               a page that also opens on Desktop and the Home Server - an add-on's
   *                          (ee/frontend/src/edition.addons.tsx). No guard at all: those editions
   *                          have no sign-in, and their access code is checked by the server on
   *                          every request the page makes, as for every other page there.
   */
  access: 'signed-in' | 'admin' | 'anyone'
}

/** Translations the edition adds to the app's own, by locale (source text -> translation). */
export type EditionCatalogs = { vi: Readonly<Record<string, string>> }

/**
 * A source type whose add dialog is the edition's own (Outlook's folder and calendar pickers), by
 * the type's wire name. The Sources page opens it in place of the dialog drawn from the descriptor.
 */
export type EditionSourceDialog = ComponentType<{ onClose: () => void }>

/**
 * A service whose sign-in is its own flow rather than the generic OAuth2 one (Microsoft 365, with
 * its tenant checks), by its protocol. Both are browser-facing: `startUrl` is NAVIGATED to, never
 * fetched, because it answers with a redirect to the service's sign-in page.
 */
export interface EditionSignIn {
  /** Where the sign-in for one connection starts. */
  startUrl: (connectionId: string) => string
  /** Drops the connection's sign-in and keeps the connection. */
  signOut: (connectionId: string) => Promise<void>
}

// THE REST OF THE SEAM - each implemented by src/edition/community.tsx (doing nothing) and by
// ee/frontend/src/edition.tsx:
//
//   EDITION_ROUTES            the edition's pages (above)
//   EditionNotices            mounted once by the router: what a redirect back to the app says
//                             (Microsoft 365's sign-in outcome)
//   EditionBanners            mounted once in the app shell: the license re-check and trial banners
//   EDITION_SOURCE_DIALOGS    add dialogs of the edition's own source types (EditionSourceDialog)
//   EDITION_SIGN_INS          sign-ins of the edition's own services (EditionSignIn)
//   useServiceSetupMissing    the sentence to show when the DEPLOYMENT is not set up for a service
//                             (an administrator's step - Microsoft 365's app registration), else null
//   useKeyAccountsShown, useKeyAccountsAllowed, findKeyAccountIdByName   key accounts
//   useConstellationsShown    whether the header lists Constellations (projects of saved agents)
