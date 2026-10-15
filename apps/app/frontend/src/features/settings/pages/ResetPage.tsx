import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { AlertTriangle } from 'lucide-react'
import { Button, ConfirmDialog, List, ListRow, SettingsSections } from '@/components/ui/kit'
import { apiErrorText } from '@/services/apiError'
import { useAwaitRestart } from '../components/RestartBanner'
import { systemApi } from '@/services/systemApi'
import { useToastStore } from '@/store/toastStore'
import { useTranslation } from '@/i18n'

// Reset, DESKTOP edition.
//
// The server offers three resets at the bottom of Administration > System. Desktop has no
// Administration page, and had no reset at all - the only way to start over was to uninstall,
// find the data directory by hand and delete it.
//
// TWO of the three, not three. "Reset Users" has no meaning here: there are no accounts. The
// remaining two map onto the questions a desktop user actually has - "my configuration is a mess"
// and "I want my machine back".
//
// Credential encryption keys survive both: losing them would turn every stored token into
// undecryptable bytes rather than deleting them. (There is no license to keep: Desktop is Community.)
//
// Both endpoints self-restart the app. The desktop launcher already restarts the child on exit
// code 3 (it treats it as the update signal and applyPendingUpdate no-ops when nothing is
// staged), so the app comes back on its own against the freshly-created schema.
export function ResetPage() {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [everythingOpen, setEverythingOpen] = useState(false)
  const awaitRestart = useAwaitRestart()

  // Both resets restart the app, so the request itself is expected to be interrupted - the app is
  // gone before it can answer. Waiting for it to come back and reloading is the completion signal,
  // exactly as the server's Administration page does it.
  //
  // RestartingModal (rendered from AppShell off useRestartStore) covers the whole wait. Without it
  // this page closed its confirmation dialog and then sat there looking finished and interactive
  // for up to two minutes while the backend was not running - every control on it dead, every
  // request failing, and no statement anywhere that a restart was in progress. On desktop that gap
  // is the widest of any: the app exits, the launcher deletes the data directory and only then
  // starts the process again.
  const afterReset = async () => {
    if (!(await awaitRestart())) {
      addToast(t('Reset triggered, but the app did not come back in time. Restart it manually.'), 'danger')
    }
  }

  const resetSettings = useMutation({
    mutationFn: () => systemApi.resetSettings('RESET SETTINGS'),
    onSuccess: () => { setSettingsOpen(false); void afterReset() },
    // The server's reason, never axios's "Request failed with status code 400".
    onError: (e: unknown) => addToast(apiErrorText(e) ?? t('Failed to reset settings'), 'danger'),
  })

  const resetEverything = useMutation({
    mutationFn: () => systemApi.reset('RESET EVERYTHING'),
    onSuccess: () => { setEverythingOpen(false); void afterReset() },
    onError: (e: unknown) => addToast(apiErrorText(e) ?? t('Failed to reset'), 'danger'),
  })

  return (
    // The tab's introduction is SettingsSections' intro, as on every other tab that has one.
    <SettingsSections intro={t('Both actions restart Proxyma when they finish.')}>

      {/* A plain divided list, not a bordered rounded card - the kit List, the same treatment
          Documentation, Privacy, About and Skills use. */}
      <List>
        <ListRow
          title={t('Reset settings')}
          caption={t('AI provider, agent, conversion, search, embedding, network, retention and cost settings go back to their defaults. Your connectors, indexed documents and chat history are left alone.')}
          action={
            <Button variant="secondary" onClick={() => setSettingsOpen(true)}>
              {t('Reset settings')}
            </Button>
          }
        />

        <ListRow
          title={t('Reset everything')}
          caption={t('Every connector, all indexed content and chat history, plus the settings above. Files on your disk are not touched.')}
          action={
            // Quiet danger, like every other destructive action in Settings: the typed
            // confirmation it opens is where the danger fill belongs.
            <Button variant="danger-quiet" onClick={() => setEverythingOpen(true)}>
              {t('Reset everything')}
            </Button>
          }
        />
      </List>

      {/* The kit confirmation with a typed gate: Confirm stays off until the phrase is typed. */}
      {settingsOpen && (
        <ConfirmDialog
          title={t('Reset settings')}
          icon={AlertTriangle}
          description={t('Are you sure you want to {verb}', { verb: t('reset') })}
          subject={`${t('all settings')}?`}
          consequences={[
            t('Providers, models and API keys return to defaults - you will need to configure them again'),
            t('Connectors, indexed documents and chat history are kept'),
            t('Proxyma restarts when this finishes'),
          ]}
          typedConfirmation={{ value: 'RESET SETTINGS', label: t('Type "{value}" to confirm', { value: 'RESET SETTINGS' }) }}
          confirmLabel={t('Reset settings')}
          cancelLabel={t('Cancel')}
          pendingLabel={t('Resetting...')}
          pending={resetSettings.isPending}
          onConfirm={() => resetSettings.mutate()}
          onCancel={() => setSettingsOpen(false)}
        />
      )}

      {everythingOpen && (
        <ConfirmDialog
          title={t('Reset everything')}
          icon={AlertTriangle}
          description={t('Are you sure you want to {verb}', { verb: t('permanently delete') })}
          subject={`${t('everything Proxyma has stored')}?`}
          consequences={[
            t('Every connector and everything indexed from it will be deleted'),
            t('All chat history and conversations will be deleted'),
            t('All settings return to defaults'),
            t('Your own files on disk are NOT touched'),
            t('Proxyma restarts into a first-run state when this finishes'),
          ]}
          typedConfirmation={{ value: 'RESET EVERYTHING', label: t('Type "{value}" to confirm', { value: 'RESET EVERYTHING' }) }}
          confirmLabel={t('Reset everything')}
          cancelLabel={t('Cancel')}
          pendingLabel={t('Resetting...')}
          pending={resetEverything.isPending}
          onConfirm={() => resetEverything.mutate()}
          onCancel={() => setEverythingOpen(false)}
        />
      )}
    </SettingsSections>
  )
}
