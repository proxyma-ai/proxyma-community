// Explicit .ts extension, so `node --test` can load this module - approvalOutcome.test.ts.
import { toolDisplayName } from './toolDisplayName.ts'
import type { Translate } from '@/i18n'

// What became of a write an agent kept for a person's decision, in words. The server sends it as
// "<outcome>: <toolName>" (ApprovalInbox.settle) - the approval mail row in a transcript, the agents'
// timeline - with the outcome an English code word and the tool its code name. Neither is shown: each
// outcome is its own whole sentence, so a translation never has an English word put into it, and the
// tool is its translated title.
//
// The outcomes ApprovalInbox writes: granted (approved, and the call made), denied, held (approved,
// and waiting for the service's quota to come back), failed (approved, and the call failed for good),
// withdrawn, replaced (a newer draft took its place), expired (nobody decided in time).
export function approvalOutcomeText(title: string | null | undefined, t: Translate): string {
  const m = /^\s*([a-z_]+)\s*:\s*(\S.*?)\s*$/i.exec(title ?? '')
  if (!m) return t('A decision on a write')
  const tool = toolDisplayName(m[2], t)
  switch (m[1].toLowerCase()) {
    case 'granted': return t('Approved: {tool}', { tool })
    case 'denied': return t('Denied: {tool}', { tool })
    case 'held': return t('Approved, waiting to be sent: {tool}', { tool })
    case 'failed': return t('Approved, but it failed: {tool}', { tool })
    case 'withdrawn': return t('Withdrawn: {tool}', { tool })
    case 'replaced': return t('Replaced by a newer draft: {tool}', { tool })
    case 'expired': return t('Nobody decided in time: {tool}', { tool })
    default: return t('Decided: {tool}', { tool })
  }
}
