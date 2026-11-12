import { test } from 'node:test'
import assert from 'node:assert/strict'
import { approvalOutcomeText } from './approvalOutcome.ts'

// A marker around every string that went through the translation function, so a test can tell a
// translated sentence from English pasted around it.
const t = (s: string, p?: Record<string, string | number>) =>
  `<${s.replace(/\{(\w+)\}/g, (_, k) => String(p?.[k] ?? `{${k}}`))}>`

test('each outcome is a whole translated sentence around the translated tool title', () => {
  assert.equal(approvalOutcomeText('granted: replyToYouTubeComment', t), '<Approved: <Reply to a YouTube comment>>')
  assert.equal(approvalOutcomeText('held: replyToYouTubeComment', t), '<Approved, waiting to be sent: <Reply to a YouTube comment>>')
  assert.equal(approvalOutcomeText('denied: createJiraIssue', t), '<Denied: <Create a Jira issue>>')
  assert.equal(approvalOutcomeText('failed: createJiraIssue', t), '<Approved, but it failed: <Create a Jira issue>>')
  assert.equal(approvalOutcomeText('withdrawn: createJiraIssue', t), '<Withdrawn: <Create a Jira issue>>')
  assert.equal(approvalOutcomeText('replaced: createJiraIssue', t), '<Replaced by a newer draft: <Create a Jira issue>>')
  assert.equal(approvalOutcomeText('expired: createJiraIssue', t), '<Nobody decided in time: <Create a Jira issue>>')
})

test('an outcome it does not know still names the tool, never the code word', () => {
  assert.equal(approvalOutcomeText('postponed: createJiraIssue', t), '<Decided: <Create a Jira issue>>')
})

test('a title it cannot read is a decision on a write', () => {
  assert.equal(approvalOutcomeText(null, t), '<A decision on a write>')
  assert.equal(approvalOutcomeText('', t), '<A decision on a write>')
  assert.equal(approvalOutcomeText('granted', t), '<A decision on a write>')
})
