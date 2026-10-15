import { ConfirmDialog } from '@/components/ui/kit'
import { AlertTriangle } from 'lucide-react'
import { useFileUpload } from '@/hooks/useFileUpload'
import { useTranslation } from '@/i18n'

// What deleting a connector actually does, which is NOT the same thing in the two editions and must
// never be described as if it were.
//
// Server mode holds a COPY of the user's documents: they were uploaded into a per-user sandbox, so
// removing the connector removes those bytes, and saying so is both accurate and the thing the user
// needs to be warned about.
//
// Desktop mode holds no copy at all. A Files connector there is a folder the user already had, which
// Proxyma walks and indexes strictly by reference (see ConnectorService.registerFiles and
// FileCleanupListener, which does not run in this mode). Deleting the connector removes Proxyma's
// index of it and nothing else - the folder and every file in it are left exactly as they are.
// Telling a desktop user their files are about to be "permanently deleted from the server" is
// alarming, false, and could talk someone out of an action that was always safe.
//
// A FUNCTION OF A RUNTIME FLAG, not a module-level constant off a build-time one. It was the
// latter, and that was correct while only the server build ever held a copy - the home server is
// the DESKTOP build holding a copy, so a constant evaluated at import time would have told its
// users their documents are safe on disk while deleting the only copy. Of the two ways to get this
// wrong, that is the one that loses data rather than merely alarming somebody.
const UPLOADED_CONSEQUENCES = [
  'The source and all its items will be permanently deleted from the server',
  'All associated vector embeddings will be removed from the vector database',
]
const LOCAL_FOLDER_CONSEQUENCES = [
  'Your own files and folders are NOT touched - nothing is moved, changed or deleted on your disk',
  'Proxyma stops indexing this source and forgets what it had indexed from it',
  'All associated vector embeddings will be removed from the vector database',
]

interface Props {
  connectorName: string
  onConfirm: () => void
  onCancel: () => void
  isDeleting?: boolean
  deletingLabel?: string
  // The following let non-connector callers (backup files, API tokens) reuse this same modal
  // shell instead of hand-rolling their own - all default to the original connector-deletion
  // copy, so existing call sites need no changes.
  title?: string
  actionVerb?: string
  confirmLabel?: string
  consequences?: string[]
  // When set, the confirm button stays disabled until the admin types this exact string into a
  // text field - a stronger gate than the plain "Are you sure?" click for irreversible,
  // hard-to-target-correctly operations (e.g. restoring one specific backup file out of several).
  // The kit ConfirmDialog keeps Confirm disabled until it matches, since the 2026-10 rebuild; it
  // used to stay enabled and answer a mismatch with a shake.
  requireTypedConfirmation?: string
}

// EVERY STRING PROP IS AN ENGLISH KEY, translated inside this component rather than by the
// caller. A caller that translated first would pass Vietnamese into `t()`, which simply misses
// and returns it - correct by luck, and it would drift the moment anyone reworded the English.
// Callers pass English; this decides what the reader sees.
//
// A THIN WRAPPER OVER THE KIT'S ConfirmDialog since the 2026-10 rebuild: the dialog, its layout and
// its typed gate are the kit's; what stays here is the translation and the connector default.
export function DeleteConfirmModal({
  connectorName, onConfirm, onCancel, isDeleting, deletingLabel,
  title = 'Delete source',
  actionVerb = 'delete',
  confirmLabel = 'Delete',
  consequences,
  requireTypedConfirmation,
}: Props) {
  const { t } = useTranslation()
  const uploadsOwnTheBytes = useFileUpload()
  // The caller's list wins when it passed one - the non-connector callers (backup files, API
  // tokens) describe their own consequences and have nothing to do with file sources. Only the
  // connector default depends on who owns the bytes.
  const shownConsequences = consequences
    ?? (uploadsOwnTheBytes ? UPLOADED_CONSEQUENCES : LOCAL_FOLDER_CONSEQUENCES)

  return (
    <ConfirmDialog
      title={t(title)}
      // A whole sentence, and the name after it as the kit's `subject` (in bold) - the shape every
      // language can order. It was a sentence assembled around the bold name with a "?" outside
      // both halves, which fixed the word order to English.
      description={t('You are about to {verb}:', { verb: t(actionVerb) })}
      subject={connectorName}
      consequences={shownConsequences.map(c => t(c))}
      confirmLabel={t(confirmLabel)}
      cancelLabel={t('Cancel')}
      pendingLabel={deletingLabel ? t(deletingLabel) : t('Deleting...')}
      pending={isDeleting}
      onConfirm={onConfirm}
      onCancel={onCancel}
      // A bin for a deletion; a restore or a reset loses data too, but is not a deletion.
      icon={actionVerb === 'delete' ? undefined : AlertTriangle}
      typedConfirmation={requireTypedConfirmation != null
        ? { value: requireTypedConfirmation, label: t('Type "{value}" to confirm', { value: requireTypedConfirmation }) }
        : undefined}
    />
  )
}
