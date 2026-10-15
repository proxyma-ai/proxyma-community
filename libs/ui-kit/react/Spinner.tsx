import { cn } from './cn'

interface Props {
  className?: string
  /** A named size, or the side in pixels (the 12px of a tool row, the 22px of the quit screen). */
  size?: 'sm' | 'md' | 'lg' | number
  /**
   * `arc` (the default) is the loader. `spokes` is the KNOWLEDGE GRAPH at work - a different
   * silhouette on purpose, so a source building its graph is not read as one still embedding. It
   * was lucide's `Loader` with `animate-spin`, which the rule below forbids; drawn here it turns
   * about its own centre like the arc.
   */
  variant?: 'arc' | 'spokes'
}

const SIZE_PX = { sm: 16, md: 24, lg: 32 }

/**
 * The loader: three quarters of a circle, turning.
 *
 * THE ARC TURNS INSIDE THE DRAWING, since 2026-10-03; the icon's box does not move. It was lucide's
 * loader with `animate-spin` on the svg, which rotates the BOX - and a rotated box is its own
 * compositor layer, placed on whole pixels, about a center the browser works out from that layer.
 * When layer and box disagree by a fraction of a pixel the arc swings round a point beside its
 * own center, which on a 12px icon is plain to see; it was reported as "very often off center",
 * and an earlier `will-change` (proxyma-primitives.css) had only made it rarer. Here the path is
 * rotated about (12, 12) in the drawing's OWN coordinates - the center of the circle it is an arc
 * of - so there is no second opinion about where the middle is.
 *
 * Every spinning loader in the four apps is this component. A new one written as a lucide icon
 * with `animate-spin` is the old bug back.
 */
export function Spinner({ className, size = 'md', variant = 'arc' }: Props) {
  const side = typeof size === 'number' ? size : SIZE_PX[size]
  return (
    <svg
      width={side}
      height={side}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={cn('shrink-0 text-brand-legible', className)}
      aria-hidden="true"
    >
      {variant === 'arc' ? (
        // lucide's loader-circle (ISC): an arc of the circle centered on 12,12.
        <path className="spinner-arc" d="M21 12a9 9 0 1 1-6.219-8.56" />
      ) : (
        // lucide's loader (ISC): eight spokes about 12,12.
        <g className="spinner-arc">
          <path d="M12 2v4" /><path d="m16.2 7.8 2.9-2.9" /><path d="M18 12h4" /><path d="m16.2 16.2 2.9 2.9" />
          <path d="M12 18v4" /><path d="m4.9 19.1 2.9-2.9" /><path d="M2 12h4" /><path d="m4.9 4.9 2.9 2.9" />
        </g>
      )}
    </svg>
  )
}

/**
 * The refresh arrows, turning while what they started runs (a sync, a check for updates, a retry).
 *
 * Turned the way the Spinner's arc is, inside the drawing about (12, 12), and for the same reason:
 * lucide's RefreshCw with `animate-spin` rotated its box and was reported "easy to off-center
 * when rotating" (2026-10-07). At rest it is the plain icon, so the button reads the same.
 */
export function RefreshIcon({ size = 16, spinning = false, className }: { size?: number; spinning?: boolean; className?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={cn('shrink-0', className)}
      aria-hidden="true"
    >
      {/* lucide's refresh-cw (ISC), drawn about 12,12. */}
      <g className={spinning ? 'spinner-arc' : undefined}>
        <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" />
        <path d="M21 3v5h-5" />
        <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" />
        <path d="M8 16H3v5" />
      </g>
    </svg>
  )
}
