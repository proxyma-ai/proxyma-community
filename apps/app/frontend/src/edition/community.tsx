import type { WorkingAgent } from '@/hooks/useWorkingAgents'
import type { EditionMention, EditionRoute, EditionSignIn, EditionSourceDialog } from './types'
import type { Walkthrough } from '@/features/tour/data/tourSteps'

/**
 * The COMMUNITY edition: Desktop and the Home Server. What `@edition` resolves to in every build
 * except Enterprise's - see types.ts. One person, no accounts, so none of the Enterprise pages exist.
 */

export const EDITION_ROUTES: readonly EditionRoute[] = []

/** Whether the header lists Key accounts. Never, here. */
export function useKeyAccountsShown(): boolean {
  return false
}

/** Whether the header lists Constellations. Never, here. */
export function useConstellationsShown(): boolean {
  return false
}

/** The edition's walkthroughs, and which stands in for the product's first-run tour. None here. */
export function useEditionWalkthroughs(): { first: string | null | undefined; tours: Walkthrough[] } {
  return NO_WALKTHROUGHS
}
const NO_WALKTHROUGHS: { first: null; tours: Walkthrough[] } = { first: null, tours: [] }

/** Whether the header lists the YouTube assistant. Never, here: it is an add-on. */
export function useYouTubeAssistantShown(): boolean {
  return false
}

/** Agents of the edition's own pages working out of sight, for the menu's dots. None here. */
export function useEditionWorkingAgents(): WorkingAgent[] {
  return NO_WORKING
}

const NO_WORKING: WorkingAgent[] = []

/** What the composer's @ list offers beside the sources. Nothing here: the sources are all there is. */
export function useEditionMentions(): EditionMention[] {
  return NO_MENTIONS
}

const NO_MENTIONS: EditionMention[] = []

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
