/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Badge.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { cn } from './cn'
import { EmptyValue } from './KeyValueList'

interface Props {
  label: string
  variant?: 'brand' | 'featured' | 'electric' | 'success' | 'warning' | 'danger' | 'neutral' | 'outline'
  /** For `outline`: a fact that is bad news (a mood of "bad", a revoked key) takes `danger` ink. */
  tone?: 'neutral' | 'danger' | 'warning' | 'brand'
  /** `sm` (20px) sits inside a 14px title line without four overrides; `md` is 24px. */
  size?: 'sm' | 'md'
  /** A count or figure: medium weight, digits in the readout face. */
  numeric?: boolean
  className?: string
  /** Leading slot - a status dot or a small icon. Rendered before the label. */
  children?: React.ReactNode
}

/**
 * 12px semibold in the label's OWN CASE (the `xs` step, the caption's, the floor of the scale),
 * always the control radius. Every badge was upper case with wide tracking until 2026-10-28 (the UI
 * review: "PAUSED" and "MEMBER" shouted beside sentence-case badges of the same kind); weight and
 * fill tell a status from a fact now, not capitals.
 *
 * `outline` is a FACT rather than a status - a person's role beside their name - so it takes a
 * medium weight, no fill and a hairline border in secondary ink. A fill there read as a status the
 * person was in. A word the USER typed (a tag) is not a badge at all but a `Chip`.
 *
 * THE BUTTON'S SHAPE, SMALLER - never a pill (the maintainer, 2026-11-08: "The badges should not be
 * pill style, they should be similar to the primary buttons, but smaller"). A fixed height - 24px,
 * 20px for `sm` - and `rounded-sm` (6px): the button's corner in proportion, 8px on a 32-36px
 * button being a quarter of its height. These were `rounded-md` (8px) on 18-20px, which is all
 * but a full pill, and read as one. These carry WORDS - Admin, Enterprise, Revoked, Most popular.
 *
 * The three brand variants differ only in WEIGHT - the solid fill, then two on the NEUTRAL selected
 * fill with the brand's ink (the brand is never a tinted background, since 2026-10-28): `featured`
 * with a hairline brand border, `electric` with a firmer one.
 *
 * success/warning/danger use the fixed green-600/amber-600/red-600 literals rather than the
 * --success/--warning/--danger tokens: those are brightened for dark mode because they back TEXT,
 * and none of them holds 4.5:1 under white as a fill.
 */
export function Badge({ label, variant = 'neutral', tone = 'neutral', size = 'md', numeric, className, children }: Props) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-sm whitespace-nowrap text-xs leading-none',
        size === 'sm' ? 'h-5 px-1.5' : 'h-6 px-2',
        variant === 'outline' || numeric ? 'font-medium' : 'font-semibold',
        numeric && 'data-readout',
        variant === 'brand' && 'bg-brand-solid text-brand-on-solid',
        variant === 'featured' && 'bg-brand-chip-bg text-brand-legible border border-brand-solid/30',
        variant === 'electric' && 'bg-brand-chip-bg text-brand-legible border border-brand-solid/60',
        variant === 'success' && 'bg-success-solid text-on-status',
        variant === 'warning' && 'bg-warning-solid text-on-status',
        variant === 'danger' && 'bg-danger-solid text-on-status',
        variant === 'neutral' && 'bg-surface-alt text-ink-secondary border border-line',
        variant === 'outline' && 'bg-transparent border',
        variant === 'outline' && tone === 'neutral' && 'text-ink-secondary border-line',
        variant === 'outline' && tone === 'danger' && 'text-danger border-danger/40',
        variant === 'outline' && tone === 'warning' && 'text-warning border-warning/40',
        variant === 'outline' && tone === 'brand' && 'text-brand-legible border-brand-solid/40',
        className,
      )}
    >
      {children}
      {label}
    </span>
  )
}

const TIER_VARIANT: Record<string, 'neutral' | 'brand'> = {
  COMMUNITY: 'neutral',
  ENTERPRISE: 'brand',
}

/**
 * A LICENSE TIER by WEIGHT rather than hue - Community neutral, Enterprise the solid brand fill.
 *
 * Two tiers since 2026-10-14. PERSONAL and PROFESSIONAL are RETIRED - never issued again, still on
 * the rows that carry them in the license and telemetry consoles - and are drawn as the OUTLINE an
 * unknown tier gets: a fact about an old row, not a rung of a ladder anybody climbs now. The tier
 * names are not translated - they are what a receipt says.
 */
export function TierBadge({ tier, className }: { tier?: string | null; className?: string }) {
  if (!tier) return <EmptyValue />
  return <Badge label={tier} variant={TIER_VARIANT[tier.toUpperCase()] ?? 'outline'} className={className} />
}

/**
 * A deployment's PLAN as it stands: its tier, and on a trial how long the trial has left - the
 * TierBadge alone cannot say "Enterprise, 12 days to go". `trialLabel` arrives already translated
 * ("Trial - 12 days left"); the last week is drawn as a warning.
 */
export function PlanBadge({ tier, trial, daysLeft, trialLabel, className }: {
  tier?: string | null
  trial?: boolean
  daysLeft?: number | null
  trialLabel?: string
  className?: string
}) {
  return (
    <span className={cn('inline-flex items-center gap-2', className)}>
      <TierBadge tier={tier} />
      {trial && trialLabel && (
        <Badge label={trialLabel} variant="outline" numeric
          tone={daysLeft != null && daysLeft <= 7 ? 'warning' : 'brand'} />
      )}
    </span>
  )
}
