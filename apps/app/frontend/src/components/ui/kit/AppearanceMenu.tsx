/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/AppearanceMenu.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { Check, Monitor, Moon, Sun } from 'lucide-react'
import { IconMenu } from './IconMenu'
import { menuRow } from './menu'
import { APPEARANCE_PREFERENCES, type AppearancePreference } from './appearance'
import { Logo } from './Logo'

/**
 * The glyph for each appearance, shared by the header control here and each app's menu row.
 *
 * <p>Dark (`black`) is the moon and Proxyma (`gray`) is the Proxyma mark: the neutral dark is the
 * one people mean by "dark", and the teal one is the brand's own. Changed 2026-09-26 from a moon
 * for Proxyma and a filled circle for Dark.
 */
export function AppearanceIcon({ preference, size = 16, className }: {
  preference: AppearancePreference
  size?: number
  className?: string
}) {
  const props = { size, className, 'aria-hidden': true as const }
  switch (preference) {
    case 'system': return <Monitor {...props} />
    case 'light':  return <Sun {...props} />
    case 'black':  return <Moon {...props} />
    case 'gray':   return <Logo markOnly size={size} className={className} />
  }
}

/**
 * Appearance - System, Light, Dark or Proxyma - as the header's round icon button and its panel.
 *
 * <p>REPLACED `ThemeToggle` in 0.13.0. A one-click flip works with exactly two choices and cannot
 * say which one you are on; with four it cannot work at all. This is the language control's shape
 * for the same reason that control has it: every choice listed, the current one checked, on the
 * shared `IconMenu` shell.
 *
 * <p>The trigger shows the CURRENT choice's glyph, so System reads as a monitor even while it is
 * painting the page dark - the preference, not its result, is what this control changes.
 *
 * <p>CHOOSING DOES NOT CLOSE THE PANEL. Appearance is something people flip between to compare,
 * and the page behind the panel is the comparison. Escape or a press outside closes it.
 *
 * <p>PRESENTATIONAL: the preference comes in and the choice goes out, and the labels arrive
 * already translated - a kit file cannot reach an app's store or its translation runtime.
 */
export interface AppearanceMenuProps {
  current: AppearancePreference
  onChoose: (preference: AppearancePreference) => void
  /** Already translated, one per preference. */
  names: Record<AppearancePreference, string>
  /** Already translated - the trigger is icon-only. */
  label: string
  className?: string
}

export function AppearanceMenu({ current, onChoose, names, label, className }: AppearanceMenuProps) {
  return (
    <IconMenu
      icon={<AppearanceIcon preference={current} />}
      label={`${label}: ${names[current]}`}
      role="menu"
      className={className}
    >
      {() => APPEARANCE_PREFERENCES.map(p => {
        const isCurrent = p === current
        const content = (
          <>
            <span className="flex items-center gap-2.5">
              <AppearanceIcon preference={p} size={15} className="shrink-0" />
              <span>{names[p]}</span>
            </span>
            {isCurrent && <Check size={14} className="shrink-0 text-brand-legible" aria-hidden="true" />}
          </>
        )
        // Every choice a radio row, the current one checked - so the keyboard lands on it and a
        // screen reader says which is chosen. Choosing keeps the menu open, to compare.
        return (
          <button
            key={p}
            type="button"
            role="menuitemradio"
            aria-checked={isCurrent}
            className={menuRow(isCurrent ? 'active' : 'default', 'justify-between')}
            onClick={() => onChoose(p)}
          >
            {content}
          </button>
        )
      })}
    </IconMenu>
  )
}
