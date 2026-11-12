import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Button, ButtonLink, Callout, Caption, DialogBody, DialogFooter, DialogHeader, FadeSwap, Field, FormStack,
  InlineNotice, Presence, SecretInput, StepItem, StepList, Stepper, type Step,
} from '@/components/ui/kit'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { providersApi } from '../services/providersApi'
import { providerModelsApi } from '../services/providerModelsApi'
import { settingsApi } from '../services/settingsApi'
import { useSecretLabels } from './settingsParts'
import { GEMINI_KEY_URL, pickGeminiModels } from '../utils/geminiQuickStart'
import { apiErrorText } from '@/services/apiError'
import { keys } from '@/services/queryKeys'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useTranslation } from '@/i18n'

type Phase = 'save' | 'check' | 'models'
const PHASES: Phase[] = ['save', 'check', 'models']

/**
 * THE FREE-GEMINI QUICK START (the maintainer's decision, 2026-10-30): the one guided way from a
 * fresh install with no AI provider to a first answer, for an owner who has never seen an API key.
 * Offered from the new-chat screen on Desktop and Home Server while no provider exists
 * (offersGeminiQuickStart).
 *
 * It is the SAME path as Settings > AI Providers, not a second one: the provider is created through
 * providersApi, checked by asking Google for its model list through the provider's own models
 * endpoint, and the two roles a working install needs - the agent's model (chat) and the embedding
 * model - are saved through settingsApi, the calls the Agents and Search tabs make. The models are
 * chosen from the list Google returned (pickGeminiModels), never assumed to exist. A key Google
 * refuses leaves no provider behind: the one just created is removed, and the reason is shown.
 *
 * The free tier's terms are said in one plain sentence: Google may use what is sent to improve its
 * products. Nothing here claims more privacy than that.
 */
export function GeminiQuickStart({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const secretLabels = useSecretLabels()
  const [key, setKey] = useState('')
  const [phase, setPhase] = useState<Phase | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<{ chat: string; embedding: string } | null>(null)
  const { dirty } = useFormDirty({ key })

  const connect = useMutation({
    mutationFn: async () => {
      setError(null)
      setPhase('save')
      const instance = await providersApi.create({ name: 'Gemini', protocol: 'GEMINI', apiKey: key.trim() })
      try {
        setPhase('check')
        const { models } = await providerModelsApi.listModels(instance.id)
        setPhase('models')
        const picked = pickGeminiModels(models)
        if (!picked.chat || !picked.embedding) {
          throw new Error(t('Google answered, but offered no chat or embedding model to this key.'))
        }
        await settingsApi.saveField('chat', 'provider', instance.id)
        await settingsApi.saveField('chat', 'model', picked.chat)
        await settingsApi.saveField('embedding', 'provider', instance.id)
        await settingsApi.saveField('embedding', 'model', picked.embedding)
        return { chat: picked.chat, embedding: picked.embedding }
      } catch (e) {
        // A key Google refused is no provider to keep - the next try starts clean.
        await providersApi.remove(instance.id).catch(() => undefined)
        throw e
      }
    },
    onSuccess: result => {
      setPhase(null)
      setDone(result)
    },
    onError: (e: unknown) => {
      setPhase(null)
      setError(apiErrorText(e) ?? (e instanceof Error && e.message ? e.message : t('Could not connect to Gemini.')))
    },
    onSettled: () => {
      // The same refreshes a saved provider and a saved model role trigger in Settings.
      qc.invalidateQueries({ queryKey: keys.providerInstances() })
      qc.invalidateQueries({ queryKey: keys.providerConnectivity() })
      qc.invalidateQueries({ queryKey: keys.settings('chat') })
      qc.invalidateQueries({ queryKey: keys.settings('embedding') })
    },
  })

  const phaseLabels: Record<Phase, string> = {
    save: t('Save the key'),
    check: t('Check the key with Google'),
    models: t('Choose the models'),
  }
  const steps: Step[] = PHASES.map((p, i) => ({
    key: p,
    label: phaseLabels[p],
    state: phase === null ? 'pending' : i < PHASES.indexOf(phase) ? 'done' : p === phase ? 'running' : 'pending',
  }))

  const otherProvider = () => {
    onClose()
    useSettingsModalStore.getState().open('AI Providers')
  }
  const canConnect = key.trim().length > 0 && !connect.isPending

  return (
    <ModalShell onClose={onClose} closable={!connect.isPending} dirty={dirty && !done} size="md"
      initialFocus="first-field" ariaLabel={t('Get a free Gemini key')}>
      {requestClose => (<>
        <DialogHeader
          title={done ? t('Proxyma is ready') : t('Get a free Gemini key')}
          subtitle={done ? undefined : t('Proxyma needs an AI model. Gemini has a free tier with just a Google account.')}
        />
        <DialogBody>
          {/* The form and the result swap in place, fading; the footer swaps with them and fades in
              by itself (DialogFooter). */}
          <FadeSwap swapKey={done ? 'done' : 'form'}>
            {done ? (
              <FormStack>
                <Callout tone="success" title={t('Connected to Gemini')}>
                  {t('Chat uses {chat}. Documents are indexed with {embedding}.', { chat: done.chat, embedding: done.embedding })}
                </Callout>
                <Caption>{t('Ask "What can you do?", or add a folder first.')}</Caption>
              </FormStack>
            ) : (
              <FormStack>
                <StepList>
                  <StepItem text={t('Open Google AI Studio and sign in with your Google account.')}>
                    <ButtonLink variant="secondary" size="sm" href={GEMINI_KEY_URL} external>{t('Open Google AI Studio')}</ButtonLink>
                  </StepItem>
                  <StepItem text={t('Choose Create API key, then copy the key.')} />
                  <StepItem text={t('Paste it below and choose Connect.')} />
                </StepList>
                <Field label={t('Gemini API key')}>
                  {id => (
                    <SecretInput
                      id={id}
                      value={key}
                      onChange={v => { setKey(v); setError(null) }}
                      labels={secretLabels}
                    />
                  )}
                </Field>
                <Presence show={connect.isPending}>
                  <Stepper steps={steps} label={t('Connecting to Gemini')} />
                </Presence>
                <Presence show={!!error}>
                  <InlineNotice tone="danger">{error}</InlineNotice>
                </Presence>
                <Caption>
                  {t('On the free tier, Google may use what you send. For private documents, use a paid key.')}
                </Caption>
              </FormStack>
            )}
          </FadeSwap>
        </DialogBody>
        {done ? (
          <DialogFooter key="done">
            <Button variant="secondary" onClick={() => { onClose(); navigate('/sources') }}>{t('Add your documents')}</Button>
            <Button onClick={onClose}>{t('Start chatting')}</Button>
          </DialogFooter>
        ) : (
          <DialogFooter key="form" start={(
            <Button variant="quiet" onClick={otherProvider} disabled={connect.isPending}>{t('Use another provider')}</Button>
          )}>
            <Button variant="secondary" onClick={requestClose} disabled={connect.isPending}>{t('Cancel')}</Button>
            <Button onClick={() => connect.mutate()} disabled={!canConnect} loading={connect.isPending}>{t('Connect')}</Button>
          </DialogFooter>
        )}
      </>)}
    </ModalShell>
  )
}
