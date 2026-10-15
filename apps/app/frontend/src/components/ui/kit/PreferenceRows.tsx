/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/PreferenceRows.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { Languages } from 'lucide-react'
import { APPEARANCE_PREFERENCES, type AppearancePreference } from './appearance'
import { AppearanceIcon } from './AppearanceMenu'
import { SubmenuRow, type SubmenuOptionRender } from './SubmenuRow'

/**
 * THE TWO PREFERENCE ROWS of every account or phone menu - Appearance and Language, each the
 * current value and a submenu of every choice (`SubmenuRow`). The app wrote them in
 * `PreferenceMenuRows.tsx` and the site again inline in its `HeaderMenu`, and the appearance names
 * were typed in four places; the consoles still carried them as two icon menus on the bar. The rows
 * take their state as props, so each app's adapter is its store and its `t()` and nothing else.
 */

/**
 * Appearance: System, Light, Dark and Proxyma, each with its glyph (Proxyma's is the mark).
 * Choosing keeps the submenu and the menu open - people flip between them to compare.
 */
export function AppearanceMenuRow({ title, names, current, onChoose, className }: {
  /** "Appearance", translated. */
  title: string
  /** Each preference's name, translated. */
  names: Record<AppearancePreference, string>
  current: AppearancePreference
  onChoose: (preference: AppearancePreference) => void
  className?: string
}) {
  return (
    <SubmenuRow
      icon={<AppearanceIcon preference={current} size={15} className="shrink-0" />}
      value={names[current]}
      valueText={names[current]}
      title={title}
      options={APPEARANCE_PREFERENCES.map(p => ({
        key: p,
        label: names[p],
        icon: <AppearanceIcon preference={p} size={15} className="shrink-0" />,
      }))}
      current={current}
      onPick={onChoose}
      closeOnPick={false}
      className={className}
    />
  )
}

/**
 * Language: every language by its own name ("Tieng Viet" written in Vietnamese), the current one
 * checked. A pick closes the submenu. Where a language is a URL (the site), `renderOption` draws each
 * row as the anchor to the same page in that language - a crawler follows a link and not a button.
 */
export function LanguageMenuRow<L extends string>({ title, locales, current, nameOf, onPick, renderOption, className }: {
  /** "Language", translated. */
  title: string
  locales: readonly L[]
  current: L
  /** A language's name in that language. */
  nameOf: (locale: L) => string
  onPick?: (locale: L) => void
  renderOption?: (render: SubmenuOptionRender<L>) => React.ReactNode
  className?: string
}) {
  return (
    <SubmenuRow
      icon={<Languages size={15} className="shrink-0" aria-hidden="true" />}
      value={nameOf(current)}
      valueText={nameOf(current)}
      valueLang={current}
      title={title}
      options={locales.map(l => ({ key: l, label: nameOf(l), lang: l }))}
      current={current}
      onPick={onPick}
      closeOnPick
      renderOption={renderOption}
      className={className}
    />
  )
}
