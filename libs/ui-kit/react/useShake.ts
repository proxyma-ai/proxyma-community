'use client'

import { useCallback, useState } from 'react'

/**
 * A FIELD'S SHAKE ON A FAILED SUBMIT - the kit's `shake` prop on Input, Textarea, PasswordInput,
 * Combobox and Checkbox, re-triggerable. Setting a boolean to `true` twice in a row is no change to
 * React, so a second invalid press right after the first would not replay the animation: this
 * drops to false and flips back on the next frame. Under reduced motion the shake does not move
 * (the primitives), and the field's red border and message are what tell.
 *
 * It was the app's own; the consoles validated by disabling their submit buttons instead, which
 * tells a keyboard or a screen reader nothing. In the kit so they can do what the app does: keep
 * the button live, check on submit, mark the field and shake it.
 */
export function useShake(): { shaking: boolean; trigger: () => void } {
  const [shaking, setShaking] = useState(false)
  const trigger = useCallback(() => {
    setShaking(false)
    requestAnimationFrame(() => setShaking(true))
  }, [])
  return { shaking, trigger }
}
