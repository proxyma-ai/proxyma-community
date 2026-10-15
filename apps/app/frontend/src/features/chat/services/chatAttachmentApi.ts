import { http } from '@/services/httpClient'

export interface ChatAttachmentUploadResult {
  // The name the file is stored under in this conversation - unique there, and what a send names
  // it by. The server never says where it lives.
  fileName: string
  sizeBytes: number
  // Serves the file back, for the preview.
  url: string
}

// Where an uploaded attachment is served back; ChatController.attachmentUrl builds the same one.
export function attachmentUrl(conversationId: string, fileName: string): string {
  return `/api/v1/chat/conversations/${conversationId}/attachments/${encodeURIComponent(fileName)}`
}

export const chatAttachmentApi = {
  // Uploads one file attached to the next outgoing message for `conversationId` (minted
  // client-side via useChat's ensureConversationId() for a brand-new conversation - see
  // ChatController, which accepts a client-supplied id and only mints its own if none is given).
  // `fileName` in the response is what the eventual POST /api/v1/chat names it by, in
  // `attachments` - the `File` object itself is never sent again.
  uploadAttachment: (
    conversationId: string,
    file: File,
    onProgress: (pct: number) => void,
    signal?: AbortSignal,
  ): Promise<ChatAttachmentUploadResult> => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post<ChatAttachmentUploadResult>(
      `/api/v1/chat/conversations/${conversationId}/attachments`,
      formData,
      {
        headers: { 'Content-Type': 'multipart/form-data' },
        signal,
        onUploadProgress: (e) => {
          if (e.total) onProgress(Math.round((e.loaded / e.total) * 100))
        },
      },
    ).then(r => r.data)
  },
}
