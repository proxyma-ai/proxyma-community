import { FEATURES } from '@/config/features'
import type { SettingsEdition, NavGroup } from './types'
import { ResetPage } from './ResetPage'
import { ConfigTransferPage } from './ConfigTransferPage'
import { UpdatesPage } from './UpdatesPage'
import { QuotaSection } from './QuotaSection.desktop'
import { SpendSection } from './SpendSection.desktop'
import { CaCertificateSection } from './CaCertificateSection.desktop'
import { AuditLogsPage } from './AuditLogsPage'
import { AuditLogsLink } from './AuditLogsLink.desktop'
import { FeedbackSection } from './FeedbackSection.desktop'
import { SourceCodeSection } from './SourceCodeSection.desktop'
import { SponsorSection } from './SponsorSection.desktop'

// Proxyma DESKTOP settings.
//
// Desktop is the base edition: single local user, no auth, no roles, no accounts. Everything here
// is available to the one person using the machine, so nothing consults a role - there is nobody
// to withhold anything from. Anything role-shaped in this file would be a mistake.
//
// Desktop has no Administration page (it is server-only and not routed here), so the things an
// operator would find there - what this has cost, how to reset it - are settings destinations
// instead. That is why Usage & Cost and Reset appear here and not in the server one.
//
// See ./types.ts for why this is two files rather than conditionals in shared components.

// THREE GROUPS, BY WHAT A TAB IS ABOUT (regrouped 2026-10-02, maintainer's feedback).
//
// They were "Settings", "System Configuration" and "Help & About": a group called Settings inside
// Settings, and one list of nine that held the models, the agent, the index, the proxy and the
// retention rules alike - nothing in the rail said which tabs belong to one subject. Now:
//
//   Workspace        what YOU connect and work with - your servers, your skills, the other
//                    assistants you use this from.
//   AI & Knowledge   how the product does its job: the models, the agent and what it may say and
//                    spend, then how documents are read and found again.
//   Application      this installation - how it reaches the network, what it keeps, moving its
//                    configuration, its license and version, and the ways out.
//
// Three and not four or five: the rail fits a 1280x720 window without scrolling with sixteen
// pixels to spare (.claude/rules/frontend.md), and a heading costs forty.
//
// FOUR TABS WERE RENAMED the same day, for what a reader calls the thing: LLM Gateway is AI
// Providers, Connectors is Connections, Documents is Conversion and Retrieval is Search. The
// manuals and some forty backend messages ("Settings > Connections") went with them. About, AI
// Assistants, License and Updates kept their names because the legal documents name them, and a
// stored chat link is a SLUG, which did not change (MarkdownRenderer's SETTINGS_TAB_BY_SLUG).
//
// index.server.tsx repeats the two shared groups' order. NOT imported from here - only one of the
// two edition modules is in a given build's module graph, and a shared constant would drag this
// file into the server bundle. If the two ever disagree, this one is the reference.

const WORKSPACE: NavGroup = {
  label: 'Workspace',
  tabs: [
    'Connections',
    'Skills',
    // What the agent has saved about you and your work, to read and correct (2026-10-03). Here
    // rather than under AI & Knowledge because it is YOURS - on a server every user has their own.
    'Memory',
    // Beside Skills, because they are the two halves of one answer to "what can this agent do":
    // a skill is instructions it may choose to follow, a workflow is a plan it must. Hidden as of
    // 0.1.1 - see FEATURES.workflows, which the backend property of the same name must match.
    ...(FEATURES.workflows ? ['Workflows' as const] : []),
    // The other way to use what this install has indexed: from Claude, ChatGPT or Gemini.
    'AI Assistants',
  ],
}

// THE SETUP PATH, in order (2026-10-10, maintainer's report: after adding a provider nobody could
// tell what to configure next). A provider alone does nothing - every model role names a provider
// and a model, and none has a default. So: AI Providers, then the two roles the product cannot work
// without - Agents (the model that answers) and Search (the embedding model that indexes, plus web
// search and the search time limit) - then Conversion, whose OCR vision model is optional (blank
// leaves OCR off), then Guardrails (on with rule checks alone; the model check is optional), and
// Usage & Cost last, which reads the rates set on each provider and caps the spend. The AI
// Providers tab lists the same steps as a checklist until the two required ones are done.
// It was AI Providers, Agents, Guardrails, Usage & Cost, Conversion, Search: what the agent may say
// and spend sat between the agent and the model that indexes, which every setup needs first.
const AI_AND_KNOWLEDGE: NavGroup = {
  label: 'AI & Knowledge',
  tabs: [
    'AI Providers', 'Agents', 'Search',
    'Conversion', 'Guardrails', 'Usage & Cost',
  ],
}

// Running it, then reading about it, then the ways out. Import & Export comes after the two things
// it carries away; Updates says what you are running and can change it. No License tab: Desktop and
// the single-user server are Community, which needs no license. The feedback form, which was a tab here, is a row in About since 2026-10-12 - still the ONLY
// route to it on desktop (FeedbackSection.desktop.tsx).
//
// Reset dead LAST, after everything it could destroy: every other destructive action in this
// product sits at the bottom of its screen (Account's Danger Zone, Administration > System's), and
// being the final item in the final group is the same statement.
const APPLICATION: NavGroup = {
  label: 'Application',
  tabs: [
    // Logs beside Data Retention, which decides how long its entries are kept. It took the
    // Feedback tab's row on 2026-10-12: the rail fits 1280x720 with six pixels to spare, and the
    // feedback form is now a row in About.
    'Network', 'Data Retention', 'Logs', 'Import & Export',
    'Updates',
    'About',
    'Reset',
  ],
}

export const edition: SettingsEdition = {
  buildNavGroups: () => [WORKSPACE, AI_AND_KNOWLEDGE, APPLICATION],
  // Account is the server default and does not exist here.
  defaultTab: 'Connections',
  // Updates is desktop-only for the reason its own file states: a server deployment is a container
  // the operator upgrades with install.sh or a docker pull, so a button that replaces files under
  // a running JVM has nothing to do there. The endpoints exist in both editions; only the page is
  // scoped, which is exactly what this map is for.
  extraPages: {
    'Reset': ResetPage,
    'Import & Export': ConfigTransferPage,
    'Updates': UpdatesPage,
    // Every log this installation keeps - see features/audit/components/AuditLogs.tsx.
    'Logs': AuditLogsPage,
  },
  // A spending limit that this edition genuinely enforces, and none of the per-user quotas that
  // it does not. See QuotaSection.desktop.tsx - the distinction is drawn there against what the
  // backend actually loads, not against what reads like a desktop feature.
  QuotaSection,
  // The real figures - this month, lifetime, and a breakdown by model. One user and one machine,
  // so the only honest question is "what has this cost me". See SpendSection.desktop.tsx.
  SpendSection,
  // Renders nothing here. The OS trust store on this machine already carries whatever root a
  // corporate middlebox uses, and the JVM reads it - see CaCertificateSection.desktop.tsx.
  CaCertificateSection,
  // No service here needs the deployment registered with it first (Microsoft 365 is Enterprise's).
  ServiceSetupSection: () => null,
  // Logs is a tab of this window here.
  AuditLogsLink,
  // The only route to the feedback form on this edition.
  FeedbackSection,
  // AGPL-3.0-only: the source offer and the license.
  SourceCodeSection,
  // Community is free: the one way to support it.
  SponsorSection,
}
