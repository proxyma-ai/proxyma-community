/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/PreferenceRows.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ComponentType, ReactNode } from 'react'
import { Check, Languages } from 'lucide-react'
import {
  ACCENT_PREFERENCES, ACCENT_SWATCH, APPEARANCE_PREFERENCES, BACKDROP_PREFERENCES,
  type AccentPreference, type AppearancePreference, type BackdropPreference,
} from './appearance'
import { AppearanceIcon } from './AppearanceMenu'
import { SubmenuRow, type SubmenuOptionRender } from './SubmenuRow'
import { MenuSurface, menuRow } from './menu'
import { Tooltip } from './Tooltip'
import { sidebarRow } from './GlassFrame'
import { cn } from './cn'

/**
 * THE TWO PREFERENCE ROWS of every account or phone menu - Appearance and Language, each the
 * current value and a submenu of every choice (`SubmenuRow`). The app wrote them in
 * `PreferenceMenuRows.tsx` and the site again inline in its `HeaderMenu`, and the appearance names
 * were typed in four places; the consoles still carried them as two icon menus on the bar. The rows
 * take their state as props, so each app's adapter is its store and its `t()` and nothing else.
 */

/**
 * Appearance: System, Light and Dark, each with its glyph.
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

/**
 * A row of the glass shell's sidebar foot that opens a short list ABOVE it - one choice of a
 * preference, the current one checked (the maintainer, 2026-10-30: the account menu holds only the
 * account; what is not about the account is a row of the panel). Folded, the icon alone, named by
 * its tooltip. The app wrote it in its SidebarPreferences; both consoles take the same rows, so it
 * is the kit's, its state as props.
 */
export function SidebarChoiceRow<T extends string>({ icon: Icon, title, choices, current, nameOf, iconOf, onChoose, closeOnChoose, folded }: {
  icon: ComponentType<{ size?: number | string; className?: string }>
  /** The preference's name, translated - the row's words, and its tooltip folded. */
  title: string
  choices: readonly T[]
  current: T
  /** A choice's name, shown at the right of the row and in the list. */
  nameOf: (choice: T) => string
  /** A glyph before each choice in the list. */
  iconOf?: (choice: T) => ReactNode
  onChoose: (choice: T) => void
  /** Appearance stays open, so the reader can compare; a language closes it. */
  closeOnChoose: boolean
  folded: boolean
}) {
  const menu = (
    <MenuSurface
      className={folded ? undefined : 'w-full'}
      panelClassName="w-56"
      side="top"
      role="menu"
      ariaLabel={title}
      trigger={({ open, toggle, controls }) => (
        <button type="button" onClick={toggle} aria-haspopup="menu" aria-expanded={open} aria-controls={controls}
          aria-label={folded ? `${title}: ${nameOf(current)}` : undefined} className={sidebarRow({ folded })}>
          <Icon size={16} className="shrink-0" aria-hidden="true" />
          {!folded && (
            <>
              <span className="flex-1 min-w-0 truncate text-left">{title}</span>
              <span className="shrink-0 text-xs text-ink-caption">{nameOf(current)}</span>
            </>
          )}
        </button>
      )}
    >
      {close => (
        <div className="py-1">
          {choices.map(c => (
            <button key={c} type="button" role="menuitemradio" aria-checked={c === current}
              onClick={() => { onChoose(c); if (closeOnChoose) close() }} className={menuRow()}>
              {iconOf?.(c)}
              <span className="flex-1 min-w-0 truncate text-left">{nameOf(c)}</span>
              <Check size={14} aria-hidden="true" className={cn('shrink-0', c === current ? 'text-brand-legible' : 'invisible')} />
            </button>
          ))}
        </div>
      )}
    </MenuSurface>
  )
  return folded ? <Tooltip label={title}>{menu}</Tooltip> : menu
}

/**
 * The background and the accent beside the appearance (appearance.ts `applyLook`), offered in the same
 * list when an app passes them: the app does, the consoles do not yet.
 */
export interface LookChoices {
  /** "Background", translated. */
  backdropTitle: string
  backdropNames: Record<BackdropPreference, string>
  backdrop: BackdropPreference
  onBackdrop: (backdrop: BackdropPreference) => void
  /** "Accent color", translated. */
  accentTitle: string
  accentNames: Record<AccentPreference, string>
  accent: AccentPreference
  onAccent: (accent: AccentPreference) => void
}

/**
 * Appearance as a row of the sidebar's foot: System, Light and Dark, each with its glyph; a choice keeps
 * the list open. With `look`, the same list goes on to the background (Flat, Gradient) and a row of the
 * accent colors as swatches (the maintainer, 2026-11-09: "Make an appearance setting so we can choose.
 * Also, allow the user to select the accent color as well") - every choice keeps it open, to compare.
 */
export function SidebarAppearanceRow({ title, names, current, onChoose, folded, look }: {
  /** "Appearance", translated. */
  title: string
  /** Each preference's name, translated. */
  names: Record<AppearancePreference, string>
  current: AppearancePreference
  onChoose: (preference: AppearancePreference) => void
  folded: boolean
  look?: LookChoices
}) {
  const CurrentIcon = ({ size, className }: { size?: number | string; className?: string }) =>
    <AppearanceIcon preference={current} size={typeof size === 'number' ? size : 16} className={className} />
  if (!look) {
    return (
      <SidebarChoiceRow
        icon={CurrentIcon}
        title={title}
        choices={APPEARANCE_PREFERENCES}
        current={current}
        nameOf={c => names[c]}
        iconOf={c => <AppearanceIcon preference={c} size={15} className="shrink-0" />}
        onChoose={onChoose}
        closeOnChoose={false}
        folded={folded}
      />
    )
  }
  const heading = 'px-3 pt-2 pb-1 text-xs font-medium text-ink-caption'
  const check = (on: boolean) =>
    <Check size={14} aria-hidden="true" className={cn('shrink-0', on ? 'text-brand-legible' : 'invisible')} />
  const menu = (
    <MenuSurface
      className={folded ? undefined : 'w-full'}
      panelClassName="w-64"
      side="top"
      role="menu"
      ariaLabel={title}
      trigger={({ open, toggle, controls }) => (
        <button type="button" onClick={toggle} aria-haspopup="menu" aria-expanded={open} aria-controls={controls}
          aria-label={folded ? `${title}: ${names[current]}` : undefined} className={sidebarRow({ folded })}>
          <CurrentIcon size={16} className="shrink-0" />
          {!folded && (
            <>
              <span className="flex-1 min-w-0 truncate text-left">{title}</span>
              <span className="shrink-0 text-xs text-ink-caption">{names[current]}</span>
            </>
          )}
        </button>
      )}
    >
      {() => (
        <div className="py-1">
          <div role="group" aria-label={title}>
            {APPEARANCE_PREFERENCES.map(c => (
              <button key={c} type="button" role="menuitemradio" aria-checked={c === current}
                onClick={() => onChoose(c)} className={menuRow()}>
                <AppearanceIcon preference={c} size={15} className="shrink-0" />
                <span className="flex-1 min-w-0 truncate text-left">{names[c]}</span>
                {check(c === current)}
              </button>
            ))}
          </div>
          <div role="group" aria-label={look.backdropTitle} className="mt-1 border-t border-line/60">
            <p className={heading} aria-hidden="true">{look.backdropTitle}</p>
            {BACKDROP_PREFERENCES.map(b => (
              <button key={b} type="button" role="menuitemradio" aria-checked={b === look.backdrop}
                onClick={() => look.onBackdrop(b)} className={menuRow()}>
                <span aria-hidden="true" className="h-[15px] w-[15px] shrink-0 rounded-[4px] border border-line"
                  style={{ background: b === 'gradient'
                    ? 'linear-gradient(to top right, rgb(7 8 9), rgb(var(--brand-accent)))'
                    : 'rgb(var(--surface-alt))' }} />
                <span className="flex-1 min-w-0 truncate text-left">{look.backdropNames[b]}</span>
                {check(b === look.backdrop)}
              </button>
            ))}
          </div>
          <div role="group" aria-label={look.accentTitle} className="mt-1 border-t border-line/60">
            <p className={heading} aria-hidden="true">{look.accentTitle}</p>
            <div className="flex items-center gap-2 px-3 pb-2 pt-0.5">
              {ACCENT_PREFERENCES.map(a => (
                <Tooltip key={a} label={look.accentNames[a]}>
                  <button type="button" role="menuitemradio" aria-checked={a === look.accent} aria-label={look.accentNames[a]}
                    onClick={() => look.onAccent(a)}
                    // Square, with the kit's small corner - never a pill or a dot - and the chosen one
                    // outlined clear of its color, which reads on either appearance.
                    className={cn('h-6 w-6 shrink-0 rounded-md focus-ring transition-[outline-color] duration-fast ease-out',
                      'outline outline-2 outline-offset-2',
                      a === look.accent ? 'outline-ink-primary' : 'outline-transparent mouse:hover:outline-line')}
                    style={{ backgroundColor: ACCENT_SWATCH[a] }} />
                </Tooltip>
              ))}
            </div>
          </div>
        </div>
      )}
    </MenuSurface>
  )
  return folded ? <Tooltip label={title}>{menu}</Tooltip> : menu
}

/** Language as a row of the sidebar's foot: every language by its own name; a pick closes the list. */
export function SidebarLanguageRow<L extends string>({ title, locales, current, nameOf, onPick, folded }: {
  /** "Language", translated. */
  title: string
  locales: readonly L[]
  current: L
  /** A language's name in that language. */
  nameOf: (locale: L) => string
  onPick: (locale: L) => void
  folded: boolean
}) {
  return (
    <SidebarChoiceRow<L>
      icon={Languages}
      title={title}
      choices={locales}
      current={current}
      nameOf={nameOf}
      onChoose={onPick}
      closeOnChoose
      folded={folded}
    />
  )
}
