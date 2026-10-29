import { MessageSquareWarning } from 'lucide-react'
import { Button, Caption, ExternalLink, SettingsSection } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { useFeedbackModalStore } from '@/store/feedbackModalStore'

// About's feedback row, DESKTOP edition. It was a tab of its own until 2026-10-12, when the rail
// needed its row for Logs (the rail fits 1280x720 with six pixels to spare - frontend.md).
// It is still the ONLY route to the feedback form here: the account menu that carries it on
// Enterprise Server does not exist without accounts.
//
// The copy is unchanged from the tab, and so is the reason for it: written from what is actually on
// the wire (FeedbackSubmission), with the Privacy Policy linked rather than paraphrased.
export function FeedbackSection() {
  const { t } = useTranslation()
  const openFeedback = useFeedbackModalStore(s => s.open)
  return (
    <SettingsSection
      title={t('Feedback')}
      caption={t('You choose on the form whether to attach logs, the conversation or files.')}
    >
      <div className="space-y-3">
        <Button onClick={() => openFeedback()}>
          <MessageSquareWarning size={14} />
          {t('Send feedback')}
        </Button>
        <Caption>
          <ExternalLink href="https://proxyma.ai/privacy">{t('How feedback data is handled')}</ExternalLink>
        </Caption>
      </div>
    </SettingsSection>
  )
}
