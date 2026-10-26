import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Button, DialogBody, DialogFooter, DialogHeader, Field, FormStack, SegmentedControl, Textarea } from '@/components/ui/kit'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { memoryApi, type MemoryCategory } from '@/features/settings/services/memoryApi'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { withReason } from '@/services/apiError'
import { useTranslation } from '@/i18n'

// "Remember something", from the composer's + menu (the maintainer's request, 2026-10-23): a fact or
// a preference straight into the agent's memory - Settings > Memory - without a chat turn spent
// asking it to remember. Saved through the agent's own remember path (POST /api/v1/memory), so a
// restated fact replaces the entry it restates rather than sitting beside it.
export function RememberDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const [text, setText] = useState('')
  const [category, setCategory] = useState<MemoryCategory>('KNOWLEDGE')
  const { dirty } = useFormDirty({ text, category })

  const save = useMutation({
    mutationFn: () => memoryApi.create(text.trim(), category),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.agentMemory() })
      addToast(t('Saved to memory'), 'success')
      onClose()
    },
    onError: (e: unknown) => addToast(withReason(t('Could not save to memory'), e), 'danger'),
  })
  const canSave = text.trim().length > 0 && !save.isPending

  return (
    <ModalShell onClose={onClose} closable={!save.isPending} dirty={dirty} size="md" initialFocus="first-field"
      ariaLabel={t('Remember something')}>
      {requestClose => (<>
        <DialogHeader title={t('Remember something')}
          subtitle={t('The agent reads its memory before it searches, in every conversation.')} />
        <DialogBody>
          <FormStack>
            <Field label={t('What to remember')}>
              {id => (
                <Textarea
                  id={id}
                  value={text}
                  onChange={e => setText(e.target.value)}
                  rows={4}
                  maxLength={2000}
                  placeholder={t('Our release branch is called release/next.')}
                  onKeyDown={e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && canSave) { e.preventDefault(); save.mutate() } }}
                />
              )}
            </Field>
            <Field label={t('Kind')}>
              <SegmentedControl<MemoryCategory>
                ariaLabel={t('Kind')}
                value={category}
                onChange={setCategory}
                options={[
                  { value: 'KNOWLEDGE', label: t('Knowledge') },
                  { value: 'USER', label: t('About you') },
                ]}
              />
            </Field>
          </FormStack>
        </DialogBody>
        <DialogFooter>
          <Button variant="secondary" onClick={requestClose} disabled={save.isPending}>{t('Cancel')}</Button>
          <Button onClick={() => save.mutate()} disabled={!canSave} loading={save.isPending}>{t('Remember')}</Button>
        </DialogFooter>
      </>)}
    </ModalShell>
  )
}
