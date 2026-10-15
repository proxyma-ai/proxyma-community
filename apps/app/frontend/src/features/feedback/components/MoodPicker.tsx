import { Frown, Meh, Smile } from 'lucide-react'
import { SegmentedControl, type SegmentedOption } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import type { FeedbackMood } from '../types/feedback.types'

// No colour. A red frown and a green smile pre-judge the answer before it is given - they read as
// "pick the bad one" vs "pick the good one" rather than as three equal choices, and a coloured
// glyph is also the only place in this app where an unselected control carries a status hue. The
// chosen one is marked by the same brand chip every other selected control uses.
const MOODS: Array<{ value: FeedbackMood; label: string; Icon: typeof Frown }> = [
  { value: 'BAD', label: 'Bad', Icon: Frown },
  { value: 'NEUTRAL', label: 'Neutral', Icon: Meh },
  { value: 'GOOD', label: 'Good', Icon: Smile },
]

/**
 * THE ONE MOOD PICKER - Bad, Neutral, Good - for every feedback form. There were two for the same
 * three moods until the 2026-10 kit rebuild: worded buttons toggled between `featured` and
 * `secondary` in the feedback dialog, and bare glyphs coloured red, amber and green that grew by a
 * quarter when chosen in the quota-milestone survey. One of a few, all visible, is the kit's
 * `SegmentedControl` - a radiogroup with arrow keys.
 *
 * `compact` drops the words for a row of questions that each take a mood (the survey); the word is
 * then the option's accessible name. Each option otherwise carries its word beside the glyph:
 * three faces alone left the user guessing whether the middle one meant "neutral" or "unsure".
 * `value` may be null - nothing is chosen until the user chooses.
 */
export function MoodPicker({ value, onChange, ariaLabel, compact, className }: {
  value: FeedbackMood | null
  onChange: (mood: FeedbackMood) => void
  ariaLabel: string
  compact?: boolean
  className?: string
}) {
  const { t } = useTranslation()
  const options: SegmentedOption<FeedbackMood>[] = MOODS.map(({ value: mood, label, Icon }) => ({
    value: mood,
    ariaLabel: compact ? t(label) : undefined,
    label: (
      <>
        <Icon size={14} aria-hidden="true" />
        {!compact && t(label)}
      </>
    ),
  }))
  return (
    <SegmentedControl
      options={options}
      // The kit control takes a value of its type; with none chosen, no option matches.
      value={(value ?? '') as FeedbackMood}
      onChange={onChange}
      ariaLabel={ariaLabel}
      size="md"
      className={className}
    />
  )
}
