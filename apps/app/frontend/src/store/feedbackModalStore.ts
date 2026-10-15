import { create } from 'zustand'

// Feedback is reachable from the account menu on every width, so its open state lives here rather
// than in the component that used to own it.
//
// It was a floating button pinned `fixed bottom-6 left-6`. On a phone and a tablet that put a 48px
// circle permanently on top of the page content - over the chat composer's own controls, and over
// the bottom of any table - with no width at which it stopped overlapping something. Moving it into
// the account menu, beside Settings, costs one tap and covers nothing.
interface FeedbackModalState {
  isOpen: boolean
  open: () => void
  close: () => void
}

export const useFeedbackModalStore = create<FeedbackModalState>(set => ({
  isOpen: false,
  open: () => set({ isOpen: true }),
  close: () => set({ isOpen: false }),
}))
