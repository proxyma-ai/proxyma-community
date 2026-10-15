import type { EditionRoute, EditionSignIn, EditionSourceDialog } from './types'

/**
 * The COMMUNITY edition: Desktop and the Home Server. What `@edition` resolves to in every build
 * except Enterprise's - see types.ts. One person, no accounts, so none of the Enterprise pages exist.
 */

export const EDITION_ROUTES: readonly EditionRoute[] = []

/** Whether the header lists Key accounts. Never, here. */
export function useKeyAccountsShown(): boolean {
  return false
}

/** Whether this deployment offers key accounts. Never, here. */
export function useKeyAccountsAllowed(): boolean | undefined {
  return false
}

/** The id of the key account of this name, for the guided tour. There are none here. */
export async function findKeyAccountIdByName(_name: string): Promise<string | null> {
  return null
}

/** What a redirect back to the app says. Nothing here: no service of this edition signs in that way. */
export function EditionNotices(): null {
  return null
}

/** The license banners. None here: Community needs no license. */
export function EditionBanners(): null {
  return null
}

/** Add dialogs of this edition's own source types: none - every type here has its own or the generic one. */
export const EDITION_SOURCE_DIALOGS: Readonly<Record<string, EditionSourceDialog>> = {}

/** Services with a sign-in of their own: none - every one here uses the generic OAuth2 flow. */
export const EDITION_SIGN_INS: Readonly<Record<string, EditionSignIn>> = {}

/** No service here needs the deployment set up before a person can sign in to it. */
export function useServiceSetupMissing(_protocol: string | null | undefined): string | null {
  return null
}
