import { useState, useEffect } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import {
  Button, Caption, DialogBody, DialogFooter, DialogHeader, FieldSkeleton, InlineCode, ListRow,
  Presence, RadioGroupSkeleton, SettingsSection, SettingsSections, Skeleton, Textarea,
} from '@/components/ui/kit'
import { configApi } from '@/services/configApi'
import { settingsApi } from '../services/settingsApi'
import type { ProviderInstance } from '../services/providersApi'
import { withReason } from '@/services/apiError'
import { useSettings } from '../hooks/useSettings'
import { useResolved } from '../pages/shared'
import { useModelLimits } from '../hooks/useModelLimits'
import { unsetLimit, type UnsetLimit } from '../utils/modelDefaults'
import { useAuthStore } from '@/store/authStore'
import { useToastStore } from '@/store/toastStore'
import { FEATURES } from '@/config/features'
import { queryClient } from '@/app/QueryClient'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import type { TabProps } from '../pages/shared'
import { ChoiceField, ProviderModelRow, useProviderInstances } from '../components/settingsParts'

// - Agents tab: the main agent, then the sub-agents -

const TONES = [
  { key: 'concise',  label: 'Concise' },
  { key: 'normal',   label: 'Normal' },
  { key: 'creative', label: 'Creative' },
]

// What the agent runs with until it is told otherwise - ChatSettings' own default, repeated here
// only for the moment before the settings have loaded. The output ceiling and the context window
// have no such number: unset, they are the model's own (see unsetLimit).
const DEFAULT_MAX_ITERATIONS = '50'

/**
 * The first choice of an output-ceiling or context-window group - the one that means "not set",
 * stored as '' - in words: whose number it is, and the number where there is one.
 */
function useUnsetLimitChoice() {
  const { t } = useTranslation()
  return (limit: UnsetLimit) => {
    const words = limit.words === 'same' ? t('Same as the agent')
      : limit.words === 'model' ? t('Model default')
      : limit.words === 'provider' ? t('Provider default')
      : t('Default')
    return { key: '', label: limit.amount ? `${words} (${limit.amount})` : words }
  }
}

const MAX_OUTPUT_TOKENS_OPTIONS = [
  { key: '8192',   label: '8k' },
  { key: '16384',  label: '16k' },
  { key: '32768',  label: '32k' },
  // 64000, not 65536 (which this was until 2026-10-03): several current models state 64000 as
  // their ceiling and refuse a request one token over it.
  { key: '64000',  label: '64k' },
  { key: '131072', label: '128k' },
]

// The agent's and the sub-agents' alike - the maintainer's lists, 2026-10-03. A value stored
// before then that is not one of them (200k, 30 steps) still applies, and shows no choice selected.
const CONTEXT_WINDOW_OPTIONS = [
  { key: '128000',  label: '128k' },
  { key: '256000',  label: '256k' },
  { key: '500000',  label: '500k' },
  { key: '1000000', label: '1M' },
]

// NO LIMIT is stored as '0' (ChatSettings.NO_ITERATION_LIMIT), for a sub-agent as for the agent:
// the sub-agent's time limit still ends it.
const MAX_AGENT_ITERATIONS_OPTIONS = [
  { key: '20', label: '20' },
  { key: '50', label: '50' },
  { key: '100', label: '100' },
  { key: '0', label: 'Unlimited' },
]

// How long one sub-agent may run, in minutes (SubAgentSettings.timeoutMinutes). No "unlimited",
// for the reason above; the labels are numbers, like the iteration options.
const SUB_AGENT_TIMEOUT_OPTIONS = [
  { key: '5', label: '5' },
  { key: '15', label: '15' },
  { key: '30', label: '30' },
  { key: '60', label: '60' },
  { key: '120', label: '120' },
]

// Settings > Agents "Prompt cache". Offered only while the agent's provider takes an explicit cache
// instruction: Anthropic, OpenRouter, and LiteLLM on its Anthropic Messages API (cachesOnRequest).
// OpenAI, Azure, Gemini and the rest cache on their own terms and would ignore the choice, so
// showing it there would be a control that does nothing.
// Stored as the backend's ChatSettings.PromptCache values. AUTO REPLACED OFF on 2026-10-07
// (maintainer's feedback): Auto is never less than five minutes, and steps up to an hour while
// background sub-agents work or the reader pauses longer between turns (PromptCache.forTurn). A
// stored 'off' reads as Auto on both sides.
const PROMPT_CACHE_OPTIONS = [
  { key: 'auto', label: 'Auto' },
  { key: '5m',   label: '5 minutes' },
  { key: '1h',   label: '1 hour' },
]
const PROMPT_CACHE_PROTOCOLS = new Set(['ANTHROPIC', 'OPENROUTER'])

/** Whether a provider takes the prompt-cache choice - see PROMPT_CACHE_OPTIONS. */
function cachesOnRequest(instance: ProviderInstance | undefined): boolean {
  if (!instance) return false
  return PROMPT_CACHE_PROTOCOLS.has(instance.protocol)
    || (instance.protocol === 'LITELLM' && instance.api !== 'OPENAI_CHAT')
}

/**
 * The Prompt cache field for a provider: hidden where the protocol caches on its own terms, and
 * SHOWN GREYED OUT for a LiteLLM provider on OpenAI Chat Completions, with the hint saying where
 * to change that - a field that is simply absent says nothing about why (2026-10-09, the
 * maintainer's report, when it was OpenRouter's switch that was off).
 */
function promptCacheField(instance: ProviderInstance | undefined): 'on' | 'off' | null {
  if (!instance) return null
  if (cachesOnRequest(instance)) return 'on'
  return instance.protocol === 'LITELLM' ? 'off' : null
}

/** A stored prompt-cache value as one of the choices - the retired 'off' is Auto now. */
function promptCacheChoice(stored: string): string {
  return stored === 'off' ? 'auto' : stored
}

function AgentToneField({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const tone = useResolved('chat', 'tone', pending, 'concise')
  return (
    <ChoiceField
      label={t('Response tone')}
      value={tone}
      onChange={v => trackChange('chat', 'tone', v)}
      options={TONES}
      horizontal
    />
  )
}

function AgentIterationsField({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const maxIterations = useResolved('chat', 'max-iterations', pending, DEFAULT_MAX_ITERATIONS)
  return (
    <ChoiceField
      label={t('Max agent iterations')}
      value={maxIterations}
      onChange={v => trackChange('chat', 'max-iterations', v)}
      options={MAX_AGENT_ITERATIONS_OPTIONS}
      horizontal
      figures
    />
  )
}

// Chat tab: provider radio group, model combobox, tone/max-tokens/max-iterations radio groups.
function ChatTabSkeleton({ showSystemPrompt }: { showSystemPrompt: boolean }) {
  return (
    <div className="space-y-6">
      <div className="space-y-6">
        <RadioGroupSkeleton count={3} />
        <FieldSkeleton />
        <RadioGroupSkeleton count={3} horizontal />
        <RadioGroupSkeleton count={5} horizontal />
        <RadioGroupSkeleton count={8} horizontal />
        <RadioGroupSkeleton count={3} horizontal />
        {/* The System Prompt row, last in the master-agent block (see ChatTab) - only rendered for
            canEditSystemPrompt (same check as the real tab), so it doesn't pop in for users who'll
            never see it. */}
        {showSystemPrompt && <Skeleton className="h-14 w-full rounded-lg" />}
      </div>
    </div>
  )
}

export function ChatTab({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const { isLoading } = useSettings('chat')
  // '' while the agent runs with its model's own limits - the first choice of each group.
  const maxTokens = useResolved('chat', 'max-output-tokens', pending, '')
  const contextWindow = useResolved('chat', 'memory-max-tokens', pending, '')
  const promptCache = promptCacheChoice(useResolved('chat', 'prompt-cache', pending, 'auto'))
  const agentProvider = useResolved('chat', 'provider', pending, '')
  const agentModel = useResolved('chat', 'model', pending, '')
  const limits = useModelLimits(agentProvider, agentModel)
  const unsetChoice = useUnsetLimitChoice()
  const { data: instances = [] } = useProviderInstances()
  const agentInstance = instances.find(p => p.id === agentProvider)
  const cacheField = promptCacheField(agentInstance)

  // SERVER-ONLY, and SUPER_ADMIN even there. The prompt carries the rules that stop the agent
  // naming its underlying model vendor or reciting its own instructions, so on desktop - where
  // the only user IS the operator and there are no roles to check - showing it hands every user
  // the text of the guardrails they are the subject of, with an editor beside it. Hidden rather
  // than read-only: a prompt anyone can read is a prompt anyone can work around, and there is no
  // desktop question that reading it answers. ChatSystemPromptController's isSuperAdmin() refuses
  // the endpoints in the same edition, so this is not the only thing standing in the way.
  const user = useAuthStore(s => s.user)
  const canEditSystemPrompt = FEATURES.auth && user?.role === 'SUPER_ADMIN'
  // READ-ONLY ON DESKTOP PROPER, since 2026-10-01 (maintainer's request - "read-only, just like
  // stock skills"), which reverses the paragraph above for exactly one edition: Desktop is one
  // person on their own machine, the operator the guardrails serve. Home Server is the same bundle
  // served to whoever reaches the address, and stays hidden; the backend
  // (ChatSystemPromptController.mayRead) draws the same line.
  const { data: appConfig } = useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false })
  const canReadSystemPrompt = canEditSystemPrompt
    || (!FEATURES.auth && !!appConfig && !appConfig.homeServer)

  const [systemPromptOpen, setSystemPromptOpen] = useState(false)

  if (isLoading) return <ChatTabSkeleton showSystemPrompt={canReadSystemPrompt} />

  return (
    <SettingsSections>
      {/* The main agent's own section, headed like the Sub-agents one under it: the tab is two
          agents, and only one of them was named. */}
      <SettingsSection title={t('Main agent')}>
        <ProviderModelRow
          role="chat"
          providerLabel="Agent provider"
          modelLabel="Agent model"
          modelPlaceholder="gemma4:31b"
          trackChange={trackChange}
          pending={pending}
        />

        <AgentToneField trackChange={trackChange} pending={pending} />

        <ChoiceField
          label={t('Max output tokens')}
          value={maxTokens}
          onChange={v => trackChange('chat', 'max-output-tokens', v)}
          options={[unsetChoice(unsetLimit('output', limits?.maxOutputTokens)), ...MAX_OUTPUT_TOKENS_OPTIONS]}
          horizontal
          figures
        />

        <ChoiceField
          label={t('Context window')}
          value={contextWindow}
          onChange={v => trackChange('chat', 'memory-max-tokens', v)}
          options={[unsetChoice(unsetLimit('context', limits?.contextWindow)), ...CONTEXT_WINDOW_OPTIONS]}
          horizontal
          figures
        />

        <AgentIterationsField trackChange={trackChange} pending={pending} />

        {/* Offered while the agent's provider takes a cache instruction; it eases in and out as
            the provider changes. */}
        <Presence show={!!cacheField}>
          <ChoiceField
            label={t('Prompt cache')}
            hint={cacheField === 'on'
              ? t('How long the provider caches the conversation for the next turn. Auto keeps 5 minutes, and 1 hour while sub-agents work in the background or when you pause longer between turns. 1 hour costs more to write.')
              : t('{provider} uses OpenAI Chat Completions, which takes no cache instructions. Choose Anthropic Messages for it in Settings > AI Providers.', { provider: agentInstance?.name ?? '' })}
            value={promptCache}
            onChange={v => trackChange('chat', 'prompt-cache', v)}
            options={PROMPT_CACHE_OPTIONS}
            horizontal
            disabled={cacheField === 'off'}
          />
        </Presence>

        {/* Inside the master-agent block, not below the Sub-agents section where this used to sit:
            the system prompt is the MASTER agent's prompt (a sub-agent gets its own task brief from
            SubAgentService), so its position has to say so. It carries no section label of its own -
            the row's title and description already say what it is, and an "Instructions" heading
            plus "The system prompt sets the agent's core behavior" restated it twice more.
            A kit ListRow that opens a dialog: a button, with the chevron that says so. */}
        <Presence show={canReadSystemPrompt}>
          <ListRow
            onClick={() => setSystemPromptOpen(true)}
            chevron
            title={t('System prompt')}
            caption={canEditSystemPrompt
              ? t("Overrides the agent's entire system prompt")
              : t('The instructions the agent starts every conversation with. Read-only.')}
          />
        </Presence>
      </SettingsSection>

      <SubAgentSection trackChange={trackChange} pending={pending} />

      {systemPromptOpen && (
        <SystemPromptModal
          onClose={() => setSystemPromptOpen(false)}
          readOnly={!canEditSystemPrompt}
        />
      )}
    </SettingsSections>
  )
}

// Sub-agent settings. Lives on this tab rather than a tab of its own because a sub-agent is
// still the agent - it is the same conversation, a worker the main agent hands work to. Kept
// visually separate (divider + its own description).
//
// NOTHING HERE HAS TO BE SET, since 2026-10-03: every field follows the agent's until it is given
// a value of its own (SubAgentSettings), and the empty choice of each control says so. Choosing it
// again clears the stored value - an empty value takes performSave's deleteField path.
function SubAgentModelRow({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const agentModel = useResolved('chat', 'model', pending, '')
  const agentProvider = useResolved('chat', 'provider', pending, '')
  return (
    <ProviderModelRow
      role="sub-agent"
      providerLabel="Sub-agent provider"
      modelLabel="Sub-agent model"
      modelPlaceholder={agentModel || 'claude-haiku-4-5'}
      unsetProviderLabel={t('Same as the agent')}
      fallbackProvider={agentProvider}
      modelHint={t("Leave the model empty to use the agent's. A cheaper, faster model here keeps long reading off the agent's price. It also titles new conversations.")}
      trackChange={trackChange}
      pending={pending}
    />
  )
}

/** A sub-agent's choices: "Same as the agent" first, naming what that currently is. */
function followingAgent(options: { key: string; label: string }[], agentValue: string, same: string,
                        t: (text: string) => string) {
  const agents = options.find(o => o.key === agentValue)?.label
  return [{ key: '', label: agents ? `${same} (${t(agents)})` : same }, ...options]
}

function SubAgentTimeLimitField({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  // 30 since 2026-10-03, SubAgentSettings' own default.
  const timeoutMinutes = useResolved('sub-agent', 'timeout-minutes', pending, '30')
  return (
    <ChoiceField
      label={t('Sub-agent time limit (minutes)')}
      hint={t('Out of time, a sub-agent is asked once for what it has found. Waiting for your answer to an approval or a question is not counted.')}
      value={timeoutMinutes}
      onChange={v => trackChange('sub-agent', 'timeout-minutes', v)}
      options={SUB_AGENT_TIMEOUT_OPTIONS}
      horizontal
      figures
    />
  )
}

function SubAgentSection({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  // No switch, since 2026-10-03 (the maintainer's request): sub-agents are how the agent works. A
  // sub-agent's writes take the same approval the agent's do, so always-on cannot change, approve
  // or delete anything nobody agreed to.
  //
  // Each value is '' while the sub-agents follow the agent - the first choice of each group, which
  // names what the agent's currently is.
  const maxIterations = useResolved('sub-agent', 'max-iterations', pending, '')
  const maxTokens = useResolved('sub-agent', 'max-output-tokens', pending, '')
  const contextWindow = useResolved('sub-agent', 'context-max-tokens', pending, '')
  const agentIterations = useResolved('chat', 'max-iterations', pending, DEFAULT_MAX_ITERATIONS)
  const same = t('Same as the agent')
  // The two limits follow the agent's SETTING. Where the agent has none, each side takes its own
  // model's - so the number named here is the sub-agents' model's, which is the agent's only while
  // they have no model of their own.
  const agentMaxTokens = useResolved('chat', 'max-output-tokens', pending, '')
  const agentContextWindow = useResolved('chat', 'memory-max-tokens', pending, '')
  const agentProvider = useResolved('chat', 'provider', pending, '')
  const agentModel = useResolved('chat', 'model', pending, '')
  const ownProvider = useResolved('sub-agent', 'provider', pending, '')
  const ownModel = useResolved('sub-agent', 'model', pending, '')
  const hasOwnModel = ownProvider !== '' && ownModel.trim() !== ''
  const limits = useModelLimits(hasOwnModel ? ownProvider : agentProvider, hasOwnModel ? ownModel : agentModel)
  const unsetChoice = useUnsetLimitChoice()

  return (
    <SettingsSection
      title={t('Sub-agents')}
      caption={t("The agent hands work to sub-agents and gets back only their reports. A sub-agent has the agent's tools and settings unless you choose others here; its changes need your approval like the agent's.")}
    >
      <SubAgentModelRow trackChange={trackChange} pending={pending} />

      <ChoiceField
        label={t('Max sub-agent iterations')}
        value={maxIterations}
        onChange={v => trackChange('sub-agent', 'max-iterations', v)}
        // An agent with no limit hands that on (SubAgentSettings.maxIterations); the time limit
        // below is what ends a sub-agent then.
        options={followingAgent(MAX_AGENT_ITERATIONS_OPTIONS, agentIterations, same, t)}
        horizontal
        figures
      />

      <ChoiceField
        label={t('Sub-agent max output tokens')}
        value={maxTokens}
        onChange={v => trackChange('sub-agent', 'max-output-tokens', v)}
        options={[unsetChoice(unsetLimit('output', limits?.maxOutputTokens, agentMaxTokens)), ...MAX_OUTPUT_TOKENS_OPTIONS]}
        horizontal
        figures
      />

      {/* Worth setting when the sub-agent's model has a smaller window than the agent's: past
          80% of it the sub-agent drops its oldest tool results to keep going. */}
      <ChoiceField
        label={t('Sub-agent context window')}
        value={contextWindow}
        onChange={v => trackChange('sub-agent', 'context-max-tokens', v)}
        options={[unsetChoice(unsetLimit('context', limits?.contextWindow, agentContextWindow)), ...CONTEXT_WINDOW_OPTIONS]}
        horizontal
        figures
      />

      <SubAgentPromptCacheField
        trackChange={trackChange}
        pending={pending}
        provider={hasOwnModel ? ownProvider : agentProvider}
      />

      <SubAgentTimeLimitField trackChange={trackChange} pending={pending} />
    </SettingsSection>
  )
}

// A sub-agent's own prompt cache (2026-10-07, maintainer's feedback: sub-agents must cache too, and
// had no option). It follows the agent's until set, as everything in this section does, and it is
// offered where the agent's is: while the sub-agents' provider takes a cache instruction. Auto is
// five minutes for a sub-agent - its calls come seconds apart (SubAgentSettings.promptCache).
function SubAgentPromptCacheField({ trackChange, pending, provider }: TabProps & { provider: string }) {
  const { t } = useTranslation()
  const own = promptCacheChoice(useResolved('sub-agent', 'prompt-cache', pending, ''))
  const agents = promptCacheChoice(useResolved('chat', 'prompt-cache', pending, 'auto'))
  const { data: instances = [] } = useProviderInstances()
  const instance = instances.find(p => p.id === provider)
  const field = promptCacheField(instance)
  // In or out as the sub-agents' provider changes - a Presence in the place the field holds.
  return (
    <Presence show={!!field}>
    <ChoiceField
      label={t('Sub-agent prompt cache')}
      hint={field === 'on'
        ? t('Auto is 5 minutes for a sub-agent, whose calls come seconds apart.')
        : t('{provider} uses OpenAI Chat Completions, which takes no cache instructions. Choose Anthropic Messages for it in Settings > AI Providers.', { provider: instance?.name ?? '' })}
      value={own}
      onChange={v => trackChange('sub-agent', 'prompt-cache', v)}
      options={followingAgent(PROMPT_CACHE_OPTIONS, agents, t('Same as the agent'), t)}
      horizontal
      disabled={field === 'off'}
    />
    </Presence>
  )
}

// Only rendered when canEditSystemPrompt - SUPER_ADMIN, in the server edition only (see caller;
// desktop does not show this at all) - so the modal is already gated on visibility and its own
// content doesn't need to call that out again. See ChatService.resolveSystemPrompt and
// ChatSystemPromptController for the matching backend enforcement.
//
// There is one prompt (no smart/light variant): the built-in default or an admin's saved custom
// prompt, backed by a markdown file on disk (SystemPromptService), not a page-level settings field,
// so this modal owns its own Save/Cancel and calls the dedicated endpoints directly - same
// reasoning as SkillModal, and deliberately NOT wired through trackChange/pending: that made every
// keystroke here dirty the whole Settings page, so the page's own Save/Cancel bar showed through
// this modal's blurred backdrop while editing a prompt that Done didn't actually persist on its own.
function SystemPromptModal({ onClose, readOnly = false }: { onClose: () => void; readOnly?: boolean }) {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const [resetting, setResetting] = useState(false)
  const [value, setValue] = useState('')
  const [seeded, setSeeded] = useState(false)

  // The effective prompt (an admin's saved custom prompt, or the built-in default) - never blank,
  // so the editor always opens on real content. A reader who cannot edit is shown what a
  // conversation is actually started with instead: the same prompt with the section on delegating
  // that the server adds. The raw prompt has no such section, so the dialog used to leave out the
  // part that says how the agent works.
  const { data: prompt } = useQuery({
    queryKey: ['chat-system-prompt', readOnly ? 'assembled' : 'raw'],
    queryFn: readOnly ? settingsApi.getChatSystemPromptAssembled : settingsApi.getChatSystemPrompt,
  })
  // A prompt saved by a version before 0.1.2 may still carry these. Nothing newer does.
  const hasLegacyMarkers = value.includes('{{#IF_DELEGATION}}')

  // Seeds the editable textarea once, so the user's own edits are never clobbered by a background
  // refetch - same one-time catch-up pattern as SkillModal's detail field.
  // The seeded prompt is also the baseline the dirty check compares against.
  const { dirty, rebase } = useFormDirty(value)
  useEffect(() => {
    if (seeded) return
    if (prompt !== undefined) { setValue(prompt); rebase(prompt); setSeeded(true) }
  }, [prompt, seeded, rebase])

  // A failure says so - it was an unhandled rejection, and the button simply stopped turning.
  async function resetToDefault() {
    setResetting(true)
    try {
      setValue(await settingsApi.getChatSystemPromptDefault())
    } catch (e) {
      addToast(withReason(t('Could not load the default prompt'), e), 'danger')
    } finally {
      setResetting(false)
    }
  }

  const save = useMutation({
    mutationFn: () => settingsApi.saveChatSystemPrompt(value),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['chat-system-prompt'] })
      addToast(t('System prompt saved'), 'success')
      onClose()
    },
    onError: (e: unknown) => addToast(withReason(t('Failed to save system prompt'), e), 'danger'),
  })

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      // A fixed height, so the dialog does not change size as the prompt loads; the textarea is
      // its one scrolling region.
      size="xl"
      fill
      ariaLabel={t('System prompt')}
    >
      {requestClose => (<>
        <DialogHeader
          title={t('System prompt')}
          actions={!readOnly && (
            <Button variant="hyperlink" onClick={resetToDefault} disabled={resetting}>
              {resetting ? t('Resetting...') : t('Reset to default')}
            </Button>
          )}
        />
        <DialogBody className="flex-1 min-h-0 flex flex-col gap-3">
          {/* What the server does to this text (SystemPromptService, ChatService.resolveSystemPrompt),
              so an admin editing it knows what not to "clean up" and what they will not find in it:
              PRODUCT_NAME becomes the deployment's name, and the section on delegating is added from
              outside. The IF_DELEGATION markers are named only to a prompt that still has them. */}
          {readOnly ? (
            <Caption>
              {t("What the agent is given when a conversation starts. Today's date and the lists of skills and workflows are added to it.")}
            </Caption>
          ) : (
            <Caption>
              <InlineCode>{'{{PRODUCT_NAME}}'}</InlineCode>{' '}
              {t("becomes this deployment's name - keep it as it is. The section on delegating to sub-agents is added to this prompt automatically.")}
              {/* One sentence with its two markers as slots, so a translation orders it its own way. */}
              {hasLegacyMarkers && (<>
                {' '}
                <Rich
                  text={t('Text between {open} and {close} is included only while sub-agents are enabled.')}
                  parts={{
                    open: <InlineCode>{'{{#IF_DELEGATION}}'}</InlineCode>,
                    close: <InlineCode>{'{{/IF_DELEGATION}}'}</InlineCode>,
                  }}
                />
              </>)}
            </Caption>
          )}
          {/* Read-only is a DISABLED field, as an official skill's Detail is (0.28.0 feedback): the
              reader cannot change it, so it must not look like a box waiting to be typed in. */}
          <Textarea
            fill
            value={value}
            readOnly={readOnly}
            disabled={readOnly}
            aria-label={t('System prompt')}
            onChange={e => setValue(e.target.value)}
            className="py-2"
          />
        </DialogBody>
        {/* No footer when there is nothing to save: a dialog closes on its backdrop and on Escape,
            and a lone Close button is the footer the rule forbids (.claude/rules/frontend.md). */}
        {!readOnly && (
          <DialogFooter>
            <Button variant="secondary" onClick={requestClose} disabled={save.isPending}>{t('Cancel')}</Button>
            <Button onClick={() => save.mutate()} disabled={save.isPending}>
              {save.isPending ? t('Saving...') : t('Save')}
            </Button>
          </DialogFooter>
        )}
      </>)}
    </ModalShell>
  )
}

