import { cn } from './cn'

/**
 * A skeleton must mirror the real layout it stands in for, at the same sizes. A generic
 * grey block that does not match what arrives reads as a glitch rather than as loading,
 * which is why the table's skeleton rows (DataTable's TableSkeletonRows) are shaped per column.
 */
export function Skeleton({ className }: { className?: string }) {
  return <span className={cn('block rounded-sm bg-ink-caption/10 animate-skeleton', className)} />
}

/**
 * THE KIT'S ROWS AND FIELDS, LOADING - each on the geometry of the part it stands in for, so the
 * real one arrives without a jump. They were the app's (`components/ui/Skeleton`), drawn to the
 * Settings page of the day; Settings is the kit's now and so are its placeholders.
 */

/** A `ListRow`: title over caption, `py-3`, an action-sized block on the title's line. */
export function ListRowSkeleton({ action = true }: { action?: boolean }) {
  return (
    <div className="flex items-start justify-between gap-3 py-3" aria-hidden="true">
      <div className="flex-1 min-w-0 space-y-1.5">
        <Skeleton className="h-3.5 w-2/5" />
        <Skeleton className="h-3 w-3/5" />
      </div>
      {action && <Skeleton className="h-4 w-12 shrink-0" />}
    </div>
  )
}

/** A `SwitchRow`: the label block and a 44x24 switch on its first line. */
export function SwitchRowSkeleton() {
  return (
    <div className="flex items-start justify-between gap-3 py-3" aria-hidden="true">
      <div className="flex-1 min-w-0 space-y-1.5">
        <Skeleton className="h-3.5 w-32" />
        <Skeleton className="h-3 w-48" />
      </div>
      <Skeleton className="h-6 w-11 rounded-full shrink-0" />
    </div>
  )
}

/** A `Field`: its 13px name, `mb-2`, then a control's 36px box. */
export function FieldSkeleton({ className }: { className?: string }) {
  return (
    <div className={cn('flex flex-col', className)} aria-hidden="true">
      {/* The label's 20px line and the field's 32px, so nothing moves when the field arrives. */}
      <div className="h-5 mb-2 flex items-center"><Skeleton className="h-3.5 w-24" /></div>
      <Skeleton className="h-8 w-full max-w-sm rounded-md" />
    </div>
  )
}

/** A `RadioGroup`: its name, then `count` round marks with their words. */
export function RadioGroupSkeleton({ count = 3, horizontal }: { count?: number; horizontal?: boolean }) {
  return (
    <div className="space-y-3" aria-hidden="true">
      <Skeleton className="h-3.5 w-28" />
      <div className={horizontal ? 'flex flex-wrap gap-4' : 'space-y-2.5'}>
        {Array.from({ length: count }, (_, i) => (
          <div key={i} className="flex items-center gap-2.5">
            <Skeleton className="h-4 w-4 rounded-full" />
            <Skeleton className="h-3 w-20" />
          </div>
        ))}
      </div>
    </div>
  )
}
