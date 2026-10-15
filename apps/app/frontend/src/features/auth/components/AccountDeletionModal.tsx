import { useState } from 'react'
import {
  Button, ConfirmDialog, DialogBody, DialogDescription, DialogFooter, DialogTitle, List, ListRow,
} from '@/components/ui/kit'
import { Rich } from '@/i18n/kit'
import { AlertTriangle, Files, Database, MessageSquare, UserX, Clock, type LucideIcon } from 'lucide-react'
import { useTranslation } from '@/i18n'
import { ModalShell } from '@/components/ui/ModalShell'

interface TriggerModalProps {
  mode: 'trigger'
  ssoEnabled: boolean
  onConfirm: () => void
  onCancel: () => void
  isSubmitting?: boolean
}

interface GateModalProps {
  mode: 'gate'
  deletionRequestedAt: string | null
  onKeepWaiting: () => void
  onRecover: () => void
  onDeleteNow: () => void
  isSubmitting?: boolean
}

type Props = TriggerModalProps | GateModalProps

const GRACE_DAYS = 7

function daysRemaining(deletionRequestedAt: string | null): number {
  if (!deletionRequestedAt) return GRACE_DAYS
  const elapsedMs = Date.now() - new Date(deletionRequestedAt).getTime()
  const remaining = GRACE_DAYS - Math.floor(elapsedMs / (1000 * 60 * 60 * 24))
  return Math.max(0, remaining)
}

interface DeletionItem {
  icon: LucideIcon
  /** English text, and the catalog key - translated where the list is rendered. */
  title: string
  subtitle: string
  /** Substituted into `title`. Only the grace-period row needs one; see the note on it. */
  titleParams?: Record<string, string | number>
}

// SSO accounts stay open (the IdP remains the source of truth for the account itself) -
// only the data is wiped, immediately, with no grace period. Non-SSO accounts are the account
// itself, so login data and the withdrawal window are also listed.
const DATA_ITEMS: DeletionItem[] = [
  { icon: Files, title: 'Uploaded files', subtitle: 'Any files you uploaded are deleted, if present' },
  { icon: Database, title: 'Search index data', subtitle: 'Vector and keyword index data for your connectors' },
  { icon: MessageSquare, title: 'Conversations', subtitle: 'Your chat history and saved conversations' },
]

const ACCOUNT_ITEMS: DeletionItem[] = [
  ...DATA_ITEMS,
  { icon: UserX, title: 'Login data', subtitle: 'Your account and credentials are completely deleted' },
  // A PLACEHOLDER RATHER THAN A TEMPLATE LITERAL. `${GRACE_DAYS}-day grace period` produces a
  // different string every time the constant changes and is not a quoted literal at all, so the
  // checker cannot see it and no catalog entry could ever match it. This keeps GRACE_DAYS as the
  // single source of the number while leaving a stable key to translate.
  { icon: Clock, title: '{days}-day grace period', titleParams: { days: GRACE_DAYS }, subtitle: 'You can recover the account any time before it completes' },
]

// Two flows share one component since they're both destructive-action confirmations gated the
// same visual way (the kit's centred confirmation, red icon, portal to body):
//   - 'trigger': the user clicks "Delete account" - confirms before calling requestDeletion().
//     SSO mode gets the same irreversible-action treatment as non-SSO's "delete now", since it
//     also wipes data immediately with no grace period.
//   - 'gate': rendered as a blocking overlay (from AppShell) whenever accountStatus is
//     PENDING_DELETION - offers keep-waiting / recover / delete-now, mirroring the account's
//     current 7-day grace period.
export function AccountDeletionModal(props: Props) {
  const [confirmingDeleteNow, setConfirmingDeleteNow] = useState(false)
  const { t } = useTranslation()

  // The kit's centred confirmation body (as ConfirmDialog draws it): the glyph in the danger ink,
  // the title, what happens.
  const head = (title: string, body: React.ReactNode) => (
    <>
      <AlertTriangle size={32} className="mb-4 text-danger" aria-hidden="true" />
      <DialogTitle>{title}</DialogTitle>
      <DialogDescription className="mt-2">{body}</DialogDescription>
    </>
  )

  if (props.mode === 'trigger') {
    const { ssoEnabled, onConfirm, onCancel, isSubmitting } = props
    const items = ssoEnabled ? DATA_ITEMS : ACCOUNT_ITEMS
    return (
      <ModalShell
        onClose={onCancel}
        closable={!isSubmitting}
        role="alertdialog"
        ariaLabel={t('Delete account')}
        size="sm"
      >
        <DialogBody centered>
          {head(
            t('Delete account'),
            // Double quotes so the apostrophe is a literal character rather than a backslash
            // escape - the catalog key has to be the text the reader sees.
            ssoEnabled
              ? t("Your SSO account stays open, but your data is deleted immediately. This cannot be undone.")
              : t('This starts the deletion process. Your account is locked immediately and permanently deleted once the grace period ends.'),
          )}
          {/* What goes, one kit list row each - the thing's glyph, its name, and what it means. */}
          <List className="mt-4 self-stretch text-left">
            {items.map(item => (
              <ListRow
                key={item.title}
                leading={<item.icon size={16} className="text-ink-caption" aria-hidden="true" />}
                title={t(item.title, item.titleParams)}
                caption={t(item.subtitle)}
              />
            ))}
          </List>
        </DialogBody>
        <DialogFooter>
          <Button variant="secondary" onClick={onCancel} disabled={isSubmitting}>{t('Cancel')}</Button>
          <Button variant="danger" onClick={onConfirm} loading={isSubmitting}>
            {isSubmitting ? t('Processing...') : ssoEnabled ? t('Delete my data') : t('Start deletion')}
          </Button>
        </DialogFooter>
      </ModalShell>
    )
  }

  const { deletionRequestedAt, onKeepWaiting, onRecover, onDeleteNow, isSubmitting } = props
  const remaining = daysRemaining(deletionRequestedAt)

  return (
    <ModalShell
      // No onClose: this gate is the whole page while the account is pending deletion.
      role="alertdialog"
      ariaLabel={t('Account pending deletion')}
      closeOnBackdrop={false}
      size="md"
    >
      <DialogBody centered>
        {head(
          t('Account pending deletion'),
          <>
            {/* The runtime's two-form plural rather than a ternary on an 's'. Vietnamese does
                not inflect for number, so its side of the entry is one form for both. */}
            <Rich
              text={t('Your account is scheduled for deletion in {days}.')}
              parts={{ days: <span className="font-semibold text-ink-primary">{t('{count} day|{count} days', { count: remaining })}</span> }}
            />{' '}
            {t('Access is locked until you recover the account or the deletion completes.')}
          </>,
        )}

      </DialogBody>
      {/* Wraps on a phone rather than squeezing three actions onto one line. */}
      <DialogFooter wrap>
        <Button variant="secondary" onClick={onKeepWaiting} disabled={isSubmitting}>{t('Keep waiting')}</Button>
        <Button variant="primary" onClick={onRecover} disabled={isSubmitting}>{t('Recover my account')}</Button>
        <Button variant="danger-quiet" className="ml-2" onClick={() => setConfirmingDeleteNow(true)} disabled={isSubmitting}>
          {t('Delete now')}
        </Button>
      </DialogFooter>

      {/* "Delete now" is a quiet danger action, and the danger FILL waits for this - the moment
          something is actually lost. The kit's ConfirmDialog over the gate, as every destructive
          confirmation is, where it was a callout swapped in for the footer. */}
      {confirmingDeleteNow && (
        <ConfirmDialog
          title={t('Delete now')}
          description={t('Deleting now is immediate and cannot be undone. Are you sure?')}
          confirmLabel={t('Yes, delete now')}
          cancelLabel={t('Back')}
          pendingLabel={t('Deleting...')}
          pending={isSubmitting}
          onConfirm={onDeleteNow}
          onCancel={() => setConfirmingDeleteNow(false)}
        />
      )}
    </ModalShell>
  )
}
