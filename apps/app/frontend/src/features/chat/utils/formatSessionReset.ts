import { formatDurationUntil } from '@/utils/formatters'
import type { Translate } from '@/i18n'

// The footnote line under the Session (5h) quota meter, shared by the chat-input quota popover
// (ChatPage) and the account Usage panel (SettingsModal) so the two never drift apart.
//
// The 5h window is rolling, not a fixed cycle, so it has two genuinely different states and the
// backend's fiveHourResetsAt cannot express the first one: with no spend in the window there is
// no oldest event to anchor to, so UserQuotaController falls back to now+5h - a placeholder that
// would read as a real countdown if rendered. Hence the explicit spend > 0 test here rather than
// trusting the timestamp.
//
// When the window IS running, the countdown is what the reader wants ("how long until I can carry
// on"), not a wall-clock instant they'd have to subtract from the current time themselves. It is
// still the same lower-bound approximation the backend computes (oldest contributing event +
// 5h) - continued chatting pushes it out - which is exactly why "at least" is stated rather than
// promising an exact moment.
// `t` is taken as an argument rather than a hook because this is a plain function called from
// two components (ChatPage's quota popover and SettingsModal's Usage panel).
//
// REQUIRED, not optional, and that is the second attempt at this signature. An optional `t` needs
// a fallback, and the obvious fallback - `(s) => s` - returns the key UNSUBSTITUTED, so English
// readers would have seen the literal "Resets in at least {duration}". A default that silently
// corrupts the fallback language is worse than making three call sites pass an argument they
// already have in hand. `formatDurationUntil` keeps its optional `t` because its only translatable
// branch takes no parameters, so there `(s) => s` is genuinely correct.
export function formatSessionReset(
  fiveHourSpendUsd: number, fiveHourResetsAt: string, t: Translate,
): string {
  if (fiveHourSpendUsd <= 0) return t('Starts with your first message')
  return t('Resets in at least {duration}', { duration: formatDurationUntil(fiveHourResetsAt, t) })
}
