import { cn } from './cn'

/**
 * The page background, fixed behind everything: the --canvas fill on a surface drawn without the
 * glass (the landing site), and the FIELD in an app shell - the wash of the brand's teals the glass
 * panes sit on (`.proxyma-background` in proxyma-primitives.css, since 2026-10-29). The app shell's
 * body is the field already; this keeps a page that paints its own background from hiding it.
 *
 * It used to carry a low-opacity radial glow in --brand-solid, anchored top-centre. Removed on
 * 2026-09-26 with every other glow in the design system (maintainer's decision) - emphasis is a
 * border now, never light. The field is the window's light, not emphasis on anything.
 */
export function ProxymaBackground({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={cn('fixed inset-0 pointer-events-none -z-20 proxyma-background', className)}
    />
  )
}
