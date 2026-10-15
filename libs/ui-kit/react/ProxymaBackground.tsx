import { cn } from './cn'

/**
 * The page background: a flat --canvas fill, fixed behind everything.
 *
 * It used to carry a low-opacity radial glow in --brand-solid, anchored top-centre. Removed on
 * 2026-09-26 with every other glow in the design system (maintainer's decision) - emphasis is a
 * border now, never light. Kept as a component rather than inlined at its twelve call sites,
 * because the canvas fill behind a page that scrolls past its content is still needed.
 */
export function ProxymaBackground({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={cn('fixed inset-0 pointer-events-none -z-20 bg-canvas', className)}
    />
  )
}
