import { createContext, useContext, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { FieldSkeleton, List, ListRowSkeleton, SectionHeading } from '@/components/ui/kit'
import { useSettings } from '../hooks/useSettings'

// Primitives shared by the settings pages, lifted out of SettingsModal so that per-edition page
// modules can use them without importing the modal itself. That import would be a runtime cycle:
// the modal imports the edition module (via the @settings-pages alias), so the edition module
// must not import the modal back.
//
// Only genuinely shared, self-contained pieces belong here. Anything that knows what a particular
// tab looks like belongs in that tab's own file.

export interface PendingChange {
  prefix: string
  field: string
  value: string
}

export interface TabProps {
  trackChange: (prefix: string, field: string, value: string) => void
  pending: PendingChange[]
}

const TitleActionSlot = createContext<{ slot: HTMLElement | null; disabled: boolean }>({ slot: null, disabled: false })

/** Where the modal provides the title row's slot to the tab below it, with the read-only state:
 *  the slot is outside the tab's own <fieldset disabled>, so it carries one of its own. */
export const SettingsTitleSlot = TitleActionSlot

/** A tab's primary action, rendered on the Settings title row. Renders nothing until the slot exists. */
export function TitleAction({ children }: { children: ReactNode }) {
  const { slot, disabled } = useContext(TitleActionSlot)
  return slot
    ? createPortal(<fieldset disabled={disabled} className="contents">{children}</fieldset>, slot)
    : null
}

/**
 * A SIGNPOST: a section that is only its heading - a caption saying where something is, and the
 * one action that goes there ("Open Logs", "Replay walkthrough"). The kit `SettingsSection` keeps
 * its heading's bottom margin when it has no children, which left these with a gap under nothing;
 * this is the heading `flush`, in a section of its own so `.settings-sections` rules it off like
 * every other.
 */
export function SignpostSection({ title, caption, action }: {
  title: ReactNode
  caption: ReactNode
  action: ReactNode
}) {
  return (
    <section>
      <SectionHeading flush caption={caption} action={action}>{title}</SectionHeading>
    </section>
  )
}

export function TabFieldsSkeleton({ count = 4 }: { count?: number }) {
  return (
    <div className="space-y-6">
      {Array.from({ length: count }, (_, i) => <FieldSkeleton key={i} />)}
    </div>
  )
}

/**
 * A LIST loading: the kit's `ListRowSkeleton` - a name over a caption - in the kit `List`, divided,
 * so every Settings list loads with bones shaped like the rows that arrive, never field-shaped ones
 * and never "Loading..." on its own.
 */
export function ListSkeleton({ count = 3 }: { count?: number }) {
  return (
    <List>
      {Array.from({ length: count }, (_, i) => <ListRowSkeleton key={i} action={false} />)}
    </List>
  )
}

// The value a field should show right now: an unsaved edit if there is one, otherwise what the
// server holds, otherwise the supplied fallback. `enabled` defers the underlying settings fetch
// for sections that are collapsed until opened.
export function useResolved(
  prefix: string, field: string, pending: PendingChange[], fallback?: string, enabled = true,
): string {
  const { data } = useSettings(prefix, enabled)
  const pendingEntry = pending.find(p => p.prefix === prefix && p.field === field)
  if (pendingEntry) return pendingEntry.value
  const serverVal = data?.[field]
  if (serverVal != null && serverVal !== '') return String(serverVal)
  return fallback ?? ''
}
