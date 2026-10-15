import { useCallback, useEffect, useRef } from 'react'
import axios from 'axios'
import { chatAttachmentApi } from '../services/chatAttachmentApi'
import type { ChatAttachment } from '../types/chat.types'
import { useTranslation } from '@/i18n'


// Owns the upload lifecycle (add 'uploading' chip -> PATCH progress -> 'done'/'failed') for one
// attachments list + its setter. Extracted out of the composer so a chat (the chat page, a panel) can drive the exact
// same upload path from a page-wide drop target, not just the pill's own file picker/drop area -
// both need the identical AbortController bookkeeping (a chip's remove button must be able to
// cancel an in-flight upload regardless of whether it was started by drag or by picker), so this
export function useAttachmentUploader(
  attachments: ChatAttachment[],
  onAttachmentsChange: (attachments: ChatAttachment[]) => void,
  ensureConversationId: () => string,
) {
  const { t } = useTranslation()
  // In-flight uploads' AbortControllers, keyed by the attachment's client-local id - used to
  // cancel an upload still in progress when its chip's remove button is clicked.
  const abortControllersRef = useRef<Map<string, AbortController>>(new Map())
  // Mirrors the `attachments` prop for use inside async upload callbacks (onUploadProgress, the
  // upload promise's then/catch) - those can fire well after this render, by which point a stale
  // closure over the `attachments` prop would silently drop concurrent uploads' updates. Always
  // read/write through this ref (kept in sync below) instead of the prop directly when patching
  // one attachment by id, mirroring the same "read-latest, write-through" fix FileUploadModal's
  // own updateFile() needs for the same reason.
  const attachmentsRef = useRef(attachments)
  useEffect(() => { attachmentsRef.current = attachments }, [attachments])

  // Patches one attachment by id, merging through attachmentsRef (see its declaration above) so
  // concurrent uploads' progress events never clobber each other via a stale `attachments` prop.
  const updateAttachment = useCallback((id: string, patch: Partial<ChatAttachment>) => {
    const next = attachmentsRef.current.map(a => (a.id === id ? { ...a, ...patch } : a))
    attachmentsRef.current = next
    onAttachmentsChange(next)
  }, [onAttachmentsChange])

  // Adds each dropped/picked file as an 'uploading' chip immediately, then uploads it in the
  // background. `ensureConversationId()` mints a client-side id up front for a brand-new
  // conversation (the upload endpoint is scoped to a conversationId) - the eventual send reuses
  // the same id (see useChat's ensureConversationId).
  const handleFiles = useCallback((fileList: FileList | File[]) => {
    const files = Array.from(fileList)
    if (files.length === 0) return
    const convId = ensureConversationId()
    const newAttachments: ChatAttachment[] = files.map(file => ({
      id: crypto.randomUUID(),
      file,
      status: 'uploading',
      progress: 0,
    }))
    const next = [...attachmentsRef.current, ...newAttachments]
    attachmentsRef.current = next
    onAttachmentsChange(next)

    newAttachments.forEach(att => {
      const controller = new AbortController()
      abortControllersRef.current.set(att.id, controller)
      chatAttachmentApi.uploadAttachment(
        convId,
        att.file,
        (pct) => updateAttachment(att.id, { progress: pct }),
        controller.signal,
      ).then(result => {
        abortControllersRef.current.delete(att.id)
        updateAttachment(att.id, { status: 'done', progress: 100, fileName: result.fileName })
      }).catch(e => {
        abortControllersRef.current.delete(att.id)
        // A cancelled upload (remove button clicked mid-flight) already dropped its chip in
        // removeAttachment below - nothing further to update.
        if (axios.isCancel(e)) return
        // Backend validation/guard failures arrive as an RFC7807 ProblemDetail body
        // ({ detail: "..." }) via GlobalExceptionHandler - see FileUploadModal's identical
        // extraction for why axios's own generic AxiosError.message isn't used directly.
        const data = axios.isAxiosError(e) ? e.response?.data as Record<string, unknown> | undefined : undefined
        const msg = (typeof data?.detail === 'string' && data.detail)
          || (typeof data?.error === 'string' && data.error)
          // `detail`/`error` are the backend's sentence, translated by the HTTP client as it
          // arrives. axios's own `e.message` ("Network Error") is not shown: it is English the
          // catalog cannot know (CHAT-32), so the chip says the translated last resort instead.
          || t('Upload failed')
        updateAttachment(att.id, { status: 'failed', error: msg })
      })
    })
    // `t` joins the list because the callback closes over it - it changes identity when the
    // locale does, and a stale closure would keep the old language's fallback message.
  }, [ensureConversationId, onAttachmentsChange, updateAttachment, t])

  const removeAttachment = useCallback((id: string) => {
    abortControllersRef.current.get(id)?.abort()
    abortControllersRef.current.delete(id)
    const next = attachmentsRef.current.filter(a => a.id !== id)
    attachmentsRef.current = next
    onAttachmentsChange(next)
  }, [onAttachmentsChange])

  return { handleFiles, removeAttachment }
}
