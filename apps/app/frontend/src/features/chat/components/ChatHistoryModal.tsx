import { ModalShell } from '@/components/ui/ModalShell'
import { Button, DialogHeader } from '@/components/ui/kit'
import { ChatSessionList } from './ChatSessionList'
import type { ConversationListSource } from '../services/chatApi'
import { useTranslation } from '@/i18n'

interface Props {
  activeId?: string
  onSelect: (id: string) => void
  onClose: () => void
  /** A panel's own conversations instead of the chat page's (ChatSessionList). */
  source?: ConversationListSource
  /** Told of each conversation deleted from the list (ChatSessionList). */
  onDeleted?: (id: string) => void
}

export function ChatHistoryModal({ activeId, onSelect, onClose, source, onDeleted }: Props) {
  const { t } = useTranslation()
  return (
    <ModalShell
      onClose={onClose}
      ariaLabel={t('Conversation history')}
      // A BOTTOM SHEET on a phone held upright - full width, against the bottom edge where the
      // thumb is - and a centred panel from `sm` up (the kit's `sheet`). On a short screen (a phone
      // on its side) it takes nearly the whole height, since 70% of 375px is four rows.
      size="md"
      sheet
    >
        {/* The retention rule sits under the title, where it is read before the list rather than
            under the last row (maintainer's feedback, 2026-10-03 - it was a footer, and covered the
            row it sat on). The per-row pin tooltip repeats it at the control that changes it. */}
        {/* Every dialog's padding (the UI review, 2026-10-28, ended the 20px exception): the rows
            under it are inset to the same line (ChatSessionList). */}
        <DialogHeader
          title={t('Conversation history')}
          subtitle={t('Conversations are kept for 7 days after their last activity. Pin one to keep it indefinitely.')}
        />
        {/* The one region that scrolls: the list itself (ChatSessionList). */}
        <div className="flex-1 min-h-0 overflow-hidden flex flex-col">
          {/* Closing IS the action: the dialog opens over chat (or a panel's), whose composer is where
              a first conversation starts. */}
          <ChatSessionList
            activeId={activeId}
            onSelect={onSelect}
            source={source}
            onDeleted={onDeleted}
            emptyAction={<Button variant="secondary" onClick={onClose}>{t('Ask a question')}</Button>}
          />
        </div>
    </ModalShell>
  )
}
