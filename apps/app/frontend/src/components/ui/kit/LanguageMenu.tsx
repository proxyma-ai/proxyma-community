/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/LanguageMenu.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { Check, Languages } from 'lucide-react'
import { IconMenu } from './IconMenu'
import { menuRow } from './menu'

/**
 * Language, as the header's second round icon button and the panel it opens.
 *
 * <p>ONE IMPLEMENTATION FOR FOUR FRONTENDS, and this one had genuinely diverged: the landing site
 * renders ANCHORS, because there the locale is a URL segment and a link is the only thing a
 * crawler can follow - `/vi/` was unreachable until one existed. The app renders BUTTONS, because
 * there the locale is client state with no route of its own. Two correct answers to two different
 * questions, which is why this takes a `renderRow` rather than pretending one shape fits.
 *
 * <p>Everything AROUND the rows is identical and was written twice anyway: the trigger, the
 * outside-click and Escape handling, the panel, the check on the current row. That is what this
 * shares - and since 0.13.0 the shell under it is `IconMenu`, which `AppearanceMenu` uses too.
 *
 * <p>EVERY LANGUAGE IS LISTED, INCLUDING THE CURRENT ONE. Naming only the language you would GET
 * reads as a one-click toggle and works with exactly two - but such a list cannot tell you which
 * one you are on. The current row is inert and carries a check.
 *
 * <p>Names are written in their OWN language - "Tieng Viet", never "Vietnamese" - because somebody
 * who needs this control cannot necessarily read the language it is currently displaying.
 */
export interface LanguageMenuProps<L extends string> {
  locales: readonly L[]
  current: L
  /** Written in its own language. */
  nameOf: (locale: L) => string
  /**
   * The row's clickable shell. An app that changes the locale by NAVIGATING returns an anchor; one
   * that changes client state returns a button. `close` is passed so either can dismiss the panel.
   */
  renderRow: (args: { locale: L; className: string; children: ReactNode; close: () => void }) => ReactNode
  /** Already translated - the trigger is icon-only. */
  label: string
  className?: string
}

export function LanguageMenu<L extends string>({
  locales, current, nameOf, renderRow, label, className,
}: LanguageMenuProps<L>) {
  return (
    <IconMenu icon={<Languages size={16} aria-hidden="true" />} label={label} className={className}>
      {close => locales.map(l => {
        const isCurrent = l === current
        const row = menuRow(isCurrent ? 'active' : 'default', 'justify-between')

        if (isCurrent) {
          return (
            <span key={l} lang={l} aria-current="true" className={row}>
              <span>{nameOf(l)}</span>
              <Check size={14} className="shrink-0 text-brand-legible" aria-hidden="true" />
            </span>
          )
        }

        return (
          <span key={l}>
            {renderRow({ locale: l, className: row, close, children: <span>{nameOf(l)}</span> })}
          </span>
        )
      })}
    </IconMenu>
  )
}
