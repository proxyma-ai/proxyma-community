import { useCallback, useEffect, useRef, useState } from 'react'
import { useSettingsSaver } from '../pages/shared'
import { draftChanges, draftValue, withEdit, type SettingChange, type SettingDraft } from '../utils/settingsSave'

/**
 * WHEN THE FOCUS LEAVES AN ELEMENT: spread `bind` on it, and `onLeave` runs once the focus has gone
 * somewhere outside it. Decided a tick after a blur, so the focus moving from one field inside to the
 * next - or into a list a Select or Combobox opens in a portal, whose focus events React carries to
 * this element through the portal - is not a leave. Also run when the element goes (another tab, the
 * window closing), since that is a leave no blur reports.
 */
export function useFocusLeave(onLeave: () => void) {
  const timer = useRef<number | undefined>(undefined)
  const leaveRef = useRef(onLeave)
  leaveRef.current = onLeave
  useEffect(() => () => {
    window.clearTimeout(timer.current)
    leaveRef.current()
  }, [])
  return {
    onFocus: () => window.clearTimeout(timer.current),
    onBlur: () => {
      window.clearTimeout(timer.current)
      timer.current = window.setTimeout(() => leaveRef.current(), 0)
    },
  }
}

/**
 * A GROUP OF SETTINGS SAVED AS ONE (the click-to-edit rule, 2026-11-02 - fields meaningless apart:
 * the proxy mode and its addresses, a role's provider and model, Microsoft 365's tenant and client
 * IDs). `value` reads a field - the group's edit, else what it is given; `set` records an edit
 * without saving it; `bind` goes on the element holding the group, and when the person leaves it the
 * edits are saved together, through the window's one save (which shows them while they save and puts
 * the server's values back, with a toast, when refused). A field alone can be a group of one - a
 * secret, which has no other moment to save.
 */
export function useSettingGroup() {
  const { save } = useSettingsSaver()
  const [draft, setDraft] = useState<SettingDraft>({})
  // The draft as the last edit left it, for a save running before React has re-rendered.
  const latest = useRef<SettingDraft>({})

  const flush = useCallback(() => {
    const changes = draftChanges(latest.current)
    if (changes.length === 0) return
    latest.current = {}
    setDraft({})
    save(changes).catch(() => undefined)
  }, [save])
  const bind = useFocusLeave(flush)

  const set = useCallback((change: SettingChange, opts?: { saved?: string; secret?: boolean }) => {
    latest.current = withEdit(latest.current, change, opts)
    setDraft(latest.current)
  }, [])
  const value = (prefix: string, field: string, resolved: string) => draftValue(draft, prefix, field, resolved)
  return { value, set, flush, bind }
}

export type SettingGroup = ReturnType<typeof useSettingGroup>
