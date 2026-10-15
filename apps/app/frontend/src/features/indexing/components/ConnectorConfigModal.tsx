import { useState } from 'react'
import { AlertTriangle } from 'lucide-react'
import {
  Button, Caption, DialogBody, DialogFooter, DialogHeader, DialogNotice, Divider, FormStack, InlineNotice,
  Input, Presence,
} from '@/components/ui/kit'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { useShake } from '@/components/ui/kit'
import { useCatalogType } from '../hooks/useConnectorCatalog'
import { useWebCrawlLimits } from '../hooks/useWebCrawlLimits'
import { FileTypeFilter } from './FileTypeFilter'
import { ConnectionPicker, useConnectionChoice } from './ConnectionPicker'
import { SubfoldersSwitch, WebCrawlFields, type WebCrawlValues } from './SourceScopeFields'
import { indexingApi } from '../services/indexingApi'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { apiErrorText } from '@/services/apiError'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { SourceTypeMark } from '../utils/sourceMarks'

/**
 * The two source types whose ADD dialog is their own (`customDialog` in their descriptors) rather
 * than the generic SourceDialog: a web page, whose crawl bounds come from the server and whose large
 * page budgets earn a warning, and a network share, which says how its files are read. Every other
 * type is drawn from its descriptor (SourceDialog); a folder and uploads have dialogs of their own.
 *
 * A literal union rather than a narrowing of indexing.types' ConnectorType, which is a plain string
 * since the catalog took over the list of types - there is nothing there to narrow.
 */
type CustomType = 'webpage' | 'networkshare'

interface Props {
  type: CustomType
  onClose: () => void
}

// No server in these. A network share's file server comes from the CONNECTION it reads through,
// picked in the ConnectionPicker above these fields. The backend normalizes \\host\share,
// //host/share and smb://host/share to one form, so any of them may be pasted; the placeholder
// shows the one Explorer's address bar gives. A web page's other options are rendered below.
const FIELDS: Record<CustomType, Array<{ key: string; label: string; placeholder: string }>> = {
  networkshare: [{ key: 'uncPath', label: 'Network path', placeholder: '\\\\fileserver\\share\\folder' }],
  webpage: [{ key: 'seedUrl', label: 'Starting URL', placeholder: 'https://docs.example.com/' }],
}

export function ConnectorConfigModal({ type, onClose }: Props) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const fields = FIELDS[type]
  const descriptor = useCatalogType(type)
  const [values, setValues] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  // Set when the backend reports this config+name already matches a connector shared by another
  // user (see ConnectorController.duplicateCheck) - never set for the requester's own matching
  // connector, which the backend transparently resyncs instead, same as before this feature.
  const [duplicateMatch, setDuplicateMatch] = useState<{ matchedConnectorName: string } | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const fieldsShake = useShake()

  // The connection this source reads through - the service's default unless another is picked.
  // Idle for a web page, which needs none.
  const connection = useConnectionChoice(descriptor?.protocol ?? undefined, undefined, descriptor?.type)

  // The form starts small - a share's own level, three links deep, a hundred pages - so a first
  // sync finishes while someone is watching, and anyone raises it from there.
  const [recursive, setRecursive] = useState(false)

  // File types for the remote file-based sources. They start where a local folder does - the
  // office types, seeded from the backend's catalog (the maintainer's call, 2026-09-27) - so the
  // two ways of adding files behave alike. Null until the user changes the selection; clearing it
  // means every supported type, as it does for a folder.
  const hasFileScope = !!descriptor?.capabilities.fileTypeFilter
  const { data: fileTypeCatalog } = useQuery({
    queryKey: keys.fileTypeCatalog(),
    queryFn: indexingApi.getFileTypeCatalog,
    staleTime: Infinity,
    enabled: hasFileScope,
  })
  const [chosenFileTypes, setFileTypes] = useState<string[] | null>(null)
  const fileTypes = chosenFileTypes ?? fileTypeCatalog?.defaults ?? []

  // Web Page crawl options (only used when type === 'webpage'). Bounds and defaults come from the
  // backend (see useWebCrawlLimits) rather than being written out here - this modal used to impose
  // no bounds at all, so a depth of 300 was accepted, stored, and then silently clamped to the
  // backend's real ceiling with nothing anywhere reporting the change.
  //
  // The depth and the page budget show the server's defaults until one is typed, so the form opens
  // on what the server would use even when its limits answer after the first render.
  const limits = useWebCrawlLimits()
  const [wpName, setWpName] = useState('')
  const [crawlEdits, setCrawlEdits] = useState<Partial<WebCrawlValues>>({})
  const crawl: WebCrawlValues = {
    maxDepth: String(limits.defaultDepth),
    maxPages: String(limits.defaultPages),
    sameDomainOnly: true,
    renderJs: false,
    ...crawlEdits,
  }

  // Everything the user can type or toggle here, against what the form opened with. A field typed
  // into and cleared again is an empty string rather than a missing key, and is not a change.
  const typedValues = Object.fromEntries(Object.entries(values).filter(([, v]) => v !== ''))
  const { dirty } = useFormDirty({ values: typedValues, recursive, wpName, crawlEdits, chosenFileTypes })

  // Nothing to read through - no connection of this service, or a Microsoft 365 one nobody is
  // signed in to. Decided only once the list has answered (see useConnectionChoice): a source with
  // nothing to read through would register, sync nothing, and report it as a sync failure later on
  // a page the user is no longer looking at. Network shares joined the connections on 2026-10-01,
  // which retired this dialog's own readiness check and domain-account links.
  const connectionBlocked = connection.blocked

  const handleRegisterClick = () => {
    const errors: Record<string, string> = {}
    for (const f of fields) {
      if ((values[f.key] ?? '').trim() === '') errors[f.key] = t('Required')
    }
    if (type === 'webpage') {
      const depth = Number(crawl.maxDepth)
      const pages = Number(crawl.maxPages)
      if (!Number.isInteger(depth) || depth < 0 || depth > limits.maxDepth) {
        errors.wpMaxDepth = t('Must be a whole number between 0 and {max}', { max: limits.maxDepth })
      }
      if (!Number.isInteger(pages) || pages < 1 || pages > limits.maxPages) {
        errors.wpMaxPages = t('Must be a whole number between 1 and {max}', { max: limits.maxPages })
      }
    }
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors)
      fieldsShake.trigger()
      return
    }
    setFieldErrors({})
    save(false)

  }

  const save = async (force = false) => {
    setSaving(true)
    setError('')
    try {
      const payload = {
        ...values,
        // A string, because that endpoint reads a Map<String, String>.
        ...(type === 'networkshare' ? { recursive: String(recursive) } : {}),
        ...(hasFileScope && fileTypes.length > 0 ? { fileTypes: fileTypes.join(',') } : {}),
        // Always named, the default included: the picker showed which connection this would be,
        // and a default moved in another tab meanwhile must not change the answer.
        ...(connection.selected ? { connectionId: connection.selected.id } : {}),
        ...(type === 'webpage' ? {
          name: wpName.trim() || undefined,
          maxDepth: Number(crawl.maxDepth) || limits.defaultDepth,
          maxPages: Number(crawl.maxPages) || limits.defaultPages,
          sameDomainOnly: crawl.sameDomainOnly,
          renderJs: crawl.renderJs,
        } : {}),
        ...(force ? { force: 'true' } : {}),
      }
      await indexingApi.registerCustom(type, payload)
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
      qc.invalidateQueries({ queryKey: keys.connectorsByType() })
      addToast(t('{type} added - indexing started', { type: descriptor ? t(descriptor.noun) : type }), 'success')
      onClose()
    } catch (e: unknown) {
      const ax = e as { response?: { status?: number; data?: { error?: string; detail?: string; matchedConnectorName?: string; message?: string; name?: string } }; message?: string }
      // This caller already has the source (ConnectorService.asNewSource) - said in the reader's
      // language from the name the server returns, never as its English sentence.
      if (ax.response?.status === 409 && ax.response.data?.error === 'already_registered') {
        setError(t('"{name}" is already one of your sources. Open it on the Sources page to sync it or change its settings.', { name: ax.response.data.name ?? '' }))
        return
      }
      if (ax.response?.status === 409 && ax.response.data?.error === 'duplicate') {
        setDuplicateMatch({ matchedConnectorName: ax.response.data.matchedConnectorName ?? t('another source') })
        return
      }
      // The server's own sentence - a rejected crawl bound, a malformed seed URL (a ProblemDetail's
      // `detail`) - never a machine code or axios's "Request failed with status code 400".
      setError(apiErrorText(e) ?? t('Failed to register'))
    } finally { setSaving(false) }
  }

  const title = duplicateMatch ? t('Already added by someone else') : descriptor ? t(descriptor.addTitle) : t('Add a source')

  return (
    <ModalShell
      onClose={onClose}
      closable={!saving}
      dirty={dirty}
      initialFocus="first-field"
      size="lg"
      ariaLabel={title}
    >
      {requestClose => (<>
        <DialogHeader
          // The type's mark before the title, as on the source's card once it exists.
          icon={<SourceTypeMark type={type} size={20} className="text-brand-legible" />}
          title={title}
        />

        <DialogBody className="flex-1 flex flex-col justify-center">
          {duplicateMatch ? (
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
               says which of the two it is - no connection of this service, or a Microsoft 365 one
               nobody has signed in to - and offers the fix in place; adding a connection selects
               it and brings the form back. */
            <ConnectionPicker choice={connection} addInFooter />
          ) : (
            <FormStack className="animate-fade-in">
              {/* Where the source lives first, then what it is there. */}
              <ConnectionPicker choice={connection} disabled={saving} />
              <FormStack className={fieldsShake.shaking ? 'animate-shake' : undefined}>
                {fields.map(f => (
                  <Input
                    key={f.key}
                    label={t(f.label)}
                    placeholder={f.placeholder}
                    value={values[f.key] ?? ''}
                    onChange={e => {
                      setValues(v => ({ ...v, [f.key]: e.target.value }))
                      setFieldErrors(prev => {
                        if (!(f.key in prev)) return prev
                        const next = { ...prev }
                        delete next[f.key]
                        return next
                      })
                    }}
                    error={fieldErrors[f.key]}
                  />
                ))}
              </FormStack>
              {/* The type is fixed for the dialog's life, so these blocks never come and go. */}
              {type === 'networkshare' && (
                <>
                  <SubfoldersSwitch checked={recursive} onChange={setRecursive} disabled={saving} />
                  <Caption>
                    {t('Files are read with your own domain account, so only what you can open is indexed. The source cannot be shared.')}
                  </Caption>
                </>
              )}
              {type === 'webpage' && (
                <>
                  <Input
                    label={t('Name (optional)')}
                    placeholder={t("Defaults to the page's own title")}
                    value={wpName}
                    onChange={e => setWpName(e.target.value)}
                  />
                  <WebCrawlFields
                    values={crawl}
                    onChange={patch => {
                      setCrawlEdits(c => ({ ...c, ...patch }))
                      setFieldErrors(prev => {
                        const next = { ...prev }
                        if ('maxDepth' in patch) delete next.wpMaxDepth
                        if ('maxPages' in patch) delete next.wpMaxPages
                        return next
                      })
                    }}
                    limits={limits}
                    errors={{ maxDepth: fieldErrors.wpMaxDepth, maxPages: fieldErrors.wpMaxPages }}
                    disabled={saving}
                  />
                </>
              )}
              {/* The same picker a folder has, below everything that says WHERE the source is. It
                  waits on the type's descriptor, so it arrives rather than appearing. */}
              <Presence show={hasFileScope}>
                <FormStack>
                  <Divider />
                  <FileTypeFilter value={fileTypes} onChange={setFileTypes} disabled={saving} />
                </FormStack>
              </Presence>
              <Presence show={!!error}>
                <InlineNotice tone="danger" size="md" icon={AlertTriangle}>{error}</InlineNotice>
              </Presence>
            </FormStack>
          )}
        </DialogBody>

        {/* A blocked form with a connection list to pick from has nothing to do down here - the
            fix is the picker above - so it has no footer at all rather than a lone Close: a dialog
            closes on its backdrop and Escape. */}
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
              /* No Register button at all - there is nothing to register against, and a disabled
                 one invites people to hunt for what would enable it. Having no connection yet is
                 the first step, not an error: adding one is the primary action, where a dialog's
                 next step always sits. Saving it selects it and brings the form back (see
                 ConnectionPicker). */
              <>
                <Button variant="secondary" onClick={requestClose}>{t('Cancel')}</Button>
                <Button onClick={connection.startAdding}>{t('Add a connection')}</Button>
              </>
            ) : (
              <>
                <Button variant="secondary" onClick={requestClose} disabled={saving}>
                  {t('Cancel')}
                </Button>
                {/* connection.loading closes the one gap the branch above leaves: the form is on
                    screen for the moment the connection list is in flight, and registering in that
                    moment would name no connection at all. It is false for every type that needs
                    none, which never asks. */}
                <Button onClick={handleRegisterClick} loading={saving} disabled={connection.loading}>
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
