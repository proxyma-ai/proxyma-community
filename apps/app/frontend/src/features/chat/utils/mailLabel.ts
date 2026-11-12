// Explicit .ts extension, so `node --test` can load this module - conversationTitle.test.ts.
import { approvalOutcomeText } from './approvalOutcome.ts'
import type { AgentMailEntry } from '../types/chat.types'
import type { Translate } from '@/i18n'

/** One mail entry's line, in words - in the thread (AgentPrompts) and as a conversation's title. */
export function mailLabel(entry: Pick<AgentMailEntry, 'kind' | 'title'>, t: Translate): string {
  const title = entry.title
  switch (entry.kind) {
    case 'report': return title ? t('Report from {title}', { title }) : t('Report from an agent')
    case 'message': return title ? t('Message from {title}', { title }) : t('Message from an agent')
    case 'reply': return title ? t('Reply from {title}', { title }) : t('Reply from an agent')
    // What the agent told the person from one of its runs - a Constellations lead's tellPerson.
    case 'told': return t('Message for you')
    case 'alarm': return title ? t('Alarm: {title}', { title }) : t('Alarm')
    case 'background': return title ? t('Background result: {title}', { title }) : t('Background result')
    // "granted: createJiraIssue" - a person's decision on a write it kept for them (ApprovalInbox),
    // said as a sentence with the tool's title, never the server's code words.
    case 'approval': return approvalOutcomeText(title, t)
    // Told once that its context window is filling, before the conversation is condensed.
    case 'context': return t('Context window filling - told to save what matters')
    // A saved agent's workspace memory, given as the conversation opens or after it was condensed.
    case 'memory': return t('Read its workspace memory')
    // A Constellations agent's own messages, drawn as lines rather than their headers for the model
    // (ChatModule.shownOpening on the server): how a run started, a template's own runs, a note for the lead.
    case 'run_schedule': return t('Scheduled run')
    case 'run_webhook': return t('Run started by a webhook')
    case 'run_source': return t('Run started by new documents')
    case 'run_member': return t('Run started by a project member')
    case 'learning': return t('Learning from your decisions')
    case 'revising': return t('Revising the drafts waiting for approval')
    case 'lead_note': return t("Update on the team's work")
    case 'reply_failed': return title ? t('{title} could not answer', { title }) : t('An agent could not answer')
    default: return title ? t('You talked to {title}', { title }) : t('You talked to an agent')
  }
}
