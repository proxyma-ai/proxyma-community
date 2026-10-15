import { useState, type ReactNode } from 'react'
import { Check, MessageCircleQuestion } from 'lucide-react'
import { Button, ChoiceRow, FadeSwap, InlineNotice, MetaList, PromptCard, PromptSection, SubNavTabs } from '@/components/ui/kit'
import { useChatStore } from '../instance/ChatInstanceContext'
import { useToastStore } from '@/store/toastStore'
import { useTranslation } from '@/i18n'
import { withReason } from '@/services/apiError'
import { chatApi } from '../services/chatApi'
import { OTHER, answerOf, nextUnanswered, togglePick } from '../utils/askUser'
import type { AskUserQuestion, PendingQuestion } from '../types/chat.types'

/**
 * The assistant asking the user (askUser), since 2026-09-29 - the maintainer's request, modeled on
 * the question panel of a coding assistant in an editor: each question with its choices as rows,
 * one pick or several, and a row to answer in your own words. The turn waits server-side until
 * this is sent or skipped (ChatService.awaitUserAnswers), so it is shown on its own under the tool
 * list, never inside a row that could be collapsed out of sight.
 *
 * Which picks become the answer, and its shape - a list for a question that takes several - is
 * utils/askUser.ts.
 *
 * SEVERAL QUESTIONS ARE TABS since 2026-10-07 (maintainer's feedback: "each question should have a
 * tab, rather than display all questions in a single page"). One question per page, a tab for each
 * named by its header, a check on the ones answered; picking the one answer of a question moves on
 * to the next unanswered one, and the primary action is Next until everything has an answer, then
 * Send. One Send still answers them all.
 */
export function QuestionPrompt({ pending, conversationId, from }: {
  pending: PendingQuestion
  conversationId: string
  // "From sub-agent: X", for a question a sub-agent asked (AgentPrompts) - the card's eyebrow.
  from?: ReactNode
}) {
  const { t } = useTranslation()
  const markSent = useChatStore(s => s.markQuestionSent)
  // Per question: the picked labels (OTHER for the free-text row), and the free text.
  const [picked, setPicked] = useState<string[][]>(() => pending.questions.map(() => []))
  const [other, setOther] = useState<string[]>(() => pending.questions.map(() => ''))
  const [sending, setSending] = useState(false)
  // The tab shown, where there are several questions.
  const [active, setActive] = useState(0)

  const answers = pending.questions.map((q, i) => answerOf(q, picked[i], other[i]))
  const answered = answers.map(a => a !== null)
  const ready = answered.every(Boolean)
  const tabbed = pending.questions.length > 1
  const next = nextUnanswered(answered, active)

  const toggle = (i: number, label: string) => {
    const q = pending.questions[i]
    setPicked(prev => prev.map((list, j) => j === i ? togglePick(q, list, label) : list))
    // A question's one answer, picked: on to the next one still open. Not for several picks, and
    // not for the row you type in - both are still being made.
    if (tabbed && !q.multiSelect && label !== OTHER) {
      const after = answered.map((a, j) => j === i || a)
      const to = nextUnanswered(after, i)
      if (to !== null) setActive(to)
    }
  }

  const send = async (skipped: boolean) => {
    if (sending || pending.sent) return
    setSending(true)
    try {
      const sent = skipped ? [] : pending.questions.map((q, i) => ({ question: q.question, answer: answers[i] ?? '' }))
      await chatApi.answerQuestion(conversationId, pending.callId, sent, skipped)
      markSent(pending.callId)
    } catch (e) {
      useToastStore.getState().addToast(withReason(t('Could not send your answer'), e), 'danger')
      setSending(false)
    }
  }

  const done = pending.sent || sending

  const choices = (i: number) => (
    <Choices
      q={pending.questions[i]}
      picked={picked[i]}
      other={other[i]}
      disabled={done}
      onToggle={label => toggle(i, label)}
      onOther={text => {
        setOther(prev => prev.map((v, j) => j === i ? text : v))
        // Typing an answer of your own picks the row, so nobody types and then wonders why Send is off.
        if (!picked[i].includes(OTHER)) toggle(i, OTHER)
      }}
    />
  )
  const first = pending.questions[0]
  const current = pending.questions[active] ?? first
  const actions = !done && (
    <>
      <Button variant="secondary" size="sm" onClick={() => send(true)}>{t('Skip')}</Button>
      {ready || !tabbed
        ? <Button variant="primary" size="sm" onClick={() => send(false)} disabled={!ready}>{t('Send answer')}</Button>
        : <Button variant="primary" size="sm" onClick={() => next !== null && setActive(next)} disabled={next === null}>{t('Next')}</Button>}
    </>
  )
  const status = done && <InlineNotice tone="progress">{t('Sending your answer')}</InlineNotice>

  // One question: the kit's PromptCard with the question as its title and its choices as the body.
  if (!tabbed) {
    return (
      <PromptCard
        icon={MessageCircleQuestion}
        ariaLabel={t('Question from the assistant')}
        busy={done}
        eyebrow={(from || first?.header) && <MetaList items={[from, first?.header]} />}
        title={first?.question ?? ''}
        caption={first?.multiSelect ? t('Pick any that apply.') : undefined}
        status={status}
        actions={actions}
      >
        {first && choices(0)}
      </PromptCard>
    )
  }

  // Several: the card says how many, a tab per question names each by its header (a check once it
  // has an answer), and the question on the tab shown is a PromptSection over its choices.
  return (
    <PromptCard
      icon={MessageCircleQuestion}
      ariaLabel={t('Questions from the assistant')}
      busy={done}
      eyebrow={from}
      title={t('The assistant has {count} questions', { count: pending.questions.length })}
      status={status}
      actions={actions}
    >
      <div className="space-y-3">
        <SubNavTabs
          ariaLabel={t('Questions from the assistant')}
          active={String(active)}
          onChange={key => setActive(Number(key))}
          tabs={pending.questions.map((q, i) => ({
            key: String(i),
            label: q.header || t('Question {number}', { number: i + 1 }),
            icon: answered[i] ? Check : undefined,
          }))}
        />
        {/* Keyed, so each question is its own section; the fade is what says the tab changed. */}
        <FadeSwap swapKey={active}>
          <PromptSection
            key={active}
            title={current.question}
            caption={current.multiSelect ? t('Pick any that apply.') : undefined}
          >
            {choices(active)}
          </PromptSection>
        </FadeSwap>
      </div>
    </PromptCard>
  )
}

// One question's answers: a row per choice (the kit ChoiceRow - a round mark for one pick, a
// square one for several), then the row to answer in your own words.
function Choices({ q, picked, other, disabled, onToggle, onOther }: {
  q: AskUserQuestion
  picked: string[]
  other: string
  disabled: boolean
  onToggle: (label: string) => void
  onOther: (text: string) => void
}) {
  const { t } = useTranslation()
  const multi = !!q.multiSelect
  return (
    // Named by its question, so a screen reader says what the choices answer (CHAT-13).
    <div className="space-y-1.5" role={multi ? 'group' : 'radiogroup'} aria-label={q.question}>
      {q.options.map(o => (
        <ChoiceRow
          key={o.label}
          checked={picked.includes(o.label)}
          multi={multi}
          label={o.label}
          description={o.description}
          disabled={disabled}
          onToggle={() => onToggle(o.label)}
        />
      ))}
      <ChoiceRow
        checked={picked.includes(OTHER)}
        multi={multi}
        ariaLabel={t('Other')}
        disabled={disabled}
        onToggle={() => onToggle(OTHER)}
      >
        {/* A field inside the row's own frame, so no field chrome of its own. */}
        <input
          value={other}
          disabled={disabled}
          onChange={e => onOther(e.target.value)}
          placeholder={t('Other - type your own answer')}
          // Its own focus ring, inside the row's frame: the kit ChoiceRow draws none round a field
          // inside it, and `outline-none` left keyboard focus here invisible (CHAT-13).
          className="flex-1 min-w-0 bg-transparent text-sm text-ink-primary placeholder:text-ink-disabled py-1 px-1 -mx-1 rounded-sm focus-ring"
        />
      </ChoiceRow>
    </div>
  )
}
