import { create } from 'zustand'

// Set when any API call is answered 401 `access_required` - the browser's access cookie is gone or
// was never there. AccessGate then shows the access screen in place of the app. A plain zustand
// store, like authStore, so the HTTP client can reach it without an import cycle.
interface AccessState {
  locked: boolean
  lock: () => void
}

export const useAccessStore = create<AccessState>(set => ({
  locked: false,
  lock: () => set({ locked: true }),
}))
