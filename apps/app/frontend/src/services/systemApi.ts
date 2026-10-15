import { http } from './httpClient'

export const systemApi = {
  restart: () => http.post('/api/v1/system/restart').then(r => r.data),

  // SUPER_ADMIN-only resets - the server also verifies the confirmation phrase itself
  // (defense-in-depth beyond the frontend's own typed-confirmation modal). Each expects its own
  // exact phrase: reset -> "RESET EVERYTHING", resetUsers -> "RESET USERS",
  // resetSettings -> "RESET SETTINGS". All three self-restart the app on success.
  reset: (confirmation: string) => http.post('/api/v1/system/reset', { confirmation }).then(r => r.data),

  // Wipe all users + all user-owned data, keeping system settings and the license.
  resetUsers: (confirmation: string) => http.post('/api/v1/system/reset/users', { confirmation }).then(r => r.data),

  // Reset only the global system settings back to defaults.
  resetSettings: (confirmation: string) => http.post('/api/v1/system/reset/settings', { confirmation }).then(r => r.data),

  // Desktop only - ShutdownController is not built into proxyma-server, so this 404s there by
  // construction rather than by a frontend check. Returns 202 and the process exits a moment
  // later, so this resolving means "the request was accepted", not "the app has stopped".
  quit: () => http.post('/api/v1/desktop/quit').then(r => r.data),

  // Resolves once the backend STOPS answering. For a restart whose request returns before the old
  // process is gone (applying an update): without it, waitForRestart can be answered by the process
  // that is on its way out, and the page reloads into a backend that then disappears.
  waitForDown: (timeoutMs = 30_000, intervalMs = 500): Promise<void> => {
    const deadline = Date.now() + timeoutMs
    return new Promise((resolve, reject) => {
      const poll = () => {
        if (Date.now() > deadline) {
          reject(new Error('The application did not stop'))
          return
        }
        http.get('/api/v1/health')
          .then(() => setTimeout(poll, intervalMs))
          .catch(() => resolve())
      }
      poll()
    })
  },

  // Resolves once the backend answers again. TEN MINUTES, up from two (2026-10-10): a restart into
  // an update rebuilds the Python runtime before the backend even starts - measured on the
  // maintainer's PC at 100 seconds, about 30 once the rebuild stopped byte-compiling one file at a
  // time - and the backend itself takes 15 to 35 seconds after that, more on a slow machine with a
  // busy antivirus. Two minutes reported such a restart as failed while it was going well. Ten is
  // the launcher's own limit for a start (ProxymaLauncher.APP_READY_TIMEOUT_MS); the restart screen
  // says it is taking longer than usual after one (RestartingModal).
  waitForRestart: (timeoutMs = 600_000, intervalMs = 2_000): Promise<void> => {
    const deadline = Date.now() + timeoutMs
    return new Promise((resolve, reject) => {
      const poll = () => {
        if (Date.now() > deadline) {
          reject(new Error('Restart timed out'))
          return
        }
        http.get('/api/v1/health')
          .then(() => resolve())
          .catch(() => setTimeout(poll, intervalMs))
      }
      setTimeout(poll, intervalMs)
    })
  },
}
