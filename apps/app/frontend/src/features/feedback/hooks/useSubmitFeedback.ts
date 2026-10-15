import { useMutation } from '@tanstack/react-query'
import { http } from '@/services/httpClient'
import type { FeedbackRequest, FeedbackResponse } from '../types/feedback.types'

export interface SubmitFeedbackVariables extends FeedbackRequest {
  // Only ever set by FeedbackModal's general flow (attach files + drag-n-drop) - the quick
  // thumbs-up in MessageBubble and QuotaMilestoneFeedbackModal's survey never attach files, so
  // every other existing call site keeps working unchanged by simply omitting this.
  files?: File[]
}

export function useSubmitFeedback() {
  return useMutation({
    mutationFn: ({ files, ...request }: SubmitFeedbackVariables) => {
      // multipart, not a plain JSON body - see FeedbackController.submit() on the backend. The
      // "request" part must carry an explicit application/json content type (a bare string part
      // defaults to text/plain, which Spring's JSON message converter won't bind to
      // FeedbackRequest) - a Blob is what lets FormData set that.
      const formData = new FormData()
      formData.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }))
      files?.forEach(file => formData.append('files', file))
      return http.post<FeedbackResponse>('/api/v1/feedback', formData).then(r => r.data)
    },
  })
}
