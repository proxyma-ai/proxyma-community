'use client'
import { useRef, type ReactNode } from 'react'
import { cn } from './cn'
import { ProgressBar } from './Meter'
import { Presence } from './Presence'
import { ProxymaIcon } from './ProxymaIcon'
import { useIdleRotation } from './useIdleRotation'

/**
 * THE DESKTOP SPLASH, INSIDE THE WINDOW - for Proxyma itself restarting, quitting and stopped.
 *
 * A CARD THE SIZE OF THE LAUNCHER'S SPLASH WINDOW, since 2026-10-10 (maintainer's request: "use the
 * same modal size as startup, don't show it the whole page"). It was the whole page for a day. It
 * draws what SplashWindow.java draws, where it draws it, so a restart reads as the same thing a cold
 * start shows - and on a restart the launcher's own splash is on screen beside it:
 *
 *   - 440 x 260 (W, H), the 14px radius (RADIUS), a 1px `line` border on `canvas`
 *   - the 72px mark centred 82px from the top (LOGO, LOGO_CY)
 *   - the name in the brand face, bold, 19px, tracking-wider, brand-legible, baseline at 160
 *     (WORDMARK_SIZE)
 *   - ONE status line: a phase in sentence case, 12px caption ink, baseline at 190
 *   - the 200 x 4 wave bar at 214 (BAR_W, BAR_H, BAR_Y) while something is under way
 *   - a second line under the bar, baseline at 242 (HINT_BASELINE): the launcher's "taking longer
 *     than usual" sentence, or what to do now that nothing is under way
 *
 * Change one and change the other. The positions are absolute for that reason: a flow would put the
 * lines wherever the fonts' line boxes fall, and the point is that they fall where the window's do.
 * On a screen narrower than the card it narrows and the lines stay centred.
 *
 * THE MARK TURNS while something is under way, at the header logo's idle speed (useIdleRotation)
 * - motion that says the app is alive, carrying no progress - and the bar runs beside it. Both STOP
 * when nothing is going on any more (Proxyma has stopped), where they would promise work that is not
 * happening; the launcher's splash stops its mark on an error for the same reason. Reduced motion:
 * the mark stands still and nothing fades.
 *
 * Presentational, like every screen here: the layer, the scrim, the focus trap and the words are the
 * app's (StatusLayer).
 */
export function SplashScreen({ turning, status, detail, productName = 'Proxyma', className }: {
  /** Something is under way: the mark turns and the bar runs. */
  turning: boolean
  /** The one line under the name - "Restarting", "Starting", "Proxyma has stopped". */
  status: ReactNode
  /** The line under the bar, faded in when it arrives. */
  detail?: ReactNode
  productName?: string
  className?: string
}) {
  return (
    <div className={cn('relative w-[440px] max-w-full h-[260px] rounded-3xl border border-line bg-canvas text-center animate-slide-up', className)}>
      <div className="absolute inset-x-0 top-[46px] flex justify-center">
        {turning ? <TurningMark /> : <ProxymaIcon size={MARK_SIZE} />}
      </div>
      {/* 19px is the launcher's WORDMARK_SIZE, not a step of the type scale - the name is drawn,
          not set. */}
      <div className="absolute inset-x-0 top-[141px] font-brand font-bold text-[19px] leading-6 tracking-wider text-brand-legible">
        {productName}
      </div>
      <div className="absolute inset-x-6 top-[176px] text-2xs leading-4 text-ink-caption truncate">{status}</div>
      <Presence show={turning} collapse={false} className="absolute inset-x-0 top-[214px] mx-auto w-[200px] max-w-[calc(100%-3rem)]">
        <ProgressBar size="xs" />
      </Presence>
      <div className="absolute inset-x-6 top-[228px] text-2xs leading-4 text-ink-caption">
        <Presence show={!!detail} collapse={false}>{detail}</Presence>
      </div>
    </div>
  )
}

const MARK_SIZE = 72

// Its own component so the rotation hook runs only while the mark turns - a hook cannot be called
// conditionally, and a still mark should not be driving a frame loop.
function TurningMark() {
  const ref = useRef<SVGSVGElement>(null)
  useIdleRotation(ref)
  return <ProxymaIcon ref={ref} size={MARK_SIZE} />
}
