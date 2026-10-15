import { useState } from 'react'
import { Paperclip } from 'lucide-react'
import {
  Button, Caption, Checkbox, Chip, DialogBody, DialogFooter, DialogHeader, ExternalLink, Field, FieldNote, FilePickerButton, FormColumns,
  FormStack, Input, Presence, Readout, Textarea, cn, useFileDrop, useShake,
} from '@/components/ui/kit'
import { useToastStore } from '@/store/toastStore'
import { formatFileSize } from '@/utils/formatters'
import { useSubmitFeedback } from '../hooks/useSubmitFeedback'
import type { FeedbackChatContext, FeedbackMood } from '../types/feedback.types'
import { MoodPicker } from './MoodPicker'
import { useTranslation } from '@/i18n'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'

interface Props {
  onClose: () => void
  // Only present when opened from a chat message's dislike flow - the general floating-button
  // flow never passes this, and the "include this conversation" option only renders when it's set.
  chatContext?: FeedbackChatContext
  // The thumb the user pressed before opening the form, preselected. Never assumed: the form
  // opened from a thumbs-UP with Bad already chosen until 2026-09-30.
  initialMood?: FeedbackMood
}

// Mirrors FeedbackController's own MAX_ATTACHMENTS_PER_FEEDBACK/MAX_TOTAL_ATTACHMENT_BYTES on the
// backend - client-side enforcement here is just for immediate feedback (no wasted round trip for
// an oversized file); the backend enforces its own limits independently regardless. The size is a
// budget for ALL the files together, so one file may use the whole of it.
const MAX_ATTACHMENTS = 10
const MAX_TOTAL_ATTACHMENT_BYTES = 20 * 1024 * 1024

export function FeedbackModal({ onClose, chatContext, initialMood }: Props) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const submitFeedback = useSubmitFeedback()
  const [mood, setMood] = useState<FeedbackMood | null>(initialMood ?? null)
  const [comment, setComment] = useState('')
  const [contactEmail, setContactEmail] = useState('')
  const [includeLogs, setIncludeLogs] = useState(false)
  const [includeConversation, setIncludeConversation] = useState(false)
  const [moodError, setMoodError] = useState('')
  const moodShake = useShake()
  const [attachments, setAttachments] = useState<File[]>([])
  const [attachError, setAttachError] = useState('')
  // Closing asks first once anything has been entered. The mood a dislike pre-selects is part of
  // the snapshot, so opening from a thumbs-down and closing again asks nothing.
  const { dirty } = useFormDirty({
    mood, comment, contactEmail, includeLogs, includeConversation, attachments: attachments.length,
  })

  const addFiles = (incoming: File[]) => {
    if (incoming.length === 0) return
    const room = MAX_ATTACHMENTS - attachments.length
    if (room <= 0) {
      setAttachError(t('You can attach up to {max} files.', { max: MAX_ATTACHMENTS }))
      return
    }
    // Taken in order while they fit the budget the files already attached have left.
    let used = attachments.reduce((sum, f) => sum + f.size, 0)
    const accepted: File[] = []
    const oversized: File[] = []
    for (const f of incoming.slice(0, room)) {
      if (used + f.size > MAX_TOTAL_ATTACHMENT_BYTES) {
        oversized.push(f)
      } else {
        accepted.push(f)
        used += f.size
      }
    }
    if (incoming.length > room) {
      setAttachError(t('Only {max} files can be attached at a time - some were skipped.', { max: MAX_ATTACHMENTS }))
    } else if (oversized.length > 0) {
      setAttachError(t('Over the {limit} limit for all files together: {files}', { limit: formatFileSize(MAX_TOTAL_ATTACHMENT_BYTES), files: oversized.map(f => f.name).join(', ') }))
    } else {
      setAttachError('')
    }
    if (accepted.length > 0) setAttachments(prev => [...prev, ...accepted])
  }

  const removeAttachment = (index: number) => {
    setAttachments(prev => prev.filter((_, i) => i !== index))
    setAttachError('')
  }

  // The kit's drop state, with its enter/leave counter, so crossing the form's fields does not
  // flicker the border. The shell keeps the drag from reaching the page underneath.
  const drop = useFileDrop({ onDrop: data => addFiles(Array.from(data.files)) })

  // A screenshot is usually on the CLIPBOARD, not in a file (maintainer's feedback, 2026-10-02):
  // pasting anywhere in the dialog attaches it. Only when the clipboard holds files - text pasted
  // into the comment or the e-mail field is left to the field. A browser names every pasted image
  // "image.png", so each is given the time it was pasted, or three of them read as one.
  const onPaste = (e: React.ClipboardEvent) => {
    const files = Array.from(e.clipboardData.files)
    if (files.length === 0) return
    e.preventDefault()
    const stamp = new Date().toTimeString().slice(0, 8).replace(/:/g, '-')
    addFiles(files.map((f, i) => {
      if (!/^image\.[a-z]+$/i.test(f.name)) return f
      const suffix = files.length > 1 ? `-${i + 1}` : ''
      return new File([f], `pasted-${stamp}${suffix}${f.name.slice(f.name.lastIndexOf('.'))}`, { type: f.type })
    }))
  }

  const handleSend = () => {
    if (!mood) {
      setMoodError(t('Select how you feel first'))
      moodShake.trigger()
      return
    }
    submitFeedback.mutate(
      {
        mood,
        comment: comment.trim() || undefined,
        // The backend ignores this and files every submission anonymously - see
        // FeedbackController. Sent as true so the request means what it says.
        anonymous: true,
        contactEmail: contactEmail.trim() || undefined,
        includeLogs,
        conversationId: chatContext?.conversationId,
        messageId: chatContext?.messageId,
        includeConversation: !!chatContext && includeConversation,
        conversationTranscript: chatContext && includeConversation ? chatContext.getTranscript() : undefined,
        files: attachments.length > 0 ? attachments : undefined,
      },
      {
        onSuccess: () => {
          addToast(t('Thanks for your feedback!'), 'success')
          onClose()
        },
        onError: () => addToast(t('Failed to send feedback. Please try again.'), 'danger'),
      },
    )
  }

  return (
    <ModalShell
      onClose={onClose}
      dirty={dirty}
      closable={!submitFeedback.isPending}
      // The drop handlers live on the PANEL now that the dashed dropzone is gone: dragging a
      // screenshot anywhere onto the dialog still attaches it, which is the behaviour worth
      // keeping - it was only the 56px dashed rectangle advertising it that had to go. The panel's
      // border takes the drop target's green while a file is over it.
      panelProps={{ ...drop.handlers, onPaste }}
      size="xl"
      panelClassName={cn('transition-colors', drop.active && 'border-success')}
    >
      {requestClose => (<>
        <DialogHeader title={t('Send feedback')} />
        {/* Two columns from `md` - what you say on the left, what goes with it on the right - so
            the form fits a normal window with no scrollbar of its own. Nothing here scrolls: on a
            genuinely short screen the dialog's overlay does, which never squeezes a child (a flex
            column that could shrink once turned the message box into a 2px sliver). */}
        <DialogBody>
          <FormColumns from="md" density="columns" className="items-start">
            <FormStack>
              {/* The mood comes FIRST, above the box. It is the one required answer on this form,
                  and asking for it after the free text meant the only mandatory field was the one
                  below everything - which is what the "pick a mood" error existed to recover from. */}
              <Field label={t('How was it?')} error={moodError}>
                {id => (
                  <div id={id} className={cn(moodShake.shaking && 'animate-shake')}>
                    <MoodPicker
                      value={mood}
                      onChange={value => { setMood(value); setMoodError('') }}
                      ariaLabel={t('How was it?')}
                    />
                  </div>
                )}
              </Field>

              {/* The kit Textarea, resizable vertically. No `scrollbar-hide`: with a fixed height and
                  the scrollbar suppressed, pressing Enter DID insert a newline - the caret simply
                  scrolled out of sight with no scrollbar and no way to tell anything had happened,
                  so the box read as refusing the key. autoComplete/name are set because a bare
                  textarea sitting in a dialog next to an email field is exactly what a browser
                  password manager offers to fill. */}
              <Textarea
                value={comment}
                onChange={e => setComment(e.target.value)}
                placeholder={t("Tell us what's on your mind (optional)")}
                rows={5}
                name="feedback-comment"
                autoComplete="off"
                data-1p-ignore
                data-lpignore="true"
                resize
                className="h-32 md:h-44 overflow-y-auto"
              />
            </FormStack>

            <FormStack>
              {/* Both default to OFF and both say what they actually contain. These are the two
                  things that can carry content the user did not type into this form, so an
                  unticked default and a plain description are the minimum - a box that says only
                  "include logs" is asking for consent to something unstated. The hint under each
                  says what it could contain, because the user is agreeing to send it somewhere
                  they do not control. */}
              <div className="flex flex-col gap-3 border-t md:border-t-0 border-line/30 pt-4 md:pt-0">
                <Checkbox
                  checked={includeLogs}
                  onChange={setIncludeLogs}
                  hint={t('Can contain file, folder and connector names, errors, settings and the words of searches from this machine. Not the contents of your documents.')}
                >
                  {t('Include my latest log file')}
                </Checkbox>
                {chatContext && (
                  <Checkbox
                    checked={includeConversation}
                    onChange={setIncludeConversation}
                    hint={t('Your questions and the answers as written, including text quoted from your documents.')}
                  >
                    {t('Include this conversation')}
                  </Checkbox>
                )}
              </div>

              <div>
                {/* A button, not a dashed drop target. The dropzone was a 56px-tall dashed rectangle
                    for an optional field most submissions leave empty, and it was the largest
                    element on the form after the text box. Dropping still works - the handlers are
                    on the panel - but the thing you SEE is a normal control the size of its purpose. */}
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  {/* The kit's FilePickerButton: the Button and its hidden file input. */}
                  <FilePickerButton multiple onFiles={addFiles}>
                    <Paperclip size={14} aria-hidden="true" />
                    {t('Attach files')}
                  </FilePickerButton>
                  <Caption as="span">{t('or drop or paste them here')}</Caption>
                </div>
                <FieldNote error={attachError} />

                {/* Each attached file is the kit's file chip, its size a readout. */}
                <Presence show={attachments.length > 0}>
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {attachments.map((file, i) => (
                      <Chip
                        key={`${file.name}-${i}`}
                        variant="file"
                        icon={<Paperclip />}
                        label={<>{file.name} <Readout tone="disabled">{formatFileSize(file.size)}</Readout></>}
                        onRemove={() => removeAttachment(i)}
                        removeLabel={t('Remove {name}', { name: file.name })}
                      />
                    ))}
                  </div>
                </Presence>
              </div>

              <Input
                label={t('Email (optional)')}
                id="feedback-email"
                type="email"
                value={contactEmail}
                onChange={e => setContactEmail(e.target.value)}
                placeholder="you@example.com"
                autoComplete="email"
                hint={t('Only if you want a reply.')}
              />
            </FormStack>
          </FormColumns>
        </DialogBody>
        {/* The privacy link in the footer's start slot, Cancel and Send at the right. It keeps the
            kit ExternalLink's own ink - it was once given `text-ink-caption`, which left the one
            link in this footer looking like the captions beside it until you were on it. */}
        <DialogFooter
          start={
            <ExternalLink href="https://proxyma.ai/privacy">
              {t('How feedback data is handled')}
            </ExternalLink>
          }
        >
          <Button variant="secondary" onClick={requestClose} disabled={submitFeedback.isPending}>{t('Cancel')}</Button>
          <Button variant="primary" onClick={handleSend} loading={submitFeedback.isPending}>
            {submitFeedback.isPending ? t('Sending...') : t('Send')}
          </Button>
        </DialogFooter>
      </>)}
    </ModalShell>
  )
}
