import { useEffect, useRef } from 'react'
import { create } from 'zustand'

/**
 * WHERE A PAGE WANTS ITS TOASTS IN ITS OWN FLOW (since 2026-10-28, the UI review). The toast layer
 * is fixed under the header, on the page's grid; over a chat thread that is on top of the very
 * text being read, and the pinned question with it. A page that cannot be covered offers a dock -
 * an element in its own layout - and the container draws the toasts there instead. One dock at a
 * time; the last one registered wins, and leaving clears it only if it is still the one shown.
 */
interface ToastDockState {
  dock: HTMLElement | null
  setDock: (dock: HTMLElement | null) => void
}

export const useToastDockStore = create<ToastDockState>(set => ({
  dock: null,
  setDock: dock => set({ dock }),
}))

/** The ref for a page's dock element. */
export function useToastDock<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    useToastDockStore.getState().setDock(el)
    return () => {
      if (useToastDockStore.getState().dock === el) useToastDockStore.getState().setDock(null)
    }
  }, [])
  return ref
}
