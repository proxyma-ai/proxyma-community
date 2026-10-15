// Decides whether to show the "what's new" panel after an update. Plain localStorage functions
// rather than a Zustand store, for the same reason as mcpAdStore beside it: this is asked once per
// app mount, not read reactively by several components.
const KEY_SEEN_VERSION = 'proxyma-whats-new-seen-version'

function read(): string | null {
  try { return localStorage.getItem(KEY_SEEN_VERSION) } catch { return null }
}

function write(value: string) {
  try { localStorage.setItem(KEY_SEEN_VERSION, value) } catch { /* private browsing, ignore */ }
}

/**
 * Whether this user has just arrived on a version they have not seen before.
 *
 * <p><b>A FRESH INSTALL MUST NOT SEE IT.</b> Somebody opening Proxyma for the first time has no
 * "before", so "what's new" is a list of changes from a version they never ran - which reads as
 * release-process noise on the one screen where it matters most. With nothing recorded, this
 * silently records the current version and answers false; only a stored value that DIFFERS from the
 * running one opens the panel.
 *
 * <p>Per browser rather than per account, which is the right granularity for a desktop app with one
 * user and an acceptable one for server mode: the cost of a second browser showing it twice is one
 * dismissed panel, and the alternative is a settings write on every user's first page load.
 *
 * <p>It also does not care whether the version went UP. A downgrade, or an operator moving a server
 * deployment back a release, is still a version this browser has not seen, and showing what changed
 * is still the honest answer.
 */
export function shouldShowWhatsNew(currentVersion: string): boolean {
  if (!currentVersion) return false
  const seen = read()
  if (seen === null) {
    write(currentVersion)
    return false
  }
  return seen !== currentVersion
}

/** Records that this version's panel has been seen, so it does not open again. */
export function markWhatsNewSeen(currentVersion: string) {
  if (currentVersion) write(currentVersion)
}
