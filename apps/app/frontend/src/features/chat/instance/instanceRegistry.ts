/**
 * Keyed instances that outlive the components showing them, and go once nothing has shown them for
 * a while.
 *
 * <p>A chat instance (store + event stream) must survive a REMOUNT: AppShell remounts the routed
 * page on every pathname change, and the chat page's first message alone moves it twice. Tied to a
 * component's lifetime, the instance would be thrown away in the middle of its own turn - which is
 * why the page's one stream was module-level until there was more than one chat on a page
 * (chatStream.ts). So an instance is held by KEY here, counted by the components that retain it,
 * and disposed only when it has been retained by none for `graceMs` - long enough for an unmount
 * and the mount that replaces it, and for a render that created one without ever committing.
 *
 * Plain TypeScript with the timer passed in, so it is tested without React or a DOM
 * (instanceRegistry.test.ts).
 */
export interface RegistryTimers {
  set: (fn: () => void, ms: number) => unknown
  clear: (handle: unknown) => void
}

export interface InstanceRegistry<T> {
  /** The instance for `key`, made by `make` the first time it is asked for. */
  get: (key: string, make: () => T) => T
  /** The instance for `key` if there is one - never makes one. */
  peek: (key: string) => T | undefined
  /** Holds the instance for `key` until the returned function is called (once; later calls do nothing). */
  retain: (key: string) => () => void
  /** How many instances are held. */
  size: () => number
}

const DEFAULT_TIMERS: RegistryTimers = {
  set: (fn, ms) => setTimeout(fn, ms),
  clear: handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
}

interface Entry<T> {
  instance: T
  refs: number
  timer: unknown
}

export function createInstanceRegistry<T>(options: {
  dispose: (instance: T, key: string) => void
  graceMs: number
  timers?: RegistryTimers
}): InstanceRegistry<T> {
  const timers = options.timers ?? DEFAULT_TIMERS
  const entries = new Map<string, Entry<T>>()

  // Disposes the entry after the grace period unless something retains it meanwhile.
  const scheduleIdle = (key: string, entry: Entry<T>) => {
    if (entry.timer !== null) timers.clear(entry.timer)
    entry.timer = timers.set(() => {
      entry.timer = null
      if (entry.refs > 0 || entries.get(key) !== entry) return
      entries.delete(key)
      options.dispose(entry.instance, key)
    }, options.graceMs)
  }

  return {
    get(key, make) {
      const found = entries.get(key)
      if (found) return found.instance
      const entry: Entry<T> = { instance: make(), refs: 0, timer: null }
      entries.set(key, entry)
      // Made during a render that may never commit: nothing retains it yet.
      scheduleIdle(key, entry)
      return entry.instance
    },
    peek(key) {
      return entries.get(key)?.instance
    },
    retain(key) {
      const entry = entries.get(key)
      if (!entry) return () => {}
      entry.refs += 1
      if (entry.timer !== null) {
        timers.clear(entry.timer)
        entry.timer = null
      }
      let released = false
      return () => {
        if (released) return
        released = true
        entry.refs -= 1
        if (entry.refs === 0) scheduleIdle(key, entry)
      }
    },
    size() {
      return entries.size
    },
  }
}
