import type { ReactNode } from 'react'
import { cn } from './cn'
import { ProxymaIcon } from './ProxymaIcon'

export interface LogoProps {
  /** Forwarded to the mark so a header can drive its idle rotation. */
  iconRef?: React.Ref<SVGSVGElement>
  size?: number
  /**
   * Renders the mark alone, with no wordmark beside it - for surfaces that already name the
   * product in their own copy (an onboarding greeting, an auth card whose heading is the
   * product name), where the wordmark would read as a duplicate.
   */
  markOnly?: boolean
  /** Defaults to the product name; the app passes the name its server reports. */
  productName?: string
  /**
   * A second line UNDER the wordmark - a console's name, the site's tagline. The two lines are
   * one block centred on the mark, so the pair reads as a lockup rather than as a label beside
   * a logo. Since 2026-09-24; the consoles used to put their name after a divider.
   */
  subtitle?: ReactNode
  /**
   * The name UNDER the mark, both centered, instead of beside it. Only the Community new chat's
   * hero (ChatStartHero) stacks, since 2026-10-15 (maintainer's request); everywhere else the
   * name sits beside the mark.
   */
  stacked?: boolean
  /** Classes for the subtitle line, e.g. `hidden sm:block` where the bar is too narrow for it. */
  subtitleClassName?: string
  /** Names a mark-only logo that stands for the product by itself (an image with no words). */
  ariaLabel?: string
  className?: string
}

/**
 * Mark + Orbitron wordmark. LOGO_HEIGHT is normalised across every surface deliberately:
 * the logo is the first thing a user sees, and a console whose logo is two pixels off reads
 * as a different application rather than the same product's admin surface.
 *
 * THE LOCKUP IS ONE BOX, since 2026-10-14: an inline-flex row whose gap is a quarter of the mark,
 * rounded to the spacing scale (8px beside the 28px header mark, 10px beside the footer's 40px).
 * It was a Fragment, so the gap was whatever the parent set - 8, 10 and 24px in three places. The
 * rules the guideline states: clear space round the lockup of at least half the mark's height;
 * the mark never under 16px, the lockup never under a 24px mark; the wordmark always "Proxyma"
 * in Orbitron 700 at tracking 0.05em in `brand-legible` ink - never upper case, never another face.
 *
 * `className` reaches the MARK (a turning mark in Settings > About sizes it with it); a mark-only
 * logo that stands for the product by itself takes `ariaLabel`, and is decorative without one.
 */
const LOGO_HEIGHT = 28

// THE NAME GROWS WITH A HERO-SIZED MARK, since 2026-10-15 (the new chat opens on the lockup,
// maintainer's request). Up to a 44px mark - the header's 28, the footer's 40 - the wordmark stays
// at 16px, as it always was. From 48px it is sized with the mark, at about the header's own ratio
// (16 beside 28), on the type scale: 29px beside 48-56, 37px beside anything larger.
function wordmarkSize(mark: number): string {
  if (mark < 48) return 'text-base'
  if (mark <= 56) return 'text-3xl'
  return 'text-4xl'
}

// THE STACKED LOCKUP'S NAME is a step under the side-by-side one: under the mark it is measured
// against the mark's WIDTH, and at the side-by-side size "Proxyma" ran to three marks wide and
// buried the mark. 25px under a 64px mark, 29px under anything larger; the gap between them is a
// fifth of the mark, so the pair reads as one sign rather than a picture with a caption.
function stackedWordmarkSize(mark: number): string {
  if (mark < 48) return 'text-base'
  if (mark <= 64) return 'text-2xl'
  return 'text-3xl'
}

function stackGap(mark: number): string {
  if (mark <= 32) return 'gap-2'
  if (mark <= 56) return 'gap-3'
  return 'gap-4'
}

function lockupGap(mark: number): string {
  if (mark <= 20) return 'gap-1.5'
  if (mark <= 32) return 'gap-2'
  if (mark <= 44) return 'gap-2.5'
  return 'gap-3'
}

export function Logo({ iconRef, size, markOnly = false, productName = 'Proxyma', subtitle, stacked = false, subtitleClassName, ariaLabel, className }: LogoProps) {
  const mark = size ?? LOGO_HEIGHT
  if (markOnly) {
    const icon = <ProxymaIcon ref={iconRef} size={mark} className={className} />
    return ariaLabel ? <span role="img" aria-label={ariaLabel} className="inline-flex shrink-0">{icon}</span> : icon
  }

  if (stacked) {
    // leading-none, so the gap is the whole of the space between the mark and the name's glyphs.
    return (
      <span className={cn('inline-flex flex-col items-center shrink-0 min-w-0 text-center', stackGap(mark))}>
        <ProxymaIcon ref={iconRef} size={mark} className={className} />
        <span className={cn('font-brand font-bold text-brand-legible tracking-wider leading-none', stackedWordmarkSize(mark))}>
          {productName}
        </span>
        {subtitle && (
          <span className={cn('text-xs text-ink-caption leading-none whitespace-nowrap', subtitleClassName)}>{subtitle}</span>
        )}
      </span>
    )
  }

  return (
    <span className={cn('inline-flex items-center shrink-0 min-w-0', lockupGap(mark))}>
      <ProxymaIcon ref={iconRef} size={mark} className={className} />
      {/* text-brand-legible, not text-brand-solid: the wordmark is TEXT sitting on the
          header's own surface, so it needs the per-mode legibility tuning that brand-solid
          deliberately skips. */}
      {subtitle ? (
        // leading-none on both lines, so the block's height is the two glyph boxes plus the gap
        // and its centre falls on the mark's centre - a default line height pads each line
        // unevenly and pushes the pair visibly low beside a 28px mark.
        <span className="flex flex-col justify-center gap-1 min-w-0">
          <span className={cn('font-brand font-bold text-brand-legible tracking-wider leading-none', wordmarkSize(mark))}>
            {productName}
          </span>
          <span className={cn('text-2xs text-ink-caption leading-none whitespace-nowrap', subtitleClassName)}>
            {subtitle}
          </span>
        </span>
      ) : (
        <span className={cn('font-brand font-bold text-brand-legible tracking-wider', wordmarkSize(mark))}>
          {productName}
        </span>
      )}
    </span>
  )
}
