import { useMemo, useState } from 'react'
import { AlertTriangle } from 'lucide-react'
import {
  Button, Caption, DialogBody, DialogFooter, DialogHeader, DialogNotice, Divider, FieldSkeleton, FormStack, InlineNotice,
  Presence,
} from '@/components/ui/kit'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { useShake } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { apiErrorText } from '@/services/apiError'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { FileTypeFilter } from './FileTypeFilter'
import { ConnectionPicker, useConnectionChoice } from './ConnectionPicker'
import { FieldSpecForm } from './FieldSpecForm'
import { SubfoldersSwitch } from './SourceScopeFields'
import { indexingApi } from '../services/indexingApi'
import { useCatalogType } from '../hooks/useConnectorCatalog'
import { SourceTypeMark } from '../utils/sourceMarks'
import {
  fieldProblems, fieldValuesChanged, initialFieldValues, submittedFieldValues, type FieldProblem,
} from '../utils/fieldForm'

/**
 * ADD A SOURCE, for every type without a dialog of its own - drawn entirely from the type's
 * descriptor (the connector catalog): its title and noun, the connection it reads through, its
 * fields, and the file-type filter and subfolder switch where its capabilities say so. A connector
 * added on the server is added here without a line of this file changing.
 *
 * <p>The types with a dialog of their own (`customDialog`) are a folder, uploads, a web page and a
 * network share, whose forms do things a field list cannot (a folder picker, a drop, a crawl's
 * warnings) - see SourcesPage.
 *
 * <p>One endpoint for all of them, POST /api/v1/indexing/register, with the same three refusals the
 * per-type endpoints it replaced had: the caller already has this source (named, never a second
 * copy), somebody else has shared it (registering anyway is the caller's choice), and the license
 * leaves the type out.
 */
export function SourceDialog({ type, onClose }: { type: string; onClose: () => void }) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const descriptor = useCatalogType(type)
  const fields = useMemo(() => descriptor?.fields ?? [], [descriptor])
  const initial = useMemo(() => initialFieldValues(fields), [fields])
  const [edits, setEdits] = useState<Record<string, string>>({})
  const values = { ...initial, ...edits }
  const [problems, setProblems] = useState<Record<string, FieldProblem>>({})
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  // Set when the server reports a matching source shared by another user (ConnectorController's
  // duplicate check) - never for the caller's own, which the server names instead.
  const [duplicateMatch, setDuplicateMatch] = useState<{ matchedConnectorName: string } | null>(null)
  const fieldsShake = useShake()

  // The connection this source reads through - the service's default unless another is picked.
  // Idle for a type that needs none.
  const connection = useConnectionChoice(descriptor?.protocol ?? undefined, undefined, descriptor?.type)

  // Subfolders start ON: a repository or a library has always been read whole, and a new source
  // should not quietly read only its top folder.
  const hasRecursive = !!descriptor?.capabilities.recursiveScope
  const [recursive, setRecursive] = useState(true)

  // File types start where a local folder does - the office types, from the server's catalog - so
  // every way of adding files behaves alike. Null until the selection is changed; clearing it means
  // every supported type.
  const hasFileScope = !!descriptor?.capabilities.fileTypeFilter
  const { data: fileTypeCatalog } = useQuery({
    queryKey: keys.fileTypeCatalog(),
    queryFn: indexingApi.getFileTypeCatalog,
    staleTime: Infinity,
    enabled: hasFileScope,
  })
  const [chosenFileTypes, setFileTypes] = useState<string[] | null>(null)
  const fileTypes = chosenFileTypes ?? fileTypeCatalog?.defaults ?? []

  const { dirty: otherDirty } = useFormDirty({ recursive, chosenFileTypes })
  const dirty = otherDirty || fieldValuesChanged(fields, initial, values)

  const onField = (key: string, value: string) => {
    setEdits(e => ({ ...e, [key]: value }))
    setProblems(p => {
      if (!(key in p)) return p
      const next = { ...p }
      delete next[key]
      return next
    })
  }

  const noun = descriptor ? t(descriptor.noun) : ''

  const handleAdd = () => {
    const found = fieldProblems(fields, values)
    setProblems(found)
    if (Object.keys(found).length > 0) {
      fieldsShake.trigger()
      return
    }
    save(false)
  }

  const save = async (force: boolean) => {
    setSaving(true)
    setError('')
    try {
      await indexingApi.register({
        type,
        config: submittedFieldValues(fields, values),
        // Always named, the default included: the picker showed which connection this would be,
        // and a default moved in another tab meanwhile must not change the answer.
        ...(connection.selected ? { connectionId: connection.selected.id } : {}),
        ...(hasRecursive ? { recursive } : {}),
        ...(hasFileScope && fileTypes.length > 0 ? { fileTypes: fileTypes.join(',') } : {}),
        ...(force ? { force: 'true' } : {}),
      })
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
      qc.invalidateQueries({ queryKey: keys.connectorsByType() })
      addToast(t('{type} added - indexing started', { type: noun }), 'success')
      onClose()
    } catch (e: unknown) {
      const ax = e as { response?: { status?: number; data?: { error?: string; detail?: string; matchedConnectorName?: string; name?: string } }; message?: string }
      // The caller already has this source (ConnectorService.asNewSource) - said in the reader's
      // language from the name the server returns, never as its English sentence.
      if (ax.response?.status === 409 && ax.response.data?.error === 'already_registered') {
        setError(t('"{name}" is already one of your sources. Open it on the Sources page to sync it or change its settings.', { name: ax.response.data.name ?? '' }))
        return
      }
      if (ax.response?.status === 409 && ax.response.data?.error === 'duplicate') {
        setDuplicateMatch({ matchedConnectorName: ax.response.data.matchedConnectorName ?? t('another source') })
        return
      }
      // A type the license leaves out is a 403 { error: 'not_licensed', detail }. `error` is a
      // machine code, so the sentence is the detail.
      if (ax.response?.status === 403 && ax.response.data?.error === 'not_licensed') {
        setError(ax.response.data.detail || t('{service} needs an Enterprise license.', { service: descriptor ? t(descriptor.displayName) : type }))
        return
      }
      // Every other refusal is a sentence written to be read - a field the server's own check
      // refused, a project the connection cannot see - and never axios's own English.
      setError(apiErrorText(e) ?? t('Failed to register'))
    } finally {
      setSaving(false)
    }
  }

  const connectionBlocked = connection.blocked
  const title = duplicateMatch ? t('Already added by someone else') : descriptor ? t(descriptor.addTitle) : t('Add a source')

  return (
    <ModalShell onClose={onClose} closable={!saving} dirty={dirty} initialFocus="first-field" size="lg" ariaLabel={title}>
      {requestClose => (<>
        <DialogHeader
          icon={<SourceTypeMark type={type} size={20} className="text-brand-legible" />}
          title={title}
        />

        <DialogBody className="flex-1 flex flex-col justify-center">
          {!descriptor ? (
            // The catalog is still on its way - it is asked for as the app starts, so this is brief.
            <FieldSkeleton />
          ) : duplicateMatch ? (
            <DialogNotice>
              {/* One sentence with the name in its slot, so a language can put the name where it goes. */}
              <p>
                <Rich
                  text={t('This matches {name}, already shared by another user. Skipping uses that copy at no cost; registering your own uses your own sync and storage capacity.')}
                  parts={{ name: <span className="text-ink-primary">{duplicateMatch.matchedConnectorName}</span> }}
                />
              </p>
            </DialogNotice>
          ) : connectionBlocked ? (
            /* Instead of the form: there is nothing for this source to read through. The picker
               says why and offers the fix in place; adding a connection selects it and brings the
               form back. */
            <ConnectionPicker choice={connection} addInFooter />
          ) : (
            <FormStack className="animate-fade-in">
              {descriptor.description && <Caption>{t(descriptor.description)}</Caption>}
              {/* Where the source lives first, then what it is there. */}
              <ConnectionPicker choice={connection} disabled={saving} />
              <div className={fieldsShake.shaking ? 'animate-shake' : undefined}>
                <FieldSpecForm fields={fields} values={values} onChange={onField} problems={problems} disabled={saving} />
              </div>
              {/* The descriptor is here by now, so these never come and go while the form is up. */}
              {hasRecursive && <SubfoldersSwitch checked={recursive} onChange={setRecursive} disabled={saving} />}
              {hasFileScope && (
                <>
                  <Divider />
                  <FileTypeFilter value={fileTypes} onChange={setFileTypes} disabled={saving} />
                </>
              )}
              <Presence show={!!error}>
                <InlineNotice tone="danger" size="md" icon={AlertTriangle}>{error}</InlineNotice>
              </Presence>
            </FormStack>
          )}
        </DialogBody>

        {/* A blocked form with a connection list to pick from has nothing to do down here - the fix
            is the picker above - so it has no footer at all; a dialog closes on its backdrop and
            Escape. */}
        {!(connectionBlocked && !duplicateMatch && !connection.none) && (
          <DialogFooter>
            {duplicateMatch ? (
              <>
                <Button variant="secondary" onClick={onClose} disabled={saving}>{t('Skip')}</Button>
                <Button onClick={() => save(true)} loading={saving}>
                  {saving ? t('Adding...') : t('Add anyway')}
                </Button>
              </>
            ) : connectionBlocked ? (
              /* No Add button at all - there is nothing to add against. Having no connection yet is
                 the first step, not an error: adding one is the primary action. */
              <>
                <Button variant="secondary" onClick={requestClose}>{t('Cancel')}</Button>
                <Button onClick={connection.startAdding}>{t('Add a connection')}</Button>
              </>
            ) : (
              <>
                <Button variant="secondary" onClick={requestClose} disabled={saving}>{t('Cancel')}</Button>
                {/* connection.loading closes the moment the form is on screen while the connection
                    list is in flight: adding then would name no connection at all. */}
                <Button onClick={handleAdd} loading={saving} disabled={!descriptor || connection.loading}>
                  {saving ? t('Adding...') : t('Add')}
                </Button>
              </>
            )}
          </DialogFooter>
        )}
      </>)}
    </ModalShell>
  )
}
