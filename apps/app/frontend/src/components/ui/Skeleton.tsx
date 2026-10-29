import {
  AuthLayout, Card, ChatMessageSkeleton, FieldSkeleton, Page, SOURCE_LIST_BOX, Skeleton, SourceRowSkeleton, StatGrid, StatTile, TABLE_CLASS,
  THREAD_COLUMN, TableCard, TableSkeletonRows, cn,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'

// The base bar comes from the shared kit, so a page skeleton and a table skeleton in two different
// Proxyma surfaces animate at the same rhythm; everything below it is product-specific, mirroring
// a real layout this app has and the consoles do not. The rows and fields of Settings - a list
// row, a switch row, a field, a radio group, a source's item - are the kit's own skeletons now.
//
// This file's own base used to be `animate-pulse rounded-md bg-ink-disabled/15` - Tailwind's
// generic pulse at a different radius and a different grey from the consoles' bar. That is
// the drift the kit exists to remove.

// A conversation loading: a question, its answer and the next question, on the kit's
// ChatMessageSkeleton - the message's own geometry (a short right-aligned line, then the column's
// full width), so the thread does not jump when the words arrive. It was an app-local bar pair
// `max-w-md` wide for both roles, matching neither. Announced once, as busy.
export function ChatThreadSkeleton() {
  const { t } = useTranslation()
  return (
    <div role="status" aria-busy="true" className={cn(THREAD_COLUMN, 'space-y-6')}>
      <span className="sr-only">{t('Loading conversation')}</span>
      <ChatMessageSkeleton role="user" />
      <ChatMessageSkeleton role="assistant" />
      <ChatMessageSkeleton role="user" />
    </div>
  )
}

// Route-level Suspense fallback for /shared/:token - a read-only thread with no composer, at the
// thread's own gutters. It fell back to the new-chat screen's skeleton (a greeting and a composer),
// which turned into something else entirely on arrival.
export function SharedConversationSkeleton() {
  return (
    <div className="flex flex-col h-full overflow-hidden py-6 short:py-3 px-2 sm:px-6">
      <ChatThreadSkeleton />
    </div>
  )
}

// Mirrors AdminPage BackupsTab's schedule form (frequency/time/growth/backup-now rows, then a
// status block) - replaces a single flat rectangle that gave no hint of the actual form shape.
export function BackupScheduleSkeleton() {
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <Skeleton className="h-3 w-24" />
        <Skeleton className="h-8 w-40 rounded-md" />
      </div>
      <div className="flex items-center gap-3">
        <Skeleton className="h-3 w-10" />
        <div className="flex items-center gap-2">
          <Skeleton className="h-8 w-20 rounded-md" />
          <Skeleton className="h-8 w-20 rounded-md" />
          <Skeleton className="h-8 w-20 rounded-md" />
        </div>
      </div>
      <div className="flex items-center gap-3 pt-3 border-t border-line/30">
        <Skeleton className="h-3 w-20" />
        <Skeleton className="h-6 w-11 rounded-full" />
      </div>
      <div className="flex items-center gap-3 pt-3 border-t border-line/30">
        <Skeleton className="h-3 w-24" />
        <Skeleton className="h-3 w-28" />
      </div>
      <div className="pt-3 border-t border-line/30 space-y-2">
        <Skeleton className="h-2.5 w-24" />
        <Skeleton className="h-3.5 w-56" />
      </div>
    </div>
  )
}

// Mirrors AdminPage MaintenanceTab's announcement banner form (toggle+label row, reason input,
// then a 2-column grid of two datetime pickers).
export function AnnouncementBannerSkeleton() {
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3">
        <Skeleton className="h-6 w-11 rounded-full" />
        <Skeleton className="h-3.5 w-32" />
      </div>
      <div className="flex flex-col gap-2">
        <Skeleton className="h-3 w-16" />
        <Skeleton className="h-9 w-full rounded-md" />
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div className="flex flex-col gap-2">
          <Skeleton className="h-3 w-24" />
          <Skeleton className="h-9 w-full rounded-md" />
        </div>
        <div className="flex flex-col gap-2">
          <Skeleton className="h-3 w-24" />
          <Skeleton className="h-9 w-full rounded-md" />
        </div>
      </div>
    </div>
  )
}

// Mirrors AdminPage MaintenanceTab's hard-maintenance status block (status line, conditional
// reason+datetime grid, then a full-width action button).
export function HardMaintenanceStatusSkeleton() {
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Skeleton className="h-3 w-14" />
        <Skeleton className="h-3.5 w-16" />
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div className="flex flex-col gap-2">
          <Skeleton className="h-3 w-16" />
          <Skeleton className="h-9 w-full rounded-md" />
        </div>
        <div className="flex flex-col gap-2">
          <Skeleton className="h-3 w-24" />
          <Skeleton className="h-9 w-full rounded-md" />
        </div>
      </div>
      <Skeleton className="h-9 w-48 rounded-md" />
    </div>
  )
}

// N word-shaped placeholders over the row's hairline, mirroring the kit's SubNavTabs - used by the
// per-page route skeletons below so the tab row isn't just absent during the code-split
// chunk-load window.
function TabRowSkeleton({ count }: { count: number }) {
  return (
    <div className="flex items-center gap-6 h-9 border-b border-line">
      {Array.from({ length: count }, (_, i) => <Skeleton key={i} className="h-3.5 w-16" />)}
    </div>
  )
}

// A page title (the kit's 18px Title) and, optionally, the caption line under it.
function PageTitleSkeleton({ width = 'w-40', meta }: { width?: string; meta?: boolean }) {
  return (
    <div className="space-y-2">
      <Skeleton className={cn('h-6', width)} />
      {meta && (
        <div className="flex gap-4">
          <Skeleton className="h-3 w-20" />
          <Skeleton className="h-3 w-24" />
        </div>
      )}
    </div>
  )
}

// Route-level Suspense fallback for /chat - mirrors ChatPage's new-chat screen: the hero (the
// Proxyma lockup, since 2026-10-15), the composer (the kit's ComposerShell, a step rounder than a
// panel, its editor line over a toolbar) and the one line of start links under it. No pill input
// and no row of suggestion chips - the page lost both (2026-09-28 and the review's F27), and a
// skeleton that still drew them turned into something else on arrival.
export function ChatPageSkeleton() {
  return (
    <div className="flex flex-col h-full items-center justify-center px-2 sm:px-6" aria-hidden="true">
      <div className={cn(THREAD_COLUMN, 'flex flex-col items-center')}>
        <div className="flex items-center gap-3 mb-10">
          <Skeleton className="h-14 w-14 rounded-full" />
          <Skeleton className="h-8 w-44" />
        </div>
        <div className="w-full rounded-lg border border-line/50 px-4 pt-3 pb-2 mb-8 space-y-4">
          <Skeleton className="h-4 w-1/2" />
          <div className="flex items-center justify-between">
            <Skeleton className="h-6 w-6 rounded-md" />
            <Skeleton className="h-8 w-8 rounded-md" />
          </div>
        </div>
        <div className="flex flex-wrap justify-start sm:justify-center gap-4 self-stretch sm:self-auto">
          <Skeleton className="h-3 w-24" />
          <Skeleton className="h-3 w-12" />
          <Skeleton className="h-3 w-16" />
        </div>
      </div>
    </div>
  )
}

// The Sources page's filter chips while the summary loads - the row keeps its height, so the list
// under it does not jump. The page draws it too, so the route's skeleton and the page's agree.
export function FilterChipsSkeleton() {
  return (
    // The filters' well: 40px, a 12px track (FilterChips).
    <div className="inline-flex items-center gap-3 h-10 px-3 rounded-lg bg-surface-alt shadow-well animate-fade-in" aria-hidden="true">
      {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-3.5 w-16" />)}
    </div>
  )
}

// The source list's rows on the real rows' columns and padding (the kit's SourceRowSkeleton), for
// the route's skeleton and for each of the page's own loading states.
export function SourceListSkeleton() {
  return (
    <div className={SOURCE_LIST_BOX} aria-hidden="true">
      {Array.from({ length: 6 }, (_, i) => <SourceRowSkeleton key={i} />)}
    </div>
  )
}

// Route-level Suspense fallback for /sources - mirrors SourcesPage on the kit: the page title with
// its counts beside the Connect button, the filter chips, the search field, then the list's rows
// (the kit's own SourceRowSkeleton, on the real rows' columns and padding).
export function SourcesPageSkeleton() {
  return (
    <Page>
      <div className="flex items-start justify-between gap-3">
        <PageTitleSkeleton meta />
        <Skeleton className="h-7 w-28 rounded-md" />
      </div>
      <div className="space-y-3">
        <FilterChipsSkeleton />
        <Skeleton className="h-9 w-full sm:max-w-sm rounded-md" />
      </div>
      <SourceListSkeleton />
    </Page>
  )
}

// Route-level Suspense fallback for /admin - mirrors AdminPage's title, tab row and the Overview
// tab's stat tiles (the kit's StatTile in its loading state, in the kit's StatGrid).
export function AdminPageSkeleton() {
  return (
    <Page>
      <PageTitleSkeleton width="w-48" />
      <TabRowSkeleton count={5} />
      <StatGrid className="pt-2">
        {Array.from({ length: 4 }, (_, i) => (
          <StatTile key={i} label={<Skeleton className="h-3 w-20" />} value={null} loading />
        ))}
      </StatGrid>
    </Page>
  )
}

// The key-accounts list's table columns, as KeyAccountsPage draws them - the narrow screen's
// hidden columns hidden here too.
const KEY_ACCOUNT_COLUMNS = [
  'w-40', 'w-16', { width: 'w-14', cellClassName: 'hidden md:table-cell' }, 'w-12', 'w-16', 'w-14', 'w-8',
  { width: 'w-24', cellClassName: 'hidden lg:table-cell' }, { width: 'w-20', cellClassName: 'hidden md:table-cell' },
]

// Key accounts (KeyAccountsPage): the title with its count and New account, the filter bar - tier
// chips over the goal select and the switches - and the table's rows in their card.
export function KeyAccountsPageSkeleton() {
  return (
    <Page>
      <div className="flex items-start justify-between gap-3">
        <PageTitleSkeleton width="w-40" meta />
        <Skeleton className="h-7 w-28 rounded-md" />
      </div>
      <div className="space-y-3">
        <div className="flex items-center gap-2 h-8">
          {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-6 w-20 rounded-md" />)}
        </div>
        <div className="flex flex-col sm:flex-row sm:items-center gap-3">
          <Skeleton className="h-8 w-40 rounded-md" />
          <div className="flex items-center gap-2 sm:ml-auto">
            <Skeleton className="h-5 w-9 rounded-full" />
            <Skeleton className="h-3 w-32" />
          </div>
        </div>
      </div>
      <TableCard flush>
        <table className={TABLE_CLASS} aria-hidden="true">
          <tbody><TableSkeletonRows rows={4} columns={KEY_ACCOUNT_COLUMNS} /></tbody>
        </table>
      </TableCard>
    </Page>
  )
}

// One key account (KeyAccountPage), the ONE skeleton of its load - the route's fallback and the page's
// own while the account is read: the way back, the name over its line with the actions beside it,
// the review bar, the nine tabs and the Overview's headline numbers.
export function KeyAccountPageSkeleton() {
  return (
    <Page>
      <div className="space-y-3">
        <Skeleton className="h-3 w-24" />
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <PageTitleSkeleton width="w-64" meta />
          <div className="flex items-center gap-2">
            <Skeleton className="h-8 w-20 rounded-md" />
            <Skeleton className="h-8 w-24 rounded-md" />
            <Skeleton className="h-9 w-9 rounded-md" />
          </div>
        </div>
      </div>
      <div className="space-y-3">
        <div className="flex items-center gap-3">
          <Skeleton className="h-8 w-48 rounded-md" />
          <Skeleton className="h-8 w-28 rounded-md" />
        </div>
        <TabRowSkeleton count={9} />
      </div>
      <StatGrid columns={3}>
        {Array.from({ length: 6 }, (_, i) => (
          <StatTile key={i} label={<Skeleton className="h-3 w-20" />} value={null} loading />
        ))}
      </StatGrid>
    </Page>
  )
}

// Full-page skeleton, the generic Suspense fallback for a route with no dedicated one (the dev-only
// kit gallery): a title and a list of kit cards.
export function PageSkeleton() {
  return (
    <Page>
      <PageTitleSkeleton width="w-48" meta />
      <div className="space-y-3">
        {Array.from({ length: 4 }, (_, i) => (
          <Card key={i} className="space-y-2">
            <Skeleton className="h-4 w-48" />
            <div className="flex items-center gap-3">
              <Skeleton className="h-3 w-20" />
              <Skeleton className="h-3 w-24" />
            </div>
          </Card>
        ))}
      </div>
    </Page>
  )
}

// The forgot- and reset-password routes, whose chunks load on demand: the kit's AuthLayout card
// with its mark, title and two fields - the shape that page has, not a page of cards.
export function AuthPageSkeleton() {
  return (
    <AuthLayout logo={<Skeleton className="h-8 w-36" />} title={<Skeleton className="h-6 w-48 mx-auto" />}>
      <div className="space-y-4" aria-hidden="true">
        <FieldSkeleton />
        <Skeleton className="h-9 w-full rounded-md" />
      </div>
    </AuthLayout>
  )
}
