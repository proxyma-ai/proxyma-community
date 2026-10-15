import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { apiErrorText } from '@/services/apiError'
import { useToastStore } from '@/store/toastStore'
import { useTranslation } from '@/i18n'
import { indexingApi } from '../services/indexingApi'
import type { ConnectorConfig } from '../types/indexing.types'

/**
 * Points a local folder source at the folder it moved to (since 2026-09-29).
 *
 * <p>A folder moved or renamed on disk failed every sync with "There is no folder at ..." and the
 * only way back was deleting the source and adding it again, which also threw away its settings.
 * This opens the same native chooser Add Folder uses and hands the answer to the config PATCH as
 * `path`; the backend checks the folder, renames the source after it and re-indexes it from there.
 *
 * <p>`browse` only ASKS - it returns the chosen path, or null when the chooser was cancelled - so
 * a dialog can hold the answer until its own Save. `relink` asks and saves in one step.
 */
export function useRelinkFolder(connector: ConnectorConfig) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const browse = async (): Promise<string | null> => {
    setError('')
    try {
      const res = await indexingApi.browseFolder(connector.name)
      if (res.cancelled) {
        if (res.reason) setError(res.reason)
        return null
      }
      return res.path ?? null
    } catch {
      setError(t('Could not open the folder chooser.'))
      return null
    }
  }

  const relink = async () => {
    setBusy(true)
    try {
      const path = await browse()
      if (!path) return
      await indexingApi.updateConfig(connector.id, { path })
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
      qc.invalidateQueries({ queryKey: keys.connectorsByType() })
      addToast(t('Folder relinked - re-indexing it from its new location'), 'success')
    } catch (e: unknown) {
      // The server's sentence (a folder that is not there, one another source reads) - else ours.
      setError(apiErrorText(e) ?? t('Failed to relink the folder'))
    } finally {
      setBusy(false)
    }
  }

  return { browse, relink, busy, error }
}
