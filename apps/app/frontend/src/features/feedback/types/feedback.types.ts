export type FeedbackMood = 'BAD' | 'NEUTRAL' | 'GOOD'

// GENERAL covers the two original flows (floating feedback button, chat message dislike) -
// every existing call site keeps working by simply omitting `source`/`ratings` below.
// QUOTA_MILESTONE is the $5-spend-bracket survey (see QuotaMilestoneFeedbackModal).
export type FeedbackSource = 'GENERAL' | 'QUOTA_MILESTONE'

// Chat context: only set when the modal is opened from a chat message's "Leave feedback"
// link - the base floating-button flow never sends conversationId/messageId/transcript.
export interface FeedbackChatContext {
  conversationId: string
  messageId?: number
  getTranscript: () => string
}

export interface FeedbackRequest {
  mood: FeedbackMood
  comment?: string
  // Still sent, still ignored by the backend, which now makes every submission anonymous
  // unconditionally - see FeedbackController.submit(). Kept in the type only so the existing
  // call sites need no edit; there is no longer a UI that can set it to false.
  anonymous: boolean
  // Optional contact address the user types themselves. The only identifying value a submission
  // can carry, and the whole reason the modal can be anonymous without being a dead end.
  contactEmail?: string
  // Attach the tail of this installation's own log. Read SERVER-side (the browser cannot see it),
  // which is why this is a flag rather than another entry in `files`.
  includeLogs?: boolean
  conversationId?: string
  messageId?: number
  includeConversation: boolean
  conversationTranscript?: string
  // Both omitted (undefined) by every existing GENERAL call site - the backend defaults a
  // missing/blank source to GENERAL and a missing ratings map to null.
  source?: FeedbackSource
  ratings?: Record<string, FeedbackMood>
  // A thumbs-up/down on an answer. It leaves the deployment only when diagnostics are sent to Proxyma -
  // the backend decides (FeedbackShipper.ratingsMayLeave); the form never sets this.
  messageRating?: boolean
}

export interface FeedbackResponse {
  id: number
  mood: FeedbackMood
  comment: string | null
  anonymous: boolean
  source: FeedbackSource
}

// Metadata only, admin-facing (AdminFeedbackController.list/resolve/unresolve) - the actual bytes
// are fetched on demand via a plain browser download link, never inlined here. contentType can be
// null for a very old row saved before that column existed.
export interface FeedbackAttachmentSummary {
  id: number
  fileName: string
  contentType: string | null
  sizeBytes: number
}

// The 5-question QUOTA_MILESTONE survey (QuotaMilestoneFeedbackModal) - stable `id`s are the
// keys of the `ratings` map above; `label` is display copy only, kept here rather than
// duplicated on the backend since the backend treats `ratings` as an opaque
// Map<String, FeedbackMood> (see backend FeedbackRequest/AdminFeedbackItemResponse).
export interface QuotaMilestoneQuestion {
  id: string
  label: string
}

export const QUOTA_MILESTONE_QUESTIONS: QuotaMilestoneQuestion[] = [
  { id: 'overall', label: 'Overall experience' },
  { id: 'search_quality', label: 'Search result quality' },
  { id: 'speed', label: 'Response speed' },
  { id: 'ease_of_use', label: 'Ease of use' },
  { id: 'recommend', label: 'Likelihood to recommend to a colleague' },
]
