// Explicit .ts extensions, so `node --test` can load this module - conversationTitle.test.ts.
import { MAIL_KINDS } from './agentTree.ts'
import { mailLabel } from './mailLabel.ts'
import type { AgentMailKind } from '../types/chat.types'
import type { Translate } from '@/i18n'

/**
 * A CONVERSATION'S TITLE, IN THE READER'S LANGUAGE. A conversation a person started is titled with their
 * words (or the model's name for them). One a product module or an agent opened - a Constellations run, a
 * note for its lead, a teammate's message - carries a `titleKind` instead (ConversationOpening on the
 * server, 2026-11-05: its first message is a header written for the model, in English, and the list showed
 * "[Scheduled run, ...]"). The kind is worded here like the transcript's mail line for the same message;
 * `title` is then only its data - a run's task, a sender's name - or nothing.
 */
export function conversationTitle(
  c: { title?: string | null; titleKind?: string | null },
  t: Translate,
): string {
  const title = c.title?.trim() || ''
  const kind = c.titleKind?.trim() || ''
  switch (kind) {
    // A run and the task it was given, which is the person's (or the template's) own words.
    case 'run_schedule': return title ? t('Scheduled run: {task}', { task: title }) : t('Scheduled run')
    case 'run_webhook': return title ? t('Run started by a webhook: {task}', { task: title }) : t('Run started by a webhook')
    case 'run_source': return title ? t('Run started by new documents: {task}', { task: title }) : t('Run started by new documents')
    case 'run_member': return title ? t('Run started by a project member: {task}', { task: title }) : t('Run started by a project member')
  }
  // Any other kind is a mail line's, with the sender's name as its title. One this page does not know
  // (a newer server's) falls back to the title as sent.
  if (kind && MAIL_KINDS.includes(kind as AgentMailKind)) {
    return mailLabel({ kind: kind as AgentMailKind, title: title || null }, t)
  }
  return title || t('New conversation')
}
