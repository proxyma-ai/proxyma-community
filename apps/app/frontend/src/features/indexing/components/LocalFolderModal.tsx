import { useState, useEffect, useRef, useCallback } from 'react'
import { AlertTriangle, CheckCircle2, FolderOpen } from 'lucide-react'
import type { ReactNode } from 'react'
import {
  Button, DialogBody, DialogDescription, DialogFooter, DialogHeader, Divider, FadeSwap, FormColumns, FormStack, InlineNotice,
  Input, MetaList, Presence, Readout, cn,
} from '@/components/ui/kit'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { useShake } from '@/components/ui/kit'
import { useDebounce } from '@/hooks/useDebounce'
import { useAppConfig } from '@/hooks/useAppConfig'
import { apiErrorText } from '@/services/apiError'
import { formatFileSize, formatCompactCount } from '@/utils/formatters'
import { FileTypeFilter } from './FileTypeFilter'
import { SubfoldersSwitch } from './SourceScopeFields'
import { indexingApi } from '../services/indexingApi'
import { useTranslation } from '@/i18n'
import { ModalShell } from '@/components/ui/ModalShell'
import { sameValue } from '@/components/ui/formSnapshot'
import { SourceTypeMark } from '../utils/sourceMarks'

// Desktop counterpart to FileUploadModal. Desktop registers a REFERENCE to a folder already on
// the user's disk - no bytes are copied anywhere - so there is no destination folder to pick, no
// quota, no duplicate resolution and no upload progress; the only thing this modal has to
// establish is an absolute path. The browser cannot supply one (see LocalPathController's own
// comment), so it comes from a native chooser the local backend puts on screen.
interface Props {
  // Name of the folder the user dropped, when this was opened from a drop rather than the menu.
  // Passed to the chooser as a pre-filled name - it cannot navigate there (the browser never told
  // us where the folder lives), but it makes the right folder obvious once the user reaches its
  // parent directory.
  suggestedName?: string
  // Set for a drop the backend could resolve to a real path on this machine (see
  // LocalPathController.resolveDroppedFolder). Seeds the path field so the drop lands on the folder
  // the user actually dragged, with no chooser at all. Mutually exclusive with autoBrowse.
  //
  // Arrives LATE: the modal is mounted the instant the folder is dropped, and this lands when the
  // lookup answers, so it is applied by an effect rather than only as the initial state.
  initialPath?: string
  // True while that lookup is still running, so the modal can say it is working instead of showing
  // an empty path field that looks like a drop the app ignored.
  resolvingDrop?: boolean
  // Set for a drop that could NOT be resolved, and says which way it failed. The modal explains it
  // beside the path field and leaves Browse to the user.
  //
  // This was `autoBrowse` until 2026-09-13, and opened the native chooser by itself - the user saw
  // the modal they expected and then a folder dialog they did not ask for, parked on Documents.
  //   not-a-folder - the drop was loose files or several items; only one folder can be indexed in place
  //   not-found    - a folder, but no directory on this machine matched it (or it could not be read)
  dropProblem?: 'not-a-folder' | 'not-found'
  onClose: () => void
}

// An example of a path on THIS machine's system - the backend reports which one it runs on, and a
// desktop's backend is the machine the folder is on. Not prose: a format example, untranslated.
const PATH_EXAMPLE: Record<string, string> = {
  windows: 'C:\\Users\\you\\Documents\\specs',
  macos: '/Users/you/Documents/specs',
  linux: '/home/you/Documents/specs',
}

export function LocalFolderModal({ suggestedName, initialPath, resolvingDrop, dropProblem, onClose }: Props) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)

  const [path, setPath] = useState(initialPath ?? '')
  const [browsing, setBrowsing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const pathShake = useShake()

  // Off by default, so the first look at a folder is its own level; one switch turns subfolders on,
  // on every tier.
  const [recursive, setRecursive] = useState(false)
  const effectiveRecursive = recursive
  const platform = useAppConfig().data?.platform

  // Office documents only to start with - the overwhelmingly common case for a folder someone
  // points Proxyma at, and the one selection that doesn't drag a source tree's worth of .c/.h
  // files into the index by default. Seeded from the backend so this modal isn't a second copy
  // of which extensions "office" means.
  const { data: fileTypeCatalog } = useQuery({
    queryKey: keys.fileTypeCatalog(),
    queryFn: indexingApi.getFileTypeCatalog,
    staleTime: Infinity,
  })
  const [fileTypes, setFileTypes] = useState<string[] | null>(null)
  const effectiveFileTypes = fileTypes ?? fileTypeCatalog?.defaults ?? []
  const fileTypesParam = effectiveFileTypes.join(',')

  const debouncedPath = useDebounce(path.trim(), 400)
  const debouncedFileTypes = useDebounce(fileTypesParam, 400)
  const { data: inspection, isFetching: inspecting } = useQuery({
    queryKey: keys.localFolder(debouncedPath, effectiveRecursive, debouncedFileTypes),
    queryFn: () => indexingApi.inspectFolder({
      path: debouncedPath,
      recursive: effectiveRecursive,
      // Omitted rather than sent empty when nothing is selected, so the backend's
      // "absent means every supported type" default is what applies.
      fileTypes: debouncedFileTypes || undefined,
    }),
    enabled: debouncedPath.length > 0,
  })

  const browse = useCallback(async () => {
    setError('')
    setBrowsing(true)
    try {
      // Stays open for as long as the native chooser is on screen - there is no axios timeout,
      // and "cancelled" comes back as a normal 200 rather than an error.
      const res = await indexingApi.browseFolder(suggestedName)
      if (res.cancelled) {
        if (res.reason) setError(res.reason)
        return
      }
      if (res.path) setPath(res.path)
    } catch {
      setError(t('Could not open the folder chooser. Type or paste a folder path below instead.'))
    } finally {
      setBrowsing(false)
    }
  }, [suggestedName])

  // Applies a late-arriving resolved path, ONCE and only while the field is untouched. A user who
  // started typing a path of their own while the lookup ran keeps what they typed - having the
  // field overwritten under the cursor would be worse than the wait it replaced.
  const seededFromDrop = useRef(false)
  useEffect(() => {
    if (initialPath && !seededFromDrop.current && path === '') {
      seededFromDrop.current = true
      setPath(initialPath)
    }
  }, [initialPath, path])

  const register = async () => {
    const trimmed = path.trim()
    if (!trimmed) {
      setError(t('Choose a folder first'))
      pathShake.trigger()
      return
    }
    setSaving(true)
    setError('')
    try {
      await indexingApi.registerLocalFolder({
        path: trimmed,
        recursive: effectiveRecursive,
        // Empty string, not omitted: an explicit "index every supported type" from the user is a
        // real choice, and the backend treats blank and absent identically anyway.
        fileTypes: fileTypesParam,
      })
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
      qc.invalidateQueries({ queryKey: keys.connectorsByType() })
      addToast(t('Folder added - indexing started'), 'success')
      onClose()
    } catch (e: unknown) {
      const ax = e as { response?: { status?: number; data?: { error?: string; name?: string } } }
      if (ax.response?.status === 409 && ax.response.data?.error === 'already_registered') {
        setError(t('"{name}" is already one of your sources. Open it on the Sources page to sync it or change its settings.', { name: ax.response.data.name ?? '' }))
      } else {
        // The server's sentence - never a machine code or axios's "Request failed with status code 400".
        setError(apiErrorText(e) ?? t('Failed to add folder'))
      }
    } finally {
      setSaving(false)
    }
  }

  // Only a definitive negative from the backend blocks registration - an inspect call that failed
  // outright leaves `inspection` undefined, which must not stop a path the user is sure about.
  const unusable = !!inspection && !inspection.readable
  const canRegister = path.trim().length > 0 && !unusable && !saving && !browsing

  // Measured against what the dialog was OPENED WITH - including a dropped folder's path, which
  // can arrive after the first render, and the backend's default file types - so neither of those
  // arriving reads as an edit.
  const dirty = !sameValue(
    { path: path.trim(), recursive, fileTypes: effectiveFileTypes },
    { path: (initialPath ?? '').trim(), recursive: false, fileTypes: fileTypeCatalog?.defaults ?? [] },
  )

  // What the folder check says, keyed so a change of answer fades from one to the next. Null while
  // there is nothing to say (no path yet, or a check that failed outright).
  const inspectionNotice = ((): { key: string; node: ReactNode } | null => {
    if (debouncedPath.length === 0) return null
    if (inspecting) return { key: 'checking', node: <InlineNotice tone="progress">{t('Checking folder...')}</InlineNotice> }
    if (!inspection) return null
    if (!inspection.exists) {
      return { key: 'missing', node: <InlineNotice tone="danger" icon={AlertTriangle}>{t('No folder exists at that path.')}</InlineNotice> }
    }
    if (!inspection.isDirectory) {
      return { key: 'file', node: <InlineNotice tone="danger" icon={AlertTriangle}>{t('That path is a file, not a folder.')}</InlineNotice> }
    }
    if (!inspection.readable) {
      return { key: 'unreadable', node: <InlineNotice tone="danger" icon={AlertTriangle}>{t('Proxyma cannot read that folder.')}</InlineNotice> }
    }
    if ((inspection.supportedFileCount ?? 0) === 0) {
      return {
        key: 'empty',
        node: (
          <InlineNotice tone="warning" icon={AlertTriangle}>
            {/* Two situations used to share one sentence, and they need opposite things from the
                user: files sitting in subfolders (by far the more common - people drag the folder
                that CONTAINS their documents), versus a folder where nothing matches the selected
                types at all. */}
            {inspection.hasSubdirectoryFiles ? (
              <>
                {t('Nothing directly in this folder, but there are supported files in its subfolders.')}{' '}
                {t('Turn on "Include subfolders" below to index them.')}
              </>
            ) : (
              // TWO WHOLE SENTENCES rather than one with a conditional fragment spliced into its
              // middle - which is not a quoted literal and could never have matched a catalog entry.
              effectiveRecursive
                ? t('No supported files found. Only the file types highlighted below are indexed.')
                : t('No supported files found directly in this folder. Only the file types highlighted below are indexed.')
            )}
          </InlineNotice>
        ),
      }
    }
    return {
      key: 'ok',
      node: (
        <InlineNotice tone="success" icon={CheckCircle2}>
          <MetaList items={[
            // The count arrives compacted ("1.2k"), so it reaches the sentence as `shown` while the
            // plural is picked from the number itself.
            inspection.truncated
              ? t('Over {shown} files to index', { shown: formatCompactCount(inspection.supportedFileCount ?? 0) })
              : t('{shown} file to index|{shown} files to index', {
                count: inspection.supportedFileCount ?? 0,
                shown: formatCompactCount(inspection.supportedFileCount ?? 0),
              }),
            <Readout key="size">{formatFileSize(inspection.totalBytes ?? 0)}</Readout>,
          ]} />
        </InlineNotice>
      ),
    }
  })()
  const title = t('Add a local folder')

  return (
    <ModalShell
      onClose={onClose}
      closable={!saving}
      dirty={dirty}
      size="form"
      ariaLabel={title}
    >
      {requestClose => (<>
        <DialogHeader
          // The type's mark before the title, as on the source's card once it exists.
          icon={<SourceTypeMark type="files" size={20} className="text-brand-legible" />}
          title={title}
        />

        {/* Two columns from `md` - the folder on the left, the file-type picker on the right. The
            picker made this dialog most of a screen tall, and stacked under the folder it needed a
            scrollbar; side by side the whole dialog fits a normal window. */}
        <DialogBody>
        <FormColumns from="md" density="columns">
          <FormStack className="min-w-0">
          <DialogDescription>
            {t('Proxyma indexes these files where they are. Nothing is copied, moved, or sent anywhere.')}
          </DialogDescription>

          {/* Browse sits to the RIGHT of the path field, on the same row: it is the shortcut for
              filling that field in, so reading left-to-right it belongs after the thing it fills,
              not stacked above it as a separate step. items-end keeps it aligned to the input
              itself rather than to the top of the label above it. */}
          <div className={cn('flex items-end gap-2', pathShake.shaking && 'animate-shake')}>
            <div className="flex-1 min-w-0">
              <Input
                label={t('Folder path')}
                placeholder={platform ? PATH_EXAMPLE[platform] : undefined}
                value={path}
                onChange={e => { setPath(e.target.value); setError('') }}
                disabled={browsing || saving}
              />
            </div>
            <Button variant="secondary" onClick={browse} loading={browsing} disabled={saving}>
              {browsing
                ? t('Waiting...')
                : <><FolderOpen size={16} />{t('Browse...')}</>}
            </Button>
          </div>

          <Presence show={!!resolvingDrop && path === ''}>
            <InlineNotice tone="progress">{t('Finding that folder on this machine...')}</InlineNotice>
          </Presence>

          {/* Only while the field is still empty: once the user has browsed or typed a path, the
              drop that failed is no longer the thing to talk about. */}
          <Presence show={!!dropProblem && path === ''}>
            <InlineNotice tone="warning" icon={AlertTriangle}>
              {dropProblem === 'not-a-folder'
                ? t('Drop a folder rather than individual files - Proxyma indexes a whole folder where it is. Or choose one with Browse.')
                : suggestedName
                  ? t('Could not find "{name}" on this machine. Choose it with Browse, or paste its path.', { name: suggestedName })
                  : t('Could not find that folder on this machine. Choose it with Browse, or paste its path.')}
            </InlineNotice>
          </Presence>

          <Presence show={!!inspectionNotice}>
            <FadeSwap swapKey={inspectionNotice?.key}>{inspectionNotice?.node}</FadeSwap>
          </Presence>

          <SubfoldersSwitch checked={effectiveRecursive} onChange={setRecursive} disabled={saving} />

          <Presence show={!!error}>
            <InlineNotice tone="danger" size="md" icon={AlertTriangle}>{error}</InlineNotice>
          </Presence>
          </FormStack>

          {/* The rule between the two halves: above the picker while they are stacked, beside
              it once they are columns. */}
          <Divider className="md:hidden" />
          <div className="min-w-0 md:pl-8 md:border-l border-line/30">
            <FileTypeFilter
              value={effectiveFileTypes}
              onChange={setFileTypes}
              disabled={browsing || saving}
            />
          </div>
        </FormColumns>
        </DialogBody>

        <DialogFooter>
          <Button variant="secondary" onClick={requestClose} disabled={saving}>{t('Cancel')}</Button>
          <Button onClick={register} loading={saving} disabled={!canRegister}>
            {saving ? t('Adding...') : t('Add folder')}
          </Button>
        </DialogFooter>
      </>)}
    </ModalShell>
  )
}
