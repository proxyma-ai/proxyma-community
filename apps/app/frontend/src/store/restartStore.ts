import { create } from 'zustand'

// "The application is restarting." One flag, one owner, because the screen that reports it must
// outlive whatever asked for the restart.
//
// Every caller used to hold its own boolean and render RestartingModal itself - SettingsModal,
// AdminPage's Danger Zone, and (not at all, which was the bug) the desktop Reset page. Three of
// the four triggers live INSIDE the Settings modal, and that modal closes on a backdrop click:
// closing it unmounted the component holding the flag and took the blocking screen down with it,
// leaving the user looking at a normal, fully interactive app whose backend had already exited.
// Held here and rendered once from AppShell, the screen stays up no matter what unmounts.
//
// Nothing clears this on success. The restart ends with `window.location.reload()`, so the flag
// dies with the page; `end()` exists for the failure path, where the app did not come back and
// the user needs the UI returned to them along with the error toast.
interface RestartState {
  restarting: boolean
  begin: () => void
  end: () => void
}

export const useRestartStore = create<RestartState>(set => ({
  restarting: false,
  begin: () => set({ restarting: true }),
  end: () => set({ restarting: false }),
}))
