import type { ComponentType, ReactNode } from 'react'
import type { TabKey } from '../components/SettingsModal'
import type { TabProps } from './shared'

// The per-edition settings contract.
//
// Desktop and server show different settings. That difference is expressed HERE, as two modules
// implementing this one interface - `index.desktop.tsx` and `index.server.tsx` - and the build
// picks between them by alias (`@settings-pages`, see vite.config.ts). Only the chosen edition's
// module is in the module graph.
//
// The alternative, which this replaces, was a FEATURES.auth conditional inside each shared page.
// That kept both editions' UI in both bundles and made "is this visible here?" a question with
// twenty separate answers scattered through a 3600-line file. The failure mode was silent and
// pointed the wrong way: a section written without a conditional shows up in BOTH editions, so
// forgetting one leaks server UI onto desktop rather than hiding something. The inert per-user
// quota fields on desktop were exactly that.
//
// Rule for anything added later: if a control means different things in the two editions, or
// exists in only one, it belongs in these two files - not behind a flag in a shared component.

export interface NavContext {
  // Server roles. Desktop has no auth and ignores both - which is the point: desktop's module
  // never consults a role, rather than computing `!FEATURES.auth || isAdmin` and getting `true`.
  isAdmin: boolean
  isSuperAdmin: boolean
}

export interface NavGroup {
  label: string
  tabs: TabKey[]
}

// Pages that exist in ONE edition only, keyed by tab. The modal renders these through the map, so
// the component is imported by the edition that has it and by nothing else. A page listed in the
// other edition's nav but absent from its map simply does not render.
//
// TabProps, not no props: an extra page that edits SETTINGS has to join the modal's pending-change
// batch like every hand-switched tab above it, or its Save button would mean something different
// from the Save button on the tab beside it. Pages that edit nothing - Reset, Import & Export -
// simply ignore what they are passed, which TypeScript allows and which costs them nothing.
export type ExtraPages = Partial<Record<TabKey, ComponentType<ExtraPageProps>>>

// Switches the modal to another tab, with an optional focus inside it (a sub-tab of Logs, a
// connection on Connections). The modal's own setter, never the global store's open() - see the
// note on UsageCostTab in SettingsModal for why a link through the store can do nothing.
export type OpenTab = (tab: TabKey, focus?: string) => void

// What an extra page is handed: the pending-change batch, where the modal was asked to focus, and
// the way to another tab.
export type ExtraPageProps = TabProps & {
  focusKey?: string | null
  onOpenTab: OpenTab
}

export interface SettingsEdition {
  buildNavGroups: (ctx: NavContext) => NavGroup[]
  // Where the modal opens when no tab was requested, or when the requested one is not visible.
  defaultTab: TabKey
  extraPages: ExtraPages
  // The quota block inside Usage & Cost (called Cost Settings until the two merged on
  // 2026-09-02). Server renders the global ceiling and the per-user quotas; desktop renders only
  // the ceiling it actually enforces, because it has one user, no auth and no UserSpendPort, so
  // every per-user field was inert there. Not a flag inside one shared page - the desktop build
  // does not contain the fields at all.
  QuotaSection: ComponentType<TabProps>
  // Where Usage & Cost's spend readout went. The figures and every call moved to Logs on
  // 2026-10-12 - Settings on desktop, Administration on server - so both editions render a
  // signpost to their own copy of that page.
  SpendSection: ComponentType<{ onOpenTab: OpenTab }>
  // The way to the Logs page from a tab whose log moved there (2026-10-12). Desktop and Home
  // Server have it in Settings, so this switches tab; Enterprise Server has it in Administration,
  // so this leaves the modal for that page. `log` is the page's sub-tab.
  AuditLogsLink: ComponentType<{ log: string; onOpenTab: OpenTab; children: ReactNode }>
  // About's "Send feedback" row - desktop only, where it replaced the Feedback tab on 2026-10-12.
  // Server has the form in the account menu and renders nothing here.
  FeedbackSection: ComponentType
  // About's source-code offer and license link - Community only (Desktop, Home Server), which is
  // AGPL-3.0-only and has to offer its source to whoever uses it over a network (section 13).
  // Enterprise Server is not open source and renders nothing. See SourceCodeSection.desktop.tsx.
  SourceCodeSection: ComponentType
  // The custom CA field on the Network tab. Server only: a desktop machine behind a TLS-inspecting
  // proxy already trusts that proxy's root through the OS trust store, so the field can only break
  // something there. See ./CaCertificateSection.desktop.tsx.
  CaCertificateSection: ComponentType<TabProps>
  // The deployment's own registration for a service its users then sign in to, on the Network tab -
  // Enterprise Server's Microsoft 365 app registration (super admin only). Nothing on Desktop and
  // the Home Server, which have no such service.
  ServiceSetupSection: ComponentType<TabProps>
}
