import { useState } from 'react'
import {
  Button, Checkbox, DialogBody, DialogDescription, DialogFooter, DialogHeader, FieldNote,
} from '@/components/ui/kit'
import { useThemeStore } from '@/store/themeStore'
import { useShake } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { ModalShell } from '@/components/ui/ModalShell'

interface Props {
  connectorLabel: string
  onAccept: () => void
  onCancel: () => void
}

// A checkbox, where this used to make the user retype a 78-character sentence.
//
// The typing was meant to force the warning to be read. It does the opposite past a certain
// length: the sentence stops being a statement and becomes a transcription exercise, and the eye
// goes to the source text to copy characters rather than to the paragraph above explaining what
// is about to be permitted. Reserve that pattern for destructive, irreversible actions where the
// friction IS the point - deleting an account, wiping an install. This one is reversible from the
// same toggle that set it.
export function WriteToolConsentModal({ connectorLabel, onAccept, onCancel }: Props) {
  const { t } = useTranslation()
  const [acknowledged, setAcknowledged] = useState(false)
  const [error, setError] = useState('')
  const shake = useShake()
  const productName = useThemeStore(s => s.branding.productName)

  const handleAccept = () => {
    if (!acknowledged) {
      setError(t('Tick the box to confirm you understand.'))
      shake.trigger()
      return
    }
    setError('')
    onAccept()
  }

  // Not a ConfirmDialog: that is a centred glyph-and-sentence confirmation, and this is a paragraph
  // to READ and a box to tick. Built from the same dialog parts, left-aligned.
  return (
    <ModalShell onClose={onCancel} size="md" ariaLabel={t('Enable {connector} write tools', { connector: connectorLabel })}>
      <DialogHeader title={t('Enable {connector} write tools', { connector: connectorLabel })} />
      <DialogBody>
        {/* ONE KEY with its three emphasised phrases as slots (Rich), so a translation can order the
            paragraph its own way - it was seven fragments a translator could not rearrange. */}
        <DialogDescription>
          <Rich
            text={t("{product} can't guarantee the AI assistant always uses this tool correctly. Every write action - creating, editing, deleting - {real}, in your connected system. {review} before it proceeds - {responsible} for what it does, not {product}.", { product: productName })}
            parts={{
              real: <strong className="text-ink-primary font-semibold">{t('happens for real, immediately')}</strong>,
              review: <strong className="text-ink-primary font-semibold">{t('You must review and confirm each write action')}</strong>,
              responsible: <strong className="text-ink-primary font-semibold">{t('you are solely responsible')}</strong>,
            }}
          />
        </DialogDescription>
        <Checkbox
          className="mt-5"
          checked={acknowledged}
          shake={shake.shaking}
          onChange={v => { setAcknowledged(v); if (v) setError('') }}
        >
          {t('I have read this and I am responsible for reviewing every write action.')}
        </Checkbox>
        <FieldNote error={error || undefined} />
      </DialogBody>
      <DialogFooter>
        <Button variant="secondary" onClick={onCancel}>{t('Cancel')}</Button>
        {/* Not disabled until ticked. A dead button explains nothing; this one says what is
            missing the moment someone reaches for it. */}
        <Button variant="primary" onClick={handleAccept}>
          {t('Accept')}
        </Button>
      </DialogFooter>
    </ModalShell>
  )
}
