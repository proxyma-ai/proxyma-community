import type { Translate } from '@/i18n'
import { elapsedParts } from './toolBlocks'

/** 8s, 3m 12s, 1h 02m. A readout: two units at most, the smaller one padded so it does not jitter. */
export function formatElapsed(ms: number, t: Translate): string {
  const { hours, minutes, seconds } = elapsedParts(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  if (hours > 0) return t('{hours}h {minutes}m', { hours, minutes: pad(minutes) })
  if (minutes > 0) return t('{minutes}m {seconds}s', { minutes, seconds: pad(seconds) })
  return t('{seconds}s', { seconds })
}
