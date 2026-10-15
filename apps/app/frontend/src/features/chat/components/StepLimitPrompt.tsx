import { useState } from 'react'
import { CirclePause } from 'lucide-react'
import { Button, InlineNotice, PromptCard } from '@/components/ui/kit'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useToastStore } from '@/store/toastStore'
import { useTranslation } from '@/i18n'
import { withReason } from '@/services/apiError'
import { chatApi } from '../services/chatApi'
import { CONTINUE_ANSWER } from '../utils/askUser'
import type { PendingQuestion, StepLimitQuestion } from '../types/chat.types'

/**
 * The turn has used its steps and the agent was still working: go on, or stop here?
 *
 * Since 2026-10-03 (the maintainer's request). The turn used to end at the limit, with the last
 * step told to answer from what it had and a notice under it - so a long job stopped part-way
 * and had to be restarted by hand. Now it waits here (ChatService.askToContinue). "Continue"
 * gives it as many steps again; "Stop here", or no answer, ends it the way it always ended.
 *
 * Two buttons rather than QuestionPrompt's rows and Send: there is one question, both answers
 * are known, and neither takes words of the reader's own. The answer goes back through the same
 * endpoint as any other question's, as the one English word the server compares against - the
 * labels here are translated, the answer is not.
 */
export function StepLimitPrompt({ pending, stepLimit, conversationId }: {
  pending: PendingQuestion
  stepLimit: StepLimitQuestion
  conversationId: string
}) {
  const { t } = useTranslation()
  const markSent = useChatStore(s => s.markQuestionSent)
  const [sending, setSending] = useState(false)
  const done = pending.sent || sending

  const answer = async (goOn: boolean) => {
    if (done) return
    setSending(true)
    try {
      const question = pending.questions[0]?.question ?? ''
      await chatApi.answerQuestion(conversationId, pending.callId,
        goOn ? [{ question, answer: CONTINUE_ANSWER }] : [], !goOn)
      markSent(pending.callId)
    } catch (e) {
      useToastStore.getState().addToast(withReason(t('Could not send your answer'), e), 'danger')
      setSending(false)
    }
  }

  return (
    <PromptCard
      icon={CirclePause}
      ariaLabel={t('Step limit reached')}
      busy={done}
      title={t('The agent has used its {count} step and is not finished.|The agent has used its {count} steps and is not finished.', { count: stepLimit.limit })}
      caption={t('Continue for up to {count} more step, or stop and get an answer from what it has found.|Continue for up to {count} more steps, or stop and get an answer from what it has found.', { count: stepLimit.more })}
      status={done && <InlineNotice tone="progress">{t('Sending your answer')}</InlineNotice>}
      actions={!done && (
        <>
          <Button variant="secondary" size="sm" onClick={() => answer(false)}>{t('Stop here')}</Button>
          <Button variant="primary" size="sm" onClick={() => answer(true)}>{t('Continue')}</Button>
        </>
      )}
    />
  )
}
