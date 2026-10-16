/**
 * WHEN A HIDDEN TAB LETS GO OF ITS CHAT STREAMS, and when it takes them back.
 *
 * <p>Hidden for `graceMs` - a glance at another tab costs nothing - the tab pauses (chatStream.ts's
 * `pause`); shown again, it resumes at once. A tab shown again before the grace ran out never
 * paused, and has nothing to resume.
 *
 * <p>Plain TypeScript with the timer passed in, so it is tested without a DOM
 * (hiddenPause.test.ts). chatInstance.ts feeds it the document's visibility.
 */
export interface PauseTimers {
  set: (fn: () => void, ms: number) => unknown
  clear: (handle: unknown) => void
}

export interface HiddenPause {
  /** The tab was hidden. */
  hidden: () => void
  /** The tab is shown again. */
  visible: () => void
}

const DEFAULT_TIMERS: PauseTimers = {
  set: (fn, ms) => setTimeout(fn, ms),
  clear: handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
}

export function createHiddenPause(options: {
  graceMs: number
  pause: () => void
  resume: () => void
  timers?: PauseTimers
}): HiddenPause {
  const timers = options.timers ?? DEFAULT_TIMERS
  let timer: unknown = null
  let paused = false

  return {
    hidden() {
      if (timer !== null || paused) return
      timer = timers.set(() => {
        timer = null
        paused = true
        options.pause()
      }, options.graceMs)
    },
    visible() {
      if (timer !== null) {
        timers.clear(timer)
        timer = null
      }
      if (!paused) return
      paused = false
      options.resume()
    },
  }
}
