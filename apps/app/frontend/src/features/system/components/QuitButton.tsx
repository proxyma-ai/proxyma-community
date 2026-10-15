import axios from 'axios'
import { Power } from 'lucide-react'
import { ENV } from '@/config/env'
import { ConfirmDialog, FadeSwap, menuRow } from '@/components/ui/kit'
import { systemApi } from '@/services/systemApi'
import { useTranslation } from '@/i18n'
import { StatusLayer } from '@/components/ui/StatusLayer'
import { useQuitStore } from '@/store/quitStore'
import { useToastStore } from '@/store/toastStore'

// Quits a desktop install from the UI.
//
// The tray icon's Exit item used to be the only way to stop Proxyma, and AWT's SystemTray is
// unsupported on GNOME and under Wayland - so a Linux user had a running application, a browser
// tab, and nothing offering to close either. See ShutdownController for the backend half.
//
// How long "Quitting" waits for the backend to stop answering before saying it did not. Shutdown
// takes several seconds (indexing winding down, ChromaDB stopping); this is well past that.
const QUIT_TIMEOUT_MS = 60_000

// Behind a confirmation because quitting is not undoable from here: once the process is gone this
// page cannot bring it back, and the user has to go and start Proxyma again from their desktop.
//
// The trigger is here; the dialogs are QuitDialogs, rendered once by AppShell. See quitStore.ts
// for why they cannot live beside the trigger.
interface QuitButtonProps {
  /** Lets a menu close itself as the confirmation opens, so the dialog is not behind a dropdown. */
  onOpenConfirm?: () => void
  /** The phone menu passes its own row; the default is the kit's danger menu row. */
  className?: string
}

// A ROW INSIDE A MENU, since Quit moved off the header into the desktop app menu (and the phone
// menu) so the header could hold a menu instead of three separate controls. The round header icon
// it shipped as was an `icon` variant nobody passed any more; it went in the 2026-10 kit rebuild.
// Both menus' rows open the one QuitDialogs.
export function QuitButton({ onOpenConfirm, className }: QuitButtonProps = {}) {
  const { t } = useTranslation()
  const ask = useQuitStore(s => s.ask)

  const askToQuit = () => {
    onOpenConfirm?.()
    ask()
  }

  return (
    <button
      type="button"
      onClick={askToQuit}
      // The danger treatment Sign out carries in the account menu, so the one control that
      // cannot be undone from this page looks the same in both editions.
      //
      // THE DEFAULT IS `menuRow('danger')` AND NOT A COPY OF IT. It was a hand-written copy,
      // written before the kit had a row recipe, and it had drifted in the two ways that
      // recipe exists to prevent: no `touch:min-h-tap`, so measured at 768x1024 on a coarse
      // pointer Quit was 39px in a menu whose every other row was 44px; and a bare `hover:`,
      // which on glass fires on tap and then STICKS, leaving the one irreversible control in
      // this app lit up red after the finger has gone.
      className={className ?? menuRow('danger')}
    >
      <Power size={15} className="shrink-0" /> {t('Quit Proxyma')}
    </button>
  )
}

/**
 * The confirmation and the "Proxyma has stopped" panel. Rendered once, by AppShell, on a desktop
 * install.
 *
 * In Proxyma's own window the stopped panel is up only for a moment: the window closes itself when
 * the launcher exits (desktop-shell/main.js, watchOwner). A browser tab cannot be closed by its
 * page, so there it stays and says so.
 */
export function QuitDialogs() {
  const { t } = useTranslation()
  const phase = useQuitStore(s => s.phase)
  const cancel = useQuitStore(s => s.cancel)
  const stopping = useQuitStore(s => s.stopping)
  const stopped = useQuitStore(s => s.stopped)
  const addToast = useToastStore(s => s.addToast)

  // "Proxyma has stopped" is said only once it is TRUE. It used to appear the moment the request
  // went out, while shutdown - indexing wound down, ChromaDB stopped - took several seconds more,
  // so the panel claimed a stopped application that was still running (maintainer's report,
  // 2026-09-26). Now the panel says "Quitting" and polls health until nothing answers. Plain
  // fetch, not the http client: its interceptors are for a live backend, and a refused
  // connection is the answer being waited for. In Proxyma's own window the window closes itself
  // when the launcher exits, usually before this even flips.
  const quit = async () => {
    stopping()
    try {
      await systemApi.quit()
    } catch (e) {
      // The process can win the race and drop the connection before the 202 is written, which
      // surfaces as a network error for a request that did exactly what was asked - the polling
      // below decides that case. An HTTP ERROR is different: the backend answered and refused,
      // so nothing is stopping, and polling would spin "Quitting" forever.
      if (axios.isAxiosError(e) && e.response) {
        cancel()
        addToast(t('Could not quit Proxyma. Try again.'), 'danger')
        return
      }
    }
    const deadline = Date.now() + QUIT_TIMEOUT_MS
    for (;;) {
      await new Promise(r => setTimeout(r, 500))
      try {
        const r = await fetch(`${ENV.apiBaseUrl}/api/v1/health`, { cache: 'no-store' })
        if (!r.ok) break
      } catch {
        break
      }
      if (Date.now() > deadline) {
        cancel()
        addToast(t('Proxyma is still running. Try quitting again.'), 'danger')
        return
      }
    }
    stopped()
  }

  if (phase === 'idle') return null
  return (
    <>
      {/* The kit's confirmation - the same centred glyph, title, sentence and toned Confirm as every
          other one. It was a dialog of its own with a 14px title and a /70 scrim, the darker one
          the status screens use, which made a question look like a screen that had taken over. */}
      {phase === 'confirming' && (
        <ConfirmDialog
          icon={Power}
          title={t('Quit Proxyma?')}
          description={t('Indexing and any chat in progress will stop until you start Proxyma again. Nothing already indexed is lost.')}
          cancelLabel={t('Cancel')}
          confirmLabel={t('Quit')}
          onCancel={cancel}
          onConfirm={quit}
        />
      )}

      {/* The desktop splash as a card (StatusLayer's `splash`), ONE layer for both phases so the
          card stays where it is and only its words change: the mark turns and the bar runs while
          Proxyma quits, and both stop once it has. Level 0, and the named status layer (z-status)
          the restart and maintenance screens share - it was a number, z-[9999]. No onClose:
          the quit cannot be taken back, and once it is done there is nothing to go back to. */}
      {(phase === 'stopping' || phase === 'stopped') && (
        <StatusLayer
          z="z-status"
          splash
          busy={phase === 'stopping'}
          title={<FadeSwap swapKey={phase}>{phase === 'stopping' ? t('Quitting Proxyma') : t('Proxyma has stopped')}</FadeSwap>}
          ariaLabel={phase === 'stopping' ? t('Quitting Proxyma') : t('Proxyma has stopped')}
        >
          {phase === 'stopped' ? t('You can close this page.') : null}
        </StatusLayer>
      )}
    </>
  )
}
