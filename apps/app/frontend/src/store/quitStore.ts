import { create } from 'zustand'

// "Quit Proxyma?" and then "Proxyma has stopped". Held here and rendered once from AppShell, for
// the reason restartStore.ts gives: the screen must outlive whatever asked for it.
//
// Both Quit triggers are rows inside a menu, and the menu closes as the confirmation opens. When
// QuitButton held this state itself, closing the menu unmounted it together with the dialog it had
// just opened - so Quit Proxyma did nothing at all, and no quit request ever reached the backend.
// 'stopping' until the backend stops answering, then 'stopped' - see QuitDialogs.
type QuitPhase = 'idle' | 'confirming' | 'stopping' | 'stopped'

interface QuitState {
  phase: QuitPhase
  ask: () => void
  cancel: () => void
  stopping: () => void
  stopped: () => void
}

export const useQuitStore = create<QuitState>(set => ({
  phase: 'idle',
  ask: () => set({ phase: 'confirming' }),
  cancel: () => set({ phase: 'idle' }),
  stopping: () => set({ phase: 'stopping' }),
  stopped: () => set({ phase: 'stopped' }),
}))
