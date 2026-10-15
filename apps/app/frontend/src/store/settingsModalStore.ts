import { create } from 'zustand'
import type { TabKey } from '@/features/settings/components/SettingsModal'

// Settings is a global overlay (mounted once in AppShell) reachable from any page - unlike every
// other modal in this app, which is opened via page-local useState because it only ever needs to
// open from the one page that owns it. `initialTab` lets a caller (Docs, a connector-connector
// modal, ...) jump straight to a specific tab instead of always landing on the default.
//
// `initialFocus` goes one step further than `initialTab`: it names something WITHIN the tab to
// open or focus (on Connections, a connection id or a service key like 'jira'). It arrives from the
// optional trailing segment of an `app://settings/<tab>/<focus>` link the agent relayed
// from a tool error - see SettingsLinks.java. Landing on the tab alone still left the user picking
// their connector out of a list of collapsed sections; this lands the cursor in the field itself.
// An unknown value is simply ignored by the tab, so it can never break the plain open-the-tab case.
interface SettingsModalState {
  isOpen: boolean
  initialTab: TabKey | null
  initialFocus: string | null
  open: (tab?: TabKey, focus?: string) => void
  close: () => void
}

export const useSettingsModalStore = create<SettingsModalState>(set => ({
  isOpen: false,
  initialTab: null,
  initialFocus: null,
  open: (tab, focus) => set({ isOpen: true, initialTab: tab ?? null, initialFocus: focus ?? null }),
  close: () => set({ isOpen: false, initialTab: null, initialFocus: null }),
}))
