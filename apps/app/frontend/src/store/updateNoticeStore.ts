import { create } from 'zustand'

// "Later" on the desktop window's "Restart to update" (features/system/components/UpdateNotice.tsx):
// the version it was pressed for. IN MEMORY ONLY, deliberately - no setting and no storage. The page
// reloads at every start of the app (the restart screen ends in a reload), so "until Proxyma next
// starts" is exactly a page's lifetime, and a newer version staged meanwhile is not this one.
interface UpdateNoticeState {
  dismissedVersion: string | null
  dismiss: (version: string) => void
}

export const useUpdateNoticeStore = create<UpdateNoticeState>(set => ({
  dismissedVersion: null,
  dismiss: version => set({ dismissedVersion: version }),
}))
