import { useEffect, useRef, useState } from 'react'
import { useUserQuota } from './useUserQuota'
import {
  isFeedbackMilestoneDismissedForever,
  markBracketShownThisCycle,
  markFeedbackMilestoneDismissedForever,
  wasBracketShownThisCycle,
} from '@/store/quotaMilestoneFeedbackStore'

// Thin wrapper around useUserQuota (item 4) that detects newly-crossed $5 spend brackets and
// decides whether the QuotaMilestoneFeedbackModal should open - kept separate from useUserQuota
// itself so that hook stays a plain data-fetching hook with no localStorage/modal-open side
// effects (ChatPage's quota ring also uses useUserQuota and must not be affected by any of this).
//
// bracket = floor(currentCycleSpendUsd / 5) - a crossing is detected whenever the poll produces a
// higher bracket than the previous poll saw (tracked in a ref, not state, since the previous
// value itself never needs to trigger a re-render on its own). cycleKey comes from cycleResetsAt
// so a monthly reset naturally produces a new key and re-arms every bracket for the new cycle,
// per the confirmed (not lifetime-once) nag behavior.
export function useQuotaMilestoneFeedback() {
  const { data: quota } = useUserQuota()
  const prevSpendRef = useRef<number | null>(null)
  const [isOpen, setIsOpen] = useState(false)
  const [bracket, setBracket] = useState(0)

  useEffect(() => {
    // enabled=false (non-Enterprise - see UserQuotaController) means currentCycleSpendUsd is
    // absent, not zero - bail out rather than let the bracket math below run on undefined and
    // produce NaN comparisons (NaN <= 0 is always false, so the early-return guard right after
    // this wouldn't actually catch it).
    if (!quota || !quota.enabled) return
    const prevSpend = prevSpendRef.current
    prevSpendRef.current = quota.currentCycleSpendUsd
    if (prevSpend === null) return // first poll this session - nothing "crossed" yet

    const prevBracket = Math.floor(prevSpend / 5)
    const newBracket = Math.floor(quota.currentCycleSpendUsd / 5)
    if (newBracket <= prevBracket || newBracket <= 0) return

    const cycleKey = quota.cycleResetsAt
    if (isFeedbackMilestoneDismissedForever()) return
    if (wasBracketShownThisCycle(newBracket, cycleKey)) return

    setBracket(newBracket)
    setIsOpen(true)
  }, [quota])

  const cycleKey = quota?.enabled ? quota.cycleResetsAt : ''

  // Dismiss without submitting (X / backdrop) - only suppresses THIS bracket for THIS cycle, the
  // next +$5 bracket (or next month's reset) will still show.
  const dismiss = () => {
    if (cycleKey) markBracketShownThisCycle(bracket, cycleKey)
    setIsOpen(false)
  }

  // A submitted bracket shouldn't re-nag either, same bookkeeping as a plain dismiss.
  const afterSubmit = () => {
    if (cycleKey) markBracketShownThisCycle(bracket, cycleKey)
    setIsOpen(false)
  }

  const neverShowAgain = () => {
    markFeedbackMilestoneDismissedForever()
    setIsOpen(false)
  }

  return { isOpen, bracket, dismiss, afterSubmit, neverShowAgain }
}
