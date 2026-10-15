import { useEffect, useMemo, useRef, useState } from 'react'
import { Plus } from 'lucide-react'
import { Button, FadeSwap, MenuEmpty, MenuGroup, MenuRow, Popover, Presence, SearchInput, Tooltip, cn } from '@/components/ui/kit'
import { SourceTypeMark } from '@/features/indexing/utils/sourceMarks'
import type { CatalogCategory, CatalogType } from '@/features/indexing/services/catalogApi'
import { groupTypesByCategory } from '@/features/indexing/utils/fieldForm'
import { useTranslation } from '@/i18n'
import { balanceColumns, filterGroups, switchedOffTypes } from '../utils/connectMenu'

// A search field over the menu once it holds more types than can be read at a glance. Below this
// the menu is a short list, and a field over it would only push the rows down.
const SEARCH_FROM = 9

// How many columns the window has room for: one on a phone, then two, three and four at the
// ladder's sm, lg and xl - each column an ordinary menu's width. The panel's width and the grid
// for each count, written out so Tailwind sees every class.
const COLUMN_QUERIES: [number, string][] = [[4, '(min-width: 1280px)'], [3, '(min-width: 1024px)'], [2, '(min-width: 640px)']]
const PANEL_WIDTH: Record<number, string> = { 1: 'w-screen', 2: 'w-[31rem]', 3: 'w-[46.5rem]', 4: 'w-[62rem]' }
const GRID: Record<number, string> = { 1: 'grid-cols-1', 2: 'grid-cols-2', 3: 'grid-cols-3', 4: 'grid-cols-4' }

function columnsNow(): number {
  return COLUMN_QUERIES.find(([, q]) => window.matchMedia(q).matches)?.[0] ?? 1
}

function useColumnCount(): number {
  const [count, setCount] = useState(columnsNow)
  useEffect(() => {
    const lists = COLUMN_QUERIES.map(([, q]) => window.matchMedia(q))
    const update = () => setCount(columnsNow())
    lists.forEach(l => l.addEventListener('change', update))
    return () => lists.forEach(l => l.removeEventListener('change', update))
  }, [])
  return count
}

// A group's height in rows, its caption counted as one: what balanceColumns weighs.
const groupHeight = (g: { types: unknown[] }) => g.types.length + 1

/**
 * THE CONNECT MENU: every source type this deployment offers, grouped by category in the catalog's
 * order, with a search field on top. Thirty-odd types in a dozen categories were one column of
 * forty rows in a 240px panel that scrolled - so from `sm` the groups sit side by side in columns,
 * balanced so no column runs long (four at 1280, where the whole catalog fits under the button),
 * and the field filters as you
 * type: by the start of any word of a type's names, in the reader's language or in English, accents
 * ignored (utils/connectMenu.ts).
 *
 * Under a mouse the field takes focus as the menu opens, so typing searches at once; ArrowDown goes
 * from it into the rows, Enter on it opens the first match. On a touch screen it does not, since
 * focusing it would put the on-screen keyboard over the menu. From the keyboard the menu's own
 * focus (the kit Popover, `useMenuFocus`) still applies once the rows have it.
 *
 * A locked entry - a type the license leaves out (Microsoft 365 on an Enterprise Server with no
 * license in force) - stays with its reason rather than being `disabled`, which would also kill its
 * tooltip and leave a greyed row with no stated reason. A type a deployment switch has turned off
 * (`offered: false`) has no row - nothing of it can be added - but is NAMED under the rows, with who
 * turns it on: Teams channels, Teams chats and Outlook each wait for a Super admin's switch.
 *
 * CONTROLLED because the empty list's "Add a source" opens it too.
 */
export function ConnectMenu({ types, categories, open, onOpenChange, onPick, filesTitle }: {
  types: CatalogType[]
  categories: CatalogCategory[]
  open: boolean
  onOpenChange: (open: boolean) => void
  onPick: (type: string) => void
  /** The Files entry's name, decided by the server: "Upload files" or "Local folder". */
  filesTitle: string
}) {
  const { t } = useTranslation()
  const [query, setQuery] = useState('')
  const groups = useMemo(() => groupTypesByCategory(types, categories), [types, categories])
  const offeredCount = groups.reduce((n, g) => n + g.types.length, 0)
  const searchable = offeredCount >= SEARCH_FROM
  const title = (d: CatalogType) => (d.type === 'files' ? filesTitle : t(d.noun))
  const shown = useMemo(
    () => filterGroups(
      groups,
      query,
      // Its names in both languages, so a reader of either finds it by the word they know.
      d => [d.type === 'files' ? filesTitle : t(d.noun), d.noun, t(d.nounPlural), d.nounPlural, t(d.displayName), d.displayName, d.type],
      g => [t(g.label), g.label],
    ),
    [groups, query, t, filesTitle],
  )
  // Named under the rows while the menu is not being searched (switchedOffTypes).
  const switchedOff = switchedOffTypes(types)
  const firstMatch = shown.find(g => g.types.some(d => d.licensed))?.types.find(d => d.licensed)
  const columnCount = useColumnCount()
  // Several columns only while every group is shown - the results of a search are one short list.
  const columns = searchable && !query.trim() ? columnCount : 1
  const starts = balanceColumns(shown.map(groupHeight), columns)
  const columnGroups = starts.map((start, i) => shown.slice(start, starts[i + 1] ?? shown.length))

  // Under a mouse the field takes focus once the panel is laid out and shown (a hidden field cannot
  // take it) - unless the keyboard opened the menu and its focus has already gone to a row.
  const searchRef = useRef<HTMLDivElement>(null)
  const [pointerFine] = useState(() => window.matchMedia('(hover: hover) and (pointer: fine)').matches)
  useEffect(() => {
    if (!open || !searchable || !pointerFine) return
    let frame = 0
    let tries = 0
    const attempt = () => {
      const input = searchRef.current?.querySelector('input')
      const panel = searchRef.current?.closest<HTMLElement>('[role="menu"]')
      // The Popover measures its panel before showing it, and until then it is `visibility: hidden` -
      // which the field takes a frame or two longer to lose, since the kit eases every control.
      if (!input || !panel || getComputedStyle(input).visibility === 'hidden') {
        if (++tries < 30) frame = requestAnimationFrame(attempt)
        return
      }
      const active = document.activeElement
      if (active && panel.contains(active)) return
      input.focus()
    }
    frame = requestAnimationFrame(attempt)
    return () => cancelAnimationFrame(frame)
  }, [open, searchable, pointerFine])

  const setOpen = (next: boolean) => {
    if (!next) setQuery('')
    onOpenChange(next)
  }
  const pick = (d: CatalogType, close: () => void) => {
    if (!d.licensed) return
    close()
    setQuery('')
    onPick(d.type)
  }

  const row = (d: CatalogType, close: () => void) => {
    const locked = !d.licensed
    const item = (
      <MenuRow
        key={d.type}
        // A type's mark: 16px, in the brand ink. sourceMarks.tsx owns the mapping.
        icon={<SourceTypeMark type={d.type} size={16} className="text-brand-legible" />}
        title={title(d)}
        tone={locked ? 'locked' : 'default'}
        trailing={locked ? 'lock' : undefined}
        onSelect={() => pick(d, close)}
      />
    )
    return locked
      ? <Tooltip key={d.type} label={t('{service} needs an Enterprise license.', { service: t(d.displayName) })} side="bottom" fullWidth>{item}</Tooltip>
      : item
  }

  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      placement="bottom-end"
      // One column on a phone (the screen's width less the panel's margin), then two, three and four
      // columns as the window widens - each about an ordinary menu's width, and at 1280x720 the whole
      // catalog fits under the button without scrolling.
      widthClass={searchable ? PANEL_WIDTH[columnCount] : 'w-60'}
      panelClassName={searchable ? 'max-h-[min(38rem,var(--float-room,38rem))]' : undefined}
      ariaLabel={t('Connect')}
      trigger={({ open: isOpen, toggle, ref }) => (
        <Button ref={ref} data-tour-id="tour-add-source" onClick={toggle} aria-haspopup="menu" aria-expanded={isOpen}>
          <Plus size={16} aria-hidden="true" />
          {t('Connect')}
        </Button>
      )}
    >
      {close => (
        <>
          {searchable && (
            // Pinned while the rows scroll under it; the panel's own solid fill behind it.
            <div
              ref={searchRef}
              className="sticky top-0 z-raised surface-float px-3 pt-3 pb-2 border-b border-line/45"
              onKeyDown={e => {
                // Home and End move the caret in the field. The arrows go to the first or last row -
                // by hand, since the menu's own keys would count the field's clear button as a row.
                if (e.key === 'Home' || e.key === 'End') e.stopPropagation()
                if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && e.target instanceof HTMLInputElement) {
                  const rows = e.currentTarget.closest('[role="menu"]')?.querySelectorAll<HTMLElement>('[role="menuitem"]:not([disabled])')
                  const target = rows && (e.key === 'ArrowDown' ? rows[0] : rows[rows.length - 1])
                  if (target) {
                    e.preventDefault()
                    e.stopPropagation()
                    target.focus()
                  }
                }
                if (e.key === 'Enter' && firstMatch && e.target instanceof HTMLInputElement) {
                  e.preventDefault()
                  pick(firstMatch, close)
                }
              }}
            >
              <SearchInput
                value={query}
                onChange={setQuery}
                placeholder={t('Search source types')}
                ariaLabel={t('Search source types')}
                clearLabel={t('Clear search')}
              />
            </div>
          )}
          {/* The rows and the no-match line swap by fading, not in one frame. */}
          <FadeSwap swapKey={shown.length === 0 ? 'none' : 'rows'}>
          {shown.length === 0 ? (
            <MenuGroup>
              <MenuEmpty>{t('No source type matches "{query}".', { query: query.trim() })}</MenuEmpty>
            </MenuGroup>
          ) : (
            <div className={cn('grid', GRID[columns])}>
              {columnGroups.map((col, ci) => (
                <div key={ci} className="min-w-0">
                  {col.map(g => (
                    // A caption over each category only once there is more than one. In columns
                    // the captions alone separate the groups - a rule under each would stop at a
                    // column's edge.
                    <MenuGroup
                      key={g.category}
                      label={shown.length > 1 ? t(g.label) : undefined}
                      className={cn(columns > 1 && 'border-b-0 first:pt-1 last:pb-1')}
                    >
                      {g.types.map(d => row(d, close))}
                    </MenuGroup>
                  ))}
                </div>
              ))}
            </div>
          )}
          </FadeSwap>
          <Presence show={switchedOff.length > 0 && !query.trim()}>
            <div className="border-t border-line/45">
              <MenuEmpty>
                {t('{names} appears here once an administrator turns it on in Settings > Network.|{names} appear here once an administrator turns them on in Settings > Network.', {
                  count: switchedOff.length,
                  names: switchedOff.map(d => t(d.displayName)).join(', '),
                })}
              </MenuEmpty>
            </div>
          </Presence>
        </>
      )}
    </Popover>
  )
}
