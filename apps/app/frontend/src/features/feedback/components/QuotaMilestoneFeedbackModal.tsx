import { useState } from 'react'
import { Button, DialogBody, DialogFooter, DialogHeader, Textarea } from '@/components/ui/kit'
import { useToastStore } from '@/store/toastStore'
import { useThemeStore } from '@/store/themeStore'
import { useSubmitFeedback } from '../hooks/useSubmitFeedback'
import { MoodPicker } from './MoodPicker'
import { QUOTA_MILESTONE_QUESTIONS } from '../types/feedback.types'
import type { FeedbackMood } from '../types/feedback.types'
import { useTranslation } from '@/i18n'
import { ModalShell } from '@/components/ui/ModalShell'

interface Props {
  // Closed via X/backdrop without submitting - caller only records "shown this cycle" (see
  // useQuotaMilestoneFeedback.dismiss), does NOT set the permanent opt-out.
  onDismiss: () => void
  // "Never show again" - caller sets the permanent opt-out flag.
  onNeverShowAgain: () => void
  // Fired after a successful submit - caller records "shown this cycle" same as dismiss.
  onSubmitted: () => void
}

// Structurally modeled on FeedbackModal.tsx (both render through ModalShell). Extends its single
// mood row into 5 independent question rows (QUOTA_MILESTONE_QUESTIONS), each with the app's one
// mood picker (MoodPicker), icon-only here since a row of questions has no room for the words.
export function QuotaMilestoneFeedbackModal({ onDismiss, onNeverShowAgain, onSubmitted }: Props) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const productName = useThemeStore(s => s.branding.productName)
  const submitFeedback = useSubmitFeedback()
  const [ratings, setRatings] = useState<Record<string, FeedbackMood>>({})
  const [comment, setComment] = useState('')

  const setRating = (questionId: string, mood: FeedbackMood) => {
    setRatings(prev => ({ ...prev, [questionId]: mood }))
  }

  const handleSubmit = () => {
    submitFeedback.mutate(
      {
        // Overall's rating (when answered) doubles as the single required `mood` field the
        // shared backend endpoint expects from every submission - default to NEUTRAL when the
        // user skipped every question rather than blocking submission on it.
        mood: ratings.overall ?? 'NEUTRAL',
        comment: comment.trim() || undefined,
        // Every submission is anonymous now, enforced by the backend regardless of what is sent
        // here (see FeedbackController). Left as `true` rather than `false` so this call site does
        // not read as though it still attaches an identity.
        anonymous: true,
        includeConversation: false,
        source: 'QUOTA_MILESTONE',
        ratings: Object.keys(ratings).length > 0 ? ratings : undefined,
      },
      {
        onSuccess: () => {
          addToast(t('Thanks for your feedback!'), 'success')
          onSubmitted()
        },
        onError: () => addToast(t('Failed to send feedback. Please try again.'), 'danger'),
      },
    )
  }

  const title = t("Enjoy using {product}? Leave feedback - it's significant in helping us improve {product}.",
    { product: productName })

  return (
    <ModalShell
      onClose={onDismiss}
      dirty={Object.keys(ratings).length > 0 || comment.trim() !== ''}
      size="md"
      ariaLabel={title}
    >
      {/* A long sentence for a title, so it wraps rather than truncating as a one-line name would. */}
      <DialogHeader title={title} wrapTitle />

      <DialogBody className="space-y-4">
        {QUOTA_MILESTONE_QUESTIONS.map(({ id, label }) => (
          <div key={id} className="flex items-center justify-between gap-3">
            <span className="text-sm text-ink-secondary">{t(label)}</span>
            <MoodPicker
              compact
              ariaLabel={t(label)}
              value={ratings[id] ?? null}
              onChange={mood => setRating(id, mood)}
              className="shrink-0"
            />
          </div>
        ))}

        <Textarea
          value={comment}
          onChange={e => setComment(e.target.value)}
          placeholder={t('Additional comments (optional)')}
          aria-label={t('Additional comments (optional)')}
          rows={3}
        />
      </DialogBody>

      <DialogFooter
        start={(
          <Button variant="quiet" onClick={onNeverShowAgain} disabled={submitFeedback.isPending}>
            {t('Never show again')}
          </Button>
        )}
      >
        <Button variant="primary" onClick={handleSubmit} disabled={submitFeedback.isPending}>
          {submitFeedback.isPending ? t('Sending...') : t('Submit')}
        </Button>
      </DialogFooter>
    </ModalShell>
  )
}
