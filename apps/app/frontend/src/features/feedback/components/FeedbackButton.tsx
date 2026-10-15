import { useFeedbackModalStore } from '@/store/feedbackModalStore'
import { FeedbackModal } from './FeedbackModal'

/**
 * Mounts the feedback dialog. No button of its own any more.
 *
 * This used to render a 48px circle pinned `fixed bottom-6 left-6` on every page. On a phone and a
 * tablet it sat permanently on top of the content - over the chat composer's controls, over the
 * bottom row of any table - and there was no width at which it stopped overlapping something,
 * because it was positioned against the viewport rather than the layout.
 *
 * The entry point is "Send feedback" in the account menu on server, and Settings > Feedback on
 * desktop, which has no account menu.
 *
 * It used to return null in desktop entirely, on the reasoning that a desktop build has no admin
 * surface to review submissions. That reasoning was wrong twice over: submissions are not reviewed
 * locally in either edition - they are shipped to Proxyma, and desktop ships by default - and the
 * thumbs-up/down control on every chat message was already submitting feedback in desktop the whole
 * time, ungated. So the gate was not preventing feedback, only preventing the one screen that
 * explains what feedback sends.
 */
export function FeedbackButton() {
  const isOpen = useFeedbackModalStore(s => s.isOpen)
  const close = useFeedbackModalStore(s => s.close)

  return isOpen ? <FeedbackModal onClose={close} /> : null
}
