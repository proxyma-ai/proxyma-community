import type { TabKey } from '@/features/settings/components/SettingsModal'
import { FEATURES, type FeatureKey } from '@/config/features'

// The onboarding walkthrough (TourOverlay draws it, useOnboardingTour starts and records it).
//
// A WELCOME, THEN FOUR STOPS THAT SAY WHAT THE PRODUCT IS - since 2026-10-06, at the maintainer's
// request. The 2026-09-30 version (UI review F20) was five one-sentence pointers at controls - "type
// @ here", "history is here" - which said where things are and never what Proxyma is for, so a new
// user met a chat box and read it as a chat bot. The order is the maintainer's: the welcome; chat as
// a TEAM OF AGENTS doing long multi-step work across systems; the sources it knows; the two together
// on one real task - since the key-account demo replaced the CRA case, the Key accounts page
// (docs/architecture.html#spec-11); and the settings that decide how far it may go.
//
// What F20 got right still holds, and is why this is not the old ten-slide deck back:
// - a stop that points at a control sits BESIDE it, never over it (TourOverlay.computePlacement);
// - each stop is a title, two or three sentences and at most three short points - the brand claims
//   ("100% on-premise") stay on the website, and every sentence describes what this build does;
// - a stop whose control is not on the page is SKIPPED, never shown floating.
//
// Fields:
// - `kind: 'welcome'`: the opening dialog, centred, with its own two buttons and no counter.
// - `targetId` / `targetSelector`: what the stop points at - a `data-tour-id`, or a CSS selector for
//   a kit part that carries no tour id (the Settings rail). Neither means a CENTRED card: a stop
//   about the product as a whole, not about one control.
// - `route`, `openSettingsTab`: where the stop happens (Settings is a modal, not a route).
// - `requiresRole`, `requiresFeature`, `requiresLicense`: who, which builds and which licenses see it
//   (see TourOverlay.shouldSkipStep).
// - `title`, `text`, `points`, `sandboxNote`, `sandboxAction`: the copy, looked up through the
//   translation function at render - each property is in the DYNAMIC list of check-catalogs.mjs,
//   so each string must stay ONE quoted literal on ONE line.
// - `sandboxNote` / `sandboxAction`: shown only on the public Enterprise sandbox, where a demo key
//   account is seeded with its sources (SandboxScenarioSeeder). TourOverlay sends the action to that
//   account, found by SANDBOX_KEY_ACCOUNT_NAME, and to the list when it is gone.
//
// THE "example" STOP IS ENTERPRISE SERVER ONLY, and skipped everywhere else. Key accounts is an
// Enterprise Server page; on Desktop and Home Server, or a server without an Enterprise license, a
// stop describing it would describe something that build does not do - against the rule above that
// every sentence describes what this build does. Those editions see the other three stops.
export type TourRole = 'admin' | 'auth'

export interface TourStep {
  id: string
  kind?: 'welcome'
  route?: string
  openSettingsTab?: TabKey
  targetId?: string
  targetSelector?: string
  requiresRole?: TourRole
  requiresFeature?: FeatureKey
  requiresLicense?: 'keyAccounts'
  title: string
  text: string
  points?: string[]
  sandboxNote?: string
  sandboxAction?: { label: string; route: string }
}

/** The demo account the Enterprise sandbox seeds (SandboxScenarioSeeder.KEY_ACCOUNT_NAME). */
export const SANDBOX_KEY_ACCOUNT_NAME = 'Harrowmere Freight Group'

// Where feedback is sent from depends on the edition: Settings > Feedback on Desktop and Home Server,
// the account menu on Enterprise Server, whose Settings leaves Feedback out on purpose
// (index.server.tsx). The tour is replayed from Settings > About on every edition. Both lines are
// bare array elements, one literal each, so check-catalogs.mjs sees them; index 1 is the server's.
const FEEDBACK_POINT = [
  'Send us feedback, and replay this tour, from Settings',
  'Send us feedback from your account menu, and replay this tour from Settings > About',
]

export function getTourSteps(): TourStep[] {
  return [
    {
      id: 'welcome',
      kind: 'welcome',
      route: '/',
      title: 'Welcome to Proxyma',
      text: 'Search everything your company knows, then hand the work to agents that act on it.',
      points: [
        'A few short stops, under a minute',
        'Replay it any time from Settings > About',
      ],
    },
    {
      id: 'agents',
      route: '/',
      targetId: 'tour-chat-input',
      title: 'Not a chatbot - a team of agents',
      text: 'Give it a task, not just a question. The main agent plans the work, hands parts of it to sub-agents running in parallel, and keeps going across your systems for as many steps as the job takes.',
      points: [
        'Every step, source and change stays visible in the thread',
        'Writes to Jira, Confluence or Bitbucket wait for your approval unless you let it work alone',
        'Type @ to aim it at one source, / to load a skill',
      ],
    },
    {
      id: 'sources',
      route: '/sources',
      targetId: 'tour-add-source',
      requiresRole: 'admin',
      title: 'Everything it knows, kept current',
      text: 'Folders, Office files, scanned PDFs, websites, Jira, Confluence and Bitbucket - indexed for keyword and meaning search, plus a knowledge graph when you turn it on, and synced again as they change.',
      points: [
        'Answers cite the passage they came from',
        'Live systems are reached with the token of the person asking',
        'Add a source here; chat can search it once it is indexed',
      ],
    },
    {
      id: 'example',
      route: '/key-accounts',
      requiresLicense: 'keyAccounts',
      title: 'One task, many systems, many agents',
      text: 'Key accounts puts that to work: a team reviews one important client together. Agents read the contracts, tickets, meeting minutes and client website in parallel, then propose health ratings with their evidence, risks and next moves.',
      points: [
        'A person confirms every rating and decides every move',
        'Everyone on the account sees each change as it happens, and every change is logged',
        'Ask the assistant panel on an account to run the review',
      ],
      sandboxNote: 'This sandbox has a fictional client already reviewed: agents read its contracts, revenue, tickets and minutes. Open it to see the ratings, risks and moves they proposed.',
      sandboxAction: { label: 'Open the demo account', route: '/key-accounts' },
    },
    {
      id: 'settings',
      openSettingsTab: 'Agents',
      targetSelector: '[data-dialog-panel] nav',
      requiresRole: 'admin',
      title: 'You set the rules',
      text: 'Choose the model for each role, how far the agent may go on its own, what it may say and how much it may spend.',
      points: [
        'Read-only, ask before each write, or work alone - chosen in the chat box',
        'Step limits, guardrails and spending limits',
        FEEDBACK_POINT[FEATURES.auth ? 1 : 0],
      ],
    },
  ]
}
