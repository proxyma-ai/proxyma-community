import { useState, useEffect, useRef, useCallback } from 'react'
import { Upload, ChevronRight, X, Check, AlertCircle, CheckCircle2, TriangleAlert, Circle } from 'lucide-react'
import {
  Button, Combobox, ConfirmDialog, DialogBody, DialogFooter, DialogHeader, DialogNotice, Disclosure, DropArea, FadeSwap, FormStack,
  IconButton, InlineNotice, ItemRow, List, MetaList, Presence, ProgressBar, ProgressRow, Readout, SegmentedControl, StateMark,
  StatusMark, TEXT, Tooltip, useFileDrop, useFilePicker, type ComboboxStatus, type WorkState,
} from '@/components/ui/kit'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { apiErrorText } from '@/services/apiError'
import axios from 'axios'
import { filesApi } from '../services/filesApi'
import { walkDataTransferItems, filesFromFileList, type DroppedFile, type WalkResult } from '../utils/dirEntryWalk'
import { formatFileSize as formatSize } from '@/utils/formatters'
import { useShake } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { SourceTypeMark } from '../utils/sourceMarks'

interface Props {
  initialFiles?: DroppedFile[]
  initialFolder?: string
  onClose: () => void
}

type FileStatus = 'pending' | 'uploading' | 'done' | 'failed' | 'cancelled' | 'skipped'
type ModalPhase = 'pick' | 'shared-duplicates' | 'duplicates' | 'uploading' | 'success' | 'cancelled' | 'partial'

// A file row's mark: its own glyph per status, coloured by the kit's work state. Done is the
// product's ink, not green (the kit's rule for every piece of work), and an upload in flight
// pulses rather than spinning an arrow, which read as a refresh.
const FILE_MARK: Record<FileStatus, { icon: typeof Check; state: WorkState }> = {
  pending: { icon: Circle, state: 'pending' },
  uploading: { icon: Upload, state: 'running' },
  done: { icon: Check, state: 'done' },
  failed: { icon: AlertCircle, state: 'failed' },
  cancelled: { icon: X, state: 'stopped' },
  skipped: { icon: ChevronRight, state: 'stopped' },
}

interface TrackedFile {
  id: string
  file: File
  relativePath: string
  status: FileStatus
  progress: number
  serverId?: string
  error?: string
  abortController?: AbortController
}

function newFileId(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`
}

// Signals a file's upload loop was aborted (Cancel clicked) while it was waiting out a 429
// backoff delay rather than mid-request - axios.isCancel() only recognizes its own request-level
// abort, not a rejection from our own setTimeout-based wait.
class UploadCancelledError extends Error {}

function sleepAbortable(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise(resolve => {
    if (signal.aborted) { resolve(); return }
    const timer = setTimeout(() => { signal.removeEventListener('abort', onAbort); resolve() }, ms)
    const onAbort = () => { clearTimeout(timer); resolve() }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

// Retry-After is either delay-seconds ("120") or an HTTP-date - both forms are valid per RFC 7231.
function parseRetryAfterMs(header: unknown): number | null {
  if (typeof header !== 'string' || !header) return null
  const seconds = Number(header)
  if (!Number.isNaN(seconds)) return seconds * 1000
  const dateMs = Date.parse(header)
  return Number.isNaN(dateMs) ? null : Math.max(0, dateMs - Date.now())
}

const MAX_RATE_LIMIT_RETRIES = 6
const RATE_LIMIT_BASE_DELAY_MS = 2000
const RATE_LIMIT_MAX_DELAY_MS = 30000

// Full path within the destination folder, used to identify a file uniquely across
// subfolders (two different subfolders can contain a same-named file).
function fullPath(relativePath: string, name: string): string {
  return relativePath ? `${relativePath}/${name}` : name
}

interface DuplicateChoice {
  path: string
  action: 'skip' | 'overwrite' | null
}

interface SharedDuplicateChoice {
  path: string
  matchedConnectorName: string
  ownedByRequester: boolean
  action: 'skip' | 'upload' | null
}

async function sha256Hex(file: File): Promise<string> {
  const buffer = await file.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('')
}

export function FileUploadModal({ initialFiles, initialFolder, onClose }: Props) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const [trackedFiles, setTrackedFiles] = useState<TrackedFile[]>(
    () => (initialFiles ?? []).map(f => ({
      id: newFileId(), file: f.file, relativePath: f.relativePath, status: 'pending' as FileStatus, progress: 0,
    })),
  )
  const trackedRef = useRef(trackedFiles)
  trackedRef.current = trackedFiles

  const [folderInput, setFolderInput] = useState(initialFolder ?? '')
  const [folderConfirmed, setFolderConfirmed] = useState(!!initialFolder)
  const [phase, setPhase] = useState<ModalPhase>('pick')
  const [error, setError] = useState('')
  const [folderError, setFolderError] = useState('')
  const folderShake = useShake()
  const [sharedDupError, setSharedDupError] = useState('')
  const sharedDupShake = useShake()
  const [dupError, setDupError] = useState('')
  const dupShake = useShake()
  const [listExpanded, setListExpanded] = useState(true)
  // "Cancel upload" on a partly failed batch deletes what did upload - asked first.
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const cancelledRef = useRef(false)
  const uploadedFolderRef = useRef<string | null>(null)

  const [duplicates, setDuplicates] = useState<DuplicateChoice[]>([])
  const [sharedDuplicates, setSharedDuplicates] = useState<SharedDuplicateChoice[]>([])

  // Covers the gap between clicking Upload and the first visible phase change: client-side
  // SHA-256 hashing of every picked file (checkSharedDuplicates) followed by two network
  // round-trips (check-shared-duplicates, check-duplicates). For a few thousand files this can
  // take many seconds with zero UI feedback otherwise, reading as a hung/unresponsive app.
  const [checkingPrereqs, setCheckingPrereqs] = useState(false)

  // Refetched after every batch completes (usage grows as files finish uploading in this same
  // session) so a second pick-more-files round in the same modal instance still checks live
  // numbers, not what was true when the modal first opened.
  const { data: quota } = useQuery({
    queryKey: keys.fileQuota(),
    queryFn: filesApi.quota,
    staleTime: 0,
  })

  // The destination folders the combobox offers.
  const foldersQuery = useQuery({ queryKey: keys.fileFolders(), queryFn: filesApi.folders })
  const folders = foldersQuery.data ?? []
  const foldersStatus: ComboboxStatus = foldersQuery.isPending ? 'loading' : foldersQuery.isError ? 'error' : 'success'

  useEffect(() => {
    if (initialFolder) ensureFolder(initialFolder)
  }, [initialFolder])

  const folderName = folderInput.trim()
  const folderExists = folders.includes(folderName)
  const hasFolderSelected = folderConfirmed || folderExists

  useEffect(() => {
    if (hasFolderSelected) setFolderError('')
  }, [hasFolderSelected])

  // The kit Combobox hands a picked suggestion back through the same onChange as a keystroke, so a
  // value naming an existing folder is treated as the pick it almost always is - confirmed and
  // ensured, as picking one always was. Typing a name in full counted as chosen before too
  // (folderExists); ensuring a folder that exists is a no-op on the server.
  const changeFolder = (v: string) => {
    setFolderInput(v)
    setFolderError('')
    if (folders.includes(v)) {
      setFolderConfirmed(true)
      ensureFolder(v)
    } else {
      setFolderConfirmed(false)
    }
  }

  const createFolder = (name: string) => {
    if (!name.trim()) return
    setFolderInput(name)
    setFolderError('')
    setFolderConfirmed(true)
    ensureFolder(name.trim())
  }

  const ensureFolder = async (name: string) => {
    try {
      await filesApi.ensureFolder(name)
    } catch (e: unknown) {
      // The server's sentence when it gave one - never axios's "Request failed with status code".
      setError(t('Failed to register folder: {reason}', { reason: apiErrorText(e) ?? t('Unknown error') }))
      setFolderConfirmed(false)
    }
  }

  const addFiles = useCallback((result: WalkResult) => {
    const { files: dropped, folderName: detectedFolderName } = result
    if (dropped.length === 0) return
    const newFiles: TrackedFile[] = dropped.map(d => ({
      id: newFileId(), file: d.file, relativePath: d.relativePath, status: 'pending' as FileStatus, progress: 0,
    }))
    setTrackedFiles(prev => [...prev, ...newFiles])
    if (detectedFolderName && !folderConfirmed && !folderInput.trim()) {
      setFolderInput(detectedFolderName)
      setFolderConfirmed(true)
      ensureFolder(detectedFolderName)
    }
  }, [folderConfirmed, folderInput])

  const removeFile = (index: number) => {
    setTrackedFiles(prev => prev.filter((_, i) => i !== index))
  }

  // The kit's drop state counts enters and leaves, so crossing the box's own glyph and text no
  // longer flickers the highlight off and on (this was a plain boolean).
  const drop = useFileDrop({
    enabled: phase === 'pick',
    onDrop: data => { walkDataTransferItems(data.items).then(addFiles) },
  })

  // The two pickers - files, and a whole folder - opened by the drop box and the link under it.
  const filePicker = useFilePicker({ onFiles: files => addFiles(filesFromFileList(files)), multiple: true })
  const folderPicker = useFilePicker({ onFiles: files => addFiles(filesFromFileList(files)), directory: true })

  // Sum of files still actually pending upload (done/failed/cancelled/skipped already resolved
  // one way or another and don't count toward what's *about* to be sent) - recomputed on every
  // render, so removing a file from the list immediately re-enables Upload once the total fits.
  const pendingUploadBytes = trackedFiles
      .filter(f => f.status === 'pending')
      .reduce((sum, f) => sum + f.file.size, 0)
  const remainingQuotaBytes = quota ? Math.max(0, quota.quotaBytes - quota.usedBytes) : null
  const exceedsQuota = remainingQuotaBytes !== null && pendingUploadBytes > remainingQuotaBytes

  const doneCount = trackedFiles.filter(f => f.status === 'done').length
  const failedCount = trackedFiles.filter(f => f.status === 'failed').length
  const skippedCount = trackedFiles.filter(f => f.status === 'skipped').length
  const cancelledCount = trackedFiles.filter(f => f.status === 'cancelled').length
  const totalCount = trackedFiles.length
  const activeCount = totalCount - cancelledCount - skippedCount
  const overallProgress = activeCount > 0
    ? trackedFiles
        .filter(f => f.status !== 'cancelled' && f.status !== 'skipped')
        .reduce((sum, f) => sum + (f.status === 'done' ? 100 : f.progress), 0) / activeCount
    : 0

  const updateFile = useCallback((index: number, patch: Partial<TrackedFile>) => {
    setTrackedFiles(prev => prev.map((f, i) => i === index ? { ...f, ...patch } : f))
  }, [])

  const removeFileFromList = useCallback((index: number) => {
    setTrackedFiles(prev => prev.filter((_, i) => i !== index))
  }, [])

  const cancelSingleFile = useCallback(async (index: number) => {
    const tf = trackedRef.current[index]
    if (!tf) return
    if (tf.status === 'uploading' && tf.abortController) {
      tf.abortController.abort()
      removeFileFromList(index)
    } else if (tf.status === 'done' && tf.serverId) {
      updateFile(index, { status: 'cancelled' })
      try {
        await filesApi.remove(tf.serverId)
      } catch { /* best effort */ }
      removeFileFromList(index)
      qc.invalidateQueries({ queryKey: keys.fileQuota() })
    } else if (tf.status === 'pending') {
      removeFileFromList(index)
    }
  }, [updateFile, removeFileFromList, qc])

  const checkDuplicates = async () => {
    // Reads trackedRef.current (not the trackedFiles closure) so a skip just applied by
    // confirmSharedDuplicates() is reflected immediately, without waiting on React's next render
    // pass - same reasoning as runUpload's use of trackedRef documented above.
    // Files already skipped in the shared-duplicates step never need a local-folder duplicate
    // check - they won't be uploaded at all.
    const activeFiles = trackedRef.current.filter(f => f.status !== 'skipped')
    const relativePaths = activeFiles.map(f => fullPath(f.relativePath, f.file.name))
    try {
      const dupes = await filesApi.checkDuplicates(folderName, relativePaths)
      if (dupes.length > 0) {
        setDuplicates(dupes.map(path => ({ path, action: null })))
        setCheckingPrereqs(false)
        setPhase('duplicates')
        return
      }
    } catch {
      // If check fails, proceed with upload anyway
    }
    runUpload(false)
  }

  const setDuplicateAction = (path: string, action: 'skip' | 'overwrite') => {
    setDuplicates(prev => prev.map(d => d.path === path ? { ...d, action } : d))
  }

  const setAllDuplicates = (action: 'skip' | 'overwrite') => {
    setDuplicates(prev => prev.map(d => ({ ...d, action })))
  }

  const allDuplicatesResolved = duplicates.every(d => d.action !== null)

  useEffect(() => {
    if (allDuplicatesResolved) setDupError('')
  }, [allDuplicatesResolved])

  // Hashes every file CLIENT-SIDE (Web Crypto) before any bytes are uploaded, and asks the
  // server whether an identical file is already registered - either shared by someone else, or
  // by the current user themselves elsewhere. Matches are opt-in to skip: the storage-quota
  // trade-off is spelled out per file so the user can still choose to upload their own copy.
  const checkSharedDuplicates = async () => {
    try {
      const candidates = await Promise.all(trackedFiles.map(async tf => ({
        relativePath: fullPath(tf.relativePath, tf.file.name),
        sizeBytes: tf.file.size,
        sha256: await sha256Hex(tf.file),
      })))
      const matches = await filesApi.checkSharedDuplicates(candidates)
      if (matches.length > 0) {
        setSharedDuplicates(matches.map(m => ({
          path: m.relativePath,
          matchedConnectorName: m.matchedConnectorName,
          ownedByRequester: m.ownedByRequester,
          action: null,
        })))
        setCheckingPrereqs(false)
        setPhase('shared-duplicates')
        return
      }
    } catch {
      // Hashing unsupported, or the check failed - proceed as if no matches were found rather
      // than blocking the upload on this best-effort step.
    }
    checkDuplicates()
  }

  const setSharedDuplicateAction = (path: string, action: 'skip' | 'upload') => {
    setSharedDuplicates(prev => prev.map(d => d.path === path ? { ...d, action } : d))
  }

  const setAllSharedDuplicates = (action: 'skip' | 'upload') => {
    setSharedDuplicates(prev => prev.map(d => ({ ...d, action })))
  }

  const allSharedDuplicatesResolved = sharedDuplicates.every(d => d.action !== null)

  useEffect(() => {
    if (allSharedDuplicatesResolved) setSharedDupError('')
  }, [allSharedDuplicatesResolved])

  const confirmSharedDuplicates = () => {
    const skippedPaths = sharedDuplicates.filter(d => d.action === 'skip').map(d => d.path)
    if (skippedPaths.length > 0) {
      setTrackedFiles(prev => prev.map(f =>
        skippedPaths.includes(fullPath(f.relativePath, f.file.name)) ? { ...f, status: 'skipped' as FileStatus } : f,
      ))
    }
    setTimeout(() => checkDuplicates(), 0)
  }

  // Upload (a pure storage operation) is deliberately decoupled from the file/folder connector
  // - the only link between them is this call, fired exactly once, only when the modal closes
  // after a successful upload (see handleClose below). It must never fire eagerly mid-upload:
  // registration re-syncs the connector against whatever is on disk for this folder right now,
  // which is only meaningful once uploading has actually finished.
  // Returns the tier-limit message on failure (e.g. a race with validateAndUpload's pre-flight
  // check - another tab registered the last available folder slot in between), or null on
  // success. Deliberately NOT best-effort: registration failing here means no RegisteredConnector
  // was ever created, so the uploaded bytes would otherwise sit as orphaned quota usage that no
  // scheduled sync will ever pick up (there's nothing registered to sync).
  const registerFolder = async (folder: string): Promise<string | null> => {
    try {
      await filesApi.registerFolder(folder)
      qc.invalidateQueries({ queryKey: keys.connectorSummary() })
      qc.invalidateQueries({ queryKey: keys.connectorsByType() })
      return null
    } catch (e: unknown) {
      return apiErrorText(e) ?? t('Could not start indexing for this folder.')
    }
  }

  const deleteUploadedFiles = async (ids: string[]) => {
    for (const id of ids) {
      try { await filesApi.remove(id) } catch { /* best effort */ }
    }
    qc.invalidateQueries({ queryKey: keys.files() })
    qc.invalidateQueries({ queryKey: keys.fileQuota() })
  }

  const confirmDuplicates = () => {
    const skippedPaths = duplicates.filter(d => d.action === 'skip').map(d => d.path)
    if (skippedPaths.length > 0) {
      setTrackedFiles(prev => prev.map(f =>
        skippedPaths.includes(fullPath(f.relativePath, f.file.name)) ? { ...f, status: 'skipped' as FileStatus } : f,
      ))
    }
    setTimeout(() => runUpload(false), 0)
  }

  const runUpload = async (retryOnly: boolean) => {
    setCheckingPrereqs(false)
    setPhase('uploading')
    setError('')
    setListExpanded(false)
    cancelledRef.current = false

    if (retryOnly) {
      setTrackedFiles(prev => prev.map(f =>
        f.status === 'failed' ? { ...f, status: 'pending' as FileStatus, progress: 0, error: undefined } : f,
      ))
      await new Promise(r => setTimeout(r, 0))
    }

    const snapshot = trackedRef.current

    // Tracked locally, not derived from trackedRef.current after the loop - updateFile() only
    // schedules a setTrackedFiles() re-render, and trackedRef.current is refreshed by the
    // component's next render pass, not synchronously. For the LAST file processed in the loop,
    // there is no further `await` between its own updateFile(..., {status:'done'}) call and the
    // post-loop code below, so React never gets a chance to flush that render before
    // trackedRef.current is read - it still reflects the pre-update (non-'done') state. For a
    // multi-file batch this was masked (earlier files' awaits gave earlier updates time to
    // flush), but for a single-file batch - e.g. a folder with exactly one file - this made
    // trackedRef.current read stale 100% of the time, so anyUploaded was always false and the
    // folder was never registered with the connector after upload.
    let anySucceeded = false
    let anyFailed = false

    for (let i = 0; i < snapshot.length; i++) {
      if (cancelledRef.current) break

      const current = trackedRef.current[i]
      if (current.status !== 'pending') continue

      const abortController = new AbortController()
      updateFile(i, { status: 'uploading', abortController })

      try {
        // Retries on 429 (server-side upload rate limiting) with a pause before each attempt -
        // honors Retry-After when the server sends one, otherwise backs off exponentially. Any
        // other status/error falls straight through to the outer catch as a real failure.
        let attempt = 0
        let res: { data: { id: string } } | undefined
        while (!res) {
          const formData = new FormData()
          formData.append('file', snapshot[i].file)
          formData.append('folder', folderName)
          formData.append('relativePath', snapshot[i].relativePath)

          try {
            // Through the shared http client (filesApi) - so an expired session's 401 goes through
            // the interceptor's silent-refresh/re-login flow like every other request, instead of
            // uploads silently failing while the rest of the app recovers transparently.
            res = await filesApi.upload(formData, {
              signal: abortController.signal,
              onUploadProgress: (e) => {
                if (e.total) {
                  const pct = Math.round((e.loaded / e.total) * 100)
                  updateFile(i, { progress: pct })
                }
              },
            })
          } catch (e: unknown) {
            const status = axios.isAxiosError(e) ? e.response?.status : undefined
            if (status !== 429 || attempt >= MAX_RATE_LIMIT_RETRIES) throw e
            attempt++
            const retryAfterMs = parseRetryAfterMs(axios.isAxiosError(e) ? e.response?.headers?.['retry-after'] : null)
              ?? Math.min(RATE_LIMIT_BASE_DELAY_MS * 2 ** (attempt - 1), RATE_LIMIT_MAX_DELAY_MS)
            updateFile(i, { progress: 0, error: t('Rate limited - retrying in {seconds}s...', { seconds: Math.ceil(retryAfterMs / 1000) }) })
            await sleepAbortable(retryAfterMs, abortController.signal)
            if (abortController.signal.aborted) throw new UploadCancelledError()
            updateFile(i, { error: undefined })
          }
        }
        updateFile(i, { status: 'done', progress: 100, serverId: res.data.id })
        anySucceeded = true
      } catch (e: unknown) {
        if (axios.isCancel(e) || e instanceof UploadCancelledError) {
          updateFile(i, { status: 'cancelled', progress: 0 })
        } else {
          // Backend validation/guard failures (e.g. storage quota exceeded) arrive as a ProblemDetail
          // via GlobalExceptionHandler; axios's own message is only "Request failed with status
          // code 400" and never says why.
          updateFile(i, { status: 'failed', error: apiErrorText(e) ?? t('Upload failed') })
          anyFailed = true
        }
      }
    }

    qc.invalidateQueries({ queryKey: keys.files() })
    qc.invalidateQueries({ queryKey: keys.fileQuota() })

    if (cancelledRef.current) return

    // Upload completing here does NOT register the folder - that happens exactly once, only
    // when the modal actually closes (handleClose), keeping upload (storage) and registration
    // (connector sync) as two separate steps linked by a single hand-off point.
    if (anySucceeded) {
      uploadedFolderRef.current = folderName
    }
    setPhase(anyFailed ? 'partial' : 'success')
  }

  const validateAndUpload = async () => {
    if (!hasFolderSelected) {
      setFolderError(folderInput.trim()
        ? t('Press Enter or pick a suggestion to confirm this folder')
        : t('Select or create a destination folder'))
      folderShake.trigger()
      return
    }
    if (exceedsQuota) return
    setError('')
    setCheckingPrereqs(true)
    checkSharedDuplicates()
  }

  const handleContinueSharedDuplicates = () => {
    if (!allSharedDuplicatesResolved) {
      setSharedDupError(t('Resolve every file above before continuing'))
      sharedDupShake.trigger()
      return
    }
    setSharedDupError('')
    confirmSharedDuplicates()
  }

  const handleContinueDuplicates = () => {
    if (!allDuplicatesResolved) {
      setDupError(t('Resolve every file above before continuing'))
      dupShake.trigger()
      return
    }
    setDupError('')
    confirmDuplicates()
  }

  const handleRetry = () => runUpload(true)

  // Back from a duplicates step to the pick: the files that step marked to skip are part of the
  // batch again - a skipped row in the pick has no way back but removal - and the answers given
  // there are asked again on the next Upload.
  const backToPick = () => {
    setTrackedFiles(prev => prev.map(f => f.status === 'skipped' ? { ...f, status: 'pending' as FileStatus } : f))
    setDuplicates([])
    setSharedDuplicates([])
    setSharedDupError('')
    setDupError('')
    setPhase('pick')
  }

  const handleClose = async () => {
    if (uploadedFolderRef.current) {
      const folder = uploadedFolderRef.current
      uploadedFolderRef.current = null
      const failureMessage = await registerFolder(folder)
      if (failureMessage) {
        // Roll the upload back so "could not index" is also true of the storage side - an
        // uploaded-but-never-registered folder would otherwise silently waste the user's quota
        // with no scheduled sync ever picking it up (see registerFolder's comment above).
        const uploadedIds = trackedRef.current.filter(f => f.serverId).map(f => f.serverId!)
        await deleteUploadedFiles(uploadedIds)
        setTrackedFiles(prev => prev.map(f => f.status === 'done' ? { ...f, status: 'cancelled' as FileStatus } : f))
        setPhase('cancelled')
        setError(failureMessage)
        return
      }
    }
    onClose()
  }

  const handleCancelUpload = async () => {
    uploadedFolderRef.current = null // must run first, so a later backdrop-click handleClose() never registers
    const uploadedIds = trackedRef.current.filter(f => f.serverId).map(f => f.serverId!)
    await deleteUploadedFiles(uploadedIds)
    setTrackedFiles(prev => prev.map(f => f.status === 'done' ? { ...f, status: 'cancelled' as FileStatus } : f))
    setPhase('cancelled')
  }

  const handleCancel = async () => {
    if (phase !== 'uploading') {
      handleClose()
      return
    }
    cancelledRef.current = true
    setPhase('cancelled')
    uploadedFolderRef.current = null

    for (const tf of trackedRef.current) {
      if (tf.status === 'uploading' && tf.abortController) {
        tf.abortController.abort()
      }
    }

    const uploadedIds = trackedRef.current
      .filter(f => f.serverId)
      .map(f => f.serverId!)
    await deleteUploadedFiles(uploadedIds)
  }

  const isPick = phase === 'pick'
  const isSharedDuplicates = phase === 'shared-duplicates'
  const isDuplicates = phase === 'duplicates'
  const isUploading = phase === 'uploading'
  const isTerminal = phase === 'success' || phase === 'cancelled'
  const isPartial = phase === 'partial'
  const canClose = isPick || isTerminal || isPartial
  const retryCount = trackedFiles.filter(f => f.status === 'failed').length
  // Only a PICK can be lost by closing: once an upload has started, closing is handleClose's job
  // (it registers what finished) and every other phase refuses to close at all.
  const { dirty: pickChanged } = useFormDirty({ files: trackedFiles.map(f => f.id), folderInput })
  const dirty = isPick && pickChanged
  const title = isSharedDuplicates ? t('Matching files found') : isDuplicates ? t('Duplicate files found') : t('Upload files')
  // A folder's name, in its own ink, inside a translated sentence.
  const folderPart = { folder: <span className="text-ink-secondary">{folderName}</span> }

  return (
    <ModalShell
      onClose={handleClose}
      closable={canClose}
      dirty={dirty}
      size="xl"
      ariaLabel={title}
    >
        <DialogHeader
          // The type's mark before the title, as on the source's card once it exists.
          icon={<SourceTypeMark type="files" size={20} className="text-brand-legible" />}
          title={title}
        />

        <DialogBody>
        {/* Each phase (pick, duplicates, uploading, done) fades in over the last. */}
        <FadeSwap swapKey={phase}>
          <FormStack>
            {/* Folder combobox - pick phase only */}
            {isPick && (
              <Combobox
                value={folderInput}
                onChange={changeFolder}
                onCreate={createFolder}
                options={folders}
                status={foldersStatus}
                labels={{
                  loading: t('Loading...'),
                  failed: t('Failed to load'),
                  empty: t('No folders yet'),
                  create: name => t('Create "{name}"', { name }),
                }}
                placeholder={t('Select or create a folder...')}
                error={folderError || undefined}
                shake={folderShake.shaking}
              />
            )}

            {/* Drop area - pick phase only. The box opens the file picker; the link under its
                sentence opens the folder picker. */}
            {isPick && (
              <DropArea
                active={drop.active}
                handlers={drop.handlers}
                label={t('Click or drop files/folders here')}
                onPick={filePicker.open}
                secondaryAction={
                  <Button
                    variant="hyperlink"
                    onClick={folderPicker.open}
                    // The box takes Enter and Space as "open the file picker", and a key pressed on
                    // this link would bubble up to it and be taken - opening the wrong picker.
                    onKeyDown={e => e.stopPropagation()}
                  >
                    {t('or select a folder')}
                  </Button>
                }
              />
            )}
            {/* The pickers themselves: hidden inputs, opened by the box and the link above. */}
            {filePicker.input}
            {folderPicker.input}

            {/* Shared/own-connector match resolution phase - client-hashed candidates that already
                match a connector shared by someone else, or one of the requester's own. */}
            {isSharedDuplicates && (
              <div className="space-y-3 animate-fade-in">
                <InlineNotice tone="warning" size="md" icon={TriangleAlert}>
                  {t("These files match content already indexed. Skipping saves storage quota; uploading anyway keeps your own copy.")}
                </InlineNotice>

                <div className="flex items-center justify-end gap-4">
                  <Button variant="quiet" onClick={() => setAllSharedDuplicates('skip')}>{t('Skip all')}</Button>
                  <Button variant="quiet" onClick={() => setAllSharedDuplicates('upload')}>{t('Upload all anyway')}</Button>
                </div>

                <List className={`max-h-[clamp(8rem,calc(100dvh-22rem),28rem)] overflow-y-auto ${sharedDupShake.shaking ? 'animate-shake' : ''}`}>
                  {sharedDuplicates.map(d => (
                    <ItemRow
                      key={d.path}
                      statusMark={<StatusMark state="warning" size={14} />}
                      title={d.path}
                      meta={[d.ownedByRequester
                        ? t('Already in your connector "{name}"', { name: d.matchedConnectorName })
                        : t('Shared by another user in "{name}"', { name: d.matchedConnectorName })]}
                      trailing={
                        <SegmentedControl<'skip' | 'upload' | ''>
                          ariaLabel={d.path}
                          value={d.action ?? ''}
                          onChange={v => { if (v) setSharedDuplicateAction(d.path, v) }}
                          options={[
                            { value: 'skip', label: t('Skip') },
                            { value: 'upload', label: t('Upload anyway'), tone: 'warning' },
                          ]}
                        />
                      }
                    />
                  ))}
                </List>
                <Presence show={!!sharedDupError}><InlineNotice tone="danger" size="md">{sharedDupError}</InlineNotice></Presence>
              </div>
            )}

            {/* Duplicates resolution phase */}
            {isDuplicates && (
              <div className="space-y-3 animate-fade-in">
                <InlineNotice tone="warning" size="md" icon={TriangleAlert}>
                  <Rich
                    text={t('The following files already exist in {folder}. Choose to skip or overwrite each file.')}
                    parts={{ folder: <span className="font-medium">{folderName}</span> }}
                  />
                </InlineNotice>

                <div className="flex items-center justify-end gap-4">
                  <Button variant="quiet" onClick={() => setAllDuplicates('skip')}>{t('Skip all')}</Button>
                  <Button variant="quiet" onClick={() => setAllDuplicates('overwrite')}>{t('Overwrite all')}</Button>
                </div>

                <List className={`max-h-[clamp(8rem,calc(100dvh-22rem),28rem)] overflow-y-auto ${dupShake.shaking ? 'animate-shake' : ''}`}>
                  {duplicates.map(d => (
                    <ItemRow
                      key={d.path}
                      statusMark={<StatusMark state="warning" size={14} />}
                      title={d.path}
                      trailing={
                        <SegmentedControl<'skip' | 'overwrite' | ''>
                          ariaLabel={d.path}
                          value={d.action ?? ''}
                          onChange={v => { if (v) setDuplicateAction(d.path, v) }}
                          options={[
                            { value: 'skip', label: t('Skip') },
                            { value: 'overwrite', label: t('Overwrite'), tone: 'warning' },
                          ]}
                        />
                      }
                    />
                  ))}
                </List>
                <Presence show={!!dupError}><InlineNotice tone="danger" size="md">{dupError}</InlineNotice></Presence>
              </div>
            )}

            {/* Success state */}
            {phase === 'success' && (
              <DialogNotice
                icon={CheckCircle2}
                tone="brand"
                title={t('All files uploaded successfully')}
                detail={(
                  <Rich
                    text={skippedCount > 0
                      ? t('{count} file uploaded to {folder}, {skipped} skipped|{count} files uploaded to {folder}, {skipped} skipped', { count: doneCount, skipped: skippedCount })
                      : t('{count} file uploaded to {folder}|{count} files uploaded to {folder}', { count: doneCount })}
                    parts={folderPart}
                  />
                )}
              />
            )}

            {/* Cancelled state - also reused for the post-upload registration rollback in
                handleClose (e.g. a tier-limit race), distinguished by `error` being set */}
            {phase === 'cancelled' && (
              <DialogNotice
                icon={X}
                tone={error ? 'danger' : 'neutral'}
                title={error ? t('Indexing blocked') : t('Upload cancelled')}
                detail={t('The uploaded files have been removed from the server.')}
              />
            )}

            {/* Status text + progress bar (uploading / partial) */}
            {(isUploading || isPartial) && totalCount > 0 && (
              // The kit's labelled progress line: one whole sentence (also the bar's name), the
              // percentage, the bar.
              <ProgressRow
                className="animate-fade-in"
                label={isUploading
                  ? t('Uploading {done}/{total} files to {folder}', { done: doneCount, total: activeCount, folder: folderName })
                  : t('{done} of {total} files uploaded to {folder}, {failed} failed', { done: doneCount, total: activeCount, folder: folderName, failed: failedCount })}
                value={overallProgress}
                tone={isPartial ? 'warning' : 'accent'}
              />
            )}

            {/* File list (pick / uploading / partial) */}
            {/* Comes with the first file picked and goes with the last one removed. */}
            <Presence show={!isTerminal && !isDuplicates && !isSharedDuplicates && trackedFiles.length > 0}>
              <Disclosure
                open={listExpanded}
                onToggle={setListExpanded}
                summary={<span className={TEXT.label}>{t('{count} file|{count} files', { count: totalCount })}</span>}
                meta={!listExpanded && !isPick ? (
                  <MetaList items={[
                    t('{count} done', { count: doneCount }),
                    failedCount > 0 && t('{count} failed', { count: failedCount }),
                    skippedCount > 0 && t('{count} skipped', { count: skippedCount }),
                  ]} />
                ) : undefined}
              >
                <List className="max-h-[clamp(8rem,calc(100dvh-29rem),24rem)] overflow-y-auto">
                  {/* Failed items surface first so a partial-failure batch doesn't bury them below
                      a long run of already-done files - stable sort keeps every other status in
                      its original (upload) order. `i` stays the index into trackedFiles (not the
                      sorted position) since every handler below (removeFile/cancelSingleFile)
                      addresses files by that original index. */}
                  {trackedFiles
                    .map((tf, i) => ({ tf, i }))
                    .sort((a, b) => Number(b.tf.status === 'failed') - Number(a.tf.status === 'failed'))
                    .map(({ tf, i }) => {
                      const path = fullPath(tf.relativePath, tf.file.name)
                      const mark = FILE_MARK[tf.status]
                      const cancelLabel = tf.status === 'done' ? t('Remove from server') : t('Cancel')
                      return (
                        <ItemRow
                          key={tf.id}
                          statusMark={<StateMark icon={mark.icon} state={mark.state} />}
                          title={<Tooltip label={path} fullWidth><span className="block truncate">{path}</span></Tooltip>}
                          struck={tf.status === 'cancelled' || tf.status === 'skipped'}
                          meta={[
                            <Readout key="size">{formatSize(tf.file.size)}</Readout>,
                            tf.status === 'uploading' && <Readout key="pct" tone="brand">{tf.progress}%</Readout>,
                          ]}
                          trailing={tf.status === 'uploading'
                            ? <ProgressBar value={tf.progress} size="xs" label={path} className="w-20 sm:w-28 mt-2" />
                            : undefined}
                          error={tf.status === 'failed' ? tf.error : undefined}
                          actions={
                            tf.status === 'pending' && isPick ? (
                              <IconButton
                                icon={X}
                                size="sm"
                                tone="danger"
                                label={t('Remove {name}', { name: tf.file.name })}
                                onClick={() => removeFile(i)}
                              />
                            ) : isUploading && (tf.status === 'uploading' || tf.status === 'pending' || tf.status === 'done') ? (
                              <IconButton
                                icon={X}
                                size="sm"
                                tone="danger"
                                label={cancelLabel}
                                onClick={() => cancelSingleFile(i)}
                              />
                            ) : undefined
                          }
                        />
                      )
                    })}
                </List>
              </Disclosure>
            </Presence>

            <Presence show={!!error}><InlineNotice tone="danger" size="md">{error}</InlineNotice></Presence>
            <Presence show={isPick && exceedsQuota && remainingQuotaBytes !== null}>
              {remainingQuotaBytes !== null && (
                <InlineNotice tone="danger" size="md" icon={TriangleAlert}>
                  {t('Not enough storage remaining - selected files total {total}, only {left} left. Remove some files to continue.', {
                    total: formatSize(pendingUploadBytes),
                    left: formatSize(remainingQuotaBytes),
                  })}
                </InlineNotice>
              )}
            </Presence>
          </FormStack>
        </FadeSwap>
        </DialogBody>

        {/* Footer buttons */}
        {isPick && trackedFiles.length > 0 && (
          <DialogFooter>
            <Button onClick={validateAndUpload} loading={checkingPrereqs}>
              {checkingPrereqs ? t('Checking files...') : t('Upload ({count})', { count: trackedFiles.length })}
            </Button>
          </DialogFooter>
        )}

        {isSharedDuplicates && (
          <DialogFooter>
            <Button variant="secondary" onClick={backToPick}>{t('Back')}</Button>
            <Button onClick={handleContinueSharedDuplicates}>
              {t('Continue')}
            </Button>
          </DialogFooter>
        )}

        {isDuplicates && (
          <DialogFooter>
            <Button variant="secondary" onClick={backToPick}>{t('Back')}</Button>
            <Button onClick={handleContinueDuplicates}>
              {t('Continue')}
            </Button>
          </DialogFooter>
        )}

        {isUploading && (
          <DialogFooter>
            <Button variant="secondary" onClick={handleCancel}>{t('Cancel')}</Button>
          </DialogFooter>
        )}

        {/* Three actions, so the row may break onto a second line on a phone. The one that deletes
            what did upload goes last, quiet and set apart, and asks first. */}
        {isPartial && (
          <DialogFooter wrap>
            <Button variant="secondary" onClick={handleClose}>{t('Continue without the failed files')}</Button>
            <Button onClick={handleRetry}>
              {t('Retry ({count})', { count: retryCount })}
            </Button>
            <Button variant="danger-quiet" className="ml-2" onClick={() => setConfirmingCancel(true)}>{t('Cancel upload')}</Button>
          </DialogFooter>
        )}
        {confirmingCancel && (
          <ConfirmDialog
            title={t('Cancel this upload?')}
            description={t('The files that did upload are removed from the server, and nothing is indexed.')}
            confirmLabel={t('Cancel upload')}
            cancelLabel={t('Keep them')}
            onConfirm={() => { setConfirmingCancel(false); void handleCancelUpload() }}
            onCancel={() => setConfirmingCancel(false)}
          />
        )}

        {/* Success/cancelled have no other footer action - this is also the only reliable
            trigger for registerFolder() (via handleClose) for a user who wouldn't otherwise
            think to click the backdrop to dismiss. */}
        {isTerminal && (
          <DialogFooter>
            <Button onClick={handleClose}>{t('Done')}</Button>
          </DialogFooter>
        )}
    </ModalShell>
  )
}
