import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  Button, Caption, EmptyState, ExternalLink, FieldLabel, FilePickerButton, InlineNotice, List,
  ListRow, MetaList, Presence, FadeSwap, SettingsSection, SettingsSections, SwitchRow,
} from '@/components/ui/kit'
import { useSettings } from '../hooks/useSettings'
import { ListSkeleton, SignpostSection, TabFieldsSkeleton, useResolved } from '../pages/shared'
import { FEATURES } from '@/config/features'
import { useFileUpload } from '@/hooks/useFileUpload'
import { guardrailPolicyApi } from '../services/guardrailPolicyApi'
import type { PolicyKind } from '../services/guardrailPolicyApi'
import { edition } from '@settings-pages'
import type { OpenTab } from '../pages/types'
import { useTranslation } from '@/i18n'
import { Rich } from '@/i18n/kit'
import { useToastStore } from '@/store/toastStore'
import { apiErrorText, withReason } from '@/services/apiError'
import { PagedItems } from '@/components/ui/ListPager'
import { serverText } from '@/i18n/serverText'
import type { TabProps } from '../pages/shared'
import { ChoiceField, ProviderModelRow, RemoveConfirm } from '../components/settingsParts'

// The guardrails tab.
//
// SIMPLIFIED 2026-09-02, on two axes: the controls, and the words.
//
// FIVE NUMERIC FIELDS CAME OUT OF THE UI. Their backend fields are untouched and keep their
// defaults, so nothing about how a deployment behaves changes and no configuration breaks - only
// the knob is gone:
//
//   examples-threshold      a cosine similarity, and one nobody can set correctly in advance
//   semantic-min-score      the same thing again, for the rules corpus
//   semantic-top-k          how many clauses to retrieve; internal tuning, not policy
//   repeat-window-minutes   folded behind one switch, below
//   repeat-cooldown-minutes the same
//
// The first three are the important removals and the reason is the same for all three: a cosine
// threshold means different things to different embedding models, so the number on the screen was
// never actionable by the person reading it. This project's own hand-off records that these two
// defaults were chosen against a FAKE embedding endpoint whose "embedding" is a hashed bag of
// words with no notion of synonymy - offering an operator a box to type a number into implied a
// precision that nobody, here or there, actually had. Bedrock and Azure both expose the checks and
// not the distances, for the same reason.
//
// WHAT THIS DOES NOT DO is make the numbers right. They are still defaults chosen against a fake
// endpoint, and tuning them against a real model is still open - see the hand-off. Hiding a knob
// is not the same as settling what it should be set to, and monitor mode is still how that gets
// answered.
//
// THE WORDS. Every control carried a paragraph, several carried three, and the tab read as an
// essay with switches in it - so the settings themselves were the hardest thing on the page to
// find. Each control now gets ONE line saying what it does. The reasoning did not evaporate: what
// a reader needs is at /docs/manual/#guardrails, which this tab links to, and what a maintainer needs is
// in these comments and in GuardrailSettings.java, which is where it always belonged.
const GUARDRAIL_MODES = [
  { key: 'ENFORCE', label: 'Enforce' },
  { key: 'MONITOR', label: 'Monitor only' },
]

// The BLOCK/REDACT defaults differ per direction on purpose (see GuardrailSettings). A blocked
// message has cost nothing yet - no provider call, no stored turn - while a blocked answer has
// already been generated and paid for, and throwing the whole thing away because one line in it
// was a path is worse than removing the line.
const GUARDRAIL_ACTIONS = [
  { key: 'BLOCK',  label: 'Block the turn' },
  { key: 'REDACT', label: 'Remove and continue' },
]

// The same two values the backend takes, under labels that say what they actually do on this
// stage. BLOCK on a tool result withholds ONE source and the turn goes on, so calling it "block
// the turn" here would describe something that does not happen and would push operators away from
// the stricter setting for a reason that is not true.
const GUARDRAIL_RETRIEVAL_ACTIONS = [
  { key: 'BLOCK',  label: 'Withhold the whole source' },
  { key: 'REDACT', label: 'Remove and continue' },
]

// What "Slow down repeated attempts" writes when it is switched on. The window and the cooldown
// used to be two more boxes on the screen; these are the values they defaulted to, and an operator
// who needs different ones is describing a rate limiter rather than a guardrail.
//
// Zero is what turns the feature off, which is the backend's own contract - see
// GuardrailSettings.repeatThreshold - so the switch writes 0 or REPEAT_ON_THRESHOLD and never
// touches the other two fields.
const REPEAT_ON_THRESHOLD = '5'

// The three checkpoints, in the order a turn travels: what people send, what the agent reads, what
// it answers.
type GuardrailStage = 'input' | 'retrieval' | 'output'
const GUARDRAIL_STAGES: Record<GuardrailStage, { actionField: string; actionFallback: string }> = {
  input: { actionField: 'on-input-violation', actionFallback: 'BLOCK' },
  retrieval: { actionField: 'on-retrieval-violation', actionFallback: 'REDACT' },
  output: { actionField: 'on-output-violation', actionFallback: 'REDACT' },
}

/** What one checkpoint does when something matches. */
function GuardrailStageAction({ stage, trackChange, pending }: TabProps & { stage: GuardrailStage }) {
  const { t } = useTranslation()
  const spec = GUARDRAIL_STAGES[stage]
  const action = useResolved('guardrail', spec.actionField, pending, spec.actionFallback)
  return (
    <ChoiceField
      label={stage === 'input' ? t('When a message matches')
        : stage === 'output' ? t('When an answer matches')
        : t('When something the agent read matches')}
      // Kept, shortened, because both halves change what an operator would choose: neither
      // option ends the turn, and the injection exception is not configurable.
      hint={stage === 'retrieval' ? t('Neither ends the conversation; prompt injection withholds the source.') : undefined}
      value={action}
      onChange={v => trackChange('guardrail', spec.actionField, v)}
      options={stage === 'retrieval' ? GUARDRAIL_RETRIEVAL_ACTIONS : GUARDRAIL_ACTIONS}
      horizontal
    />
  )
}

export function GuardrailsTab({ trackChange, pending, onOpenTab }: TabProps & { onOpenTab: OpenTab }) {
  // A server of some kind: accounts, or a home server taking uploads. Not a desktop install.
  const takesUploads = useFileUpload()
  const hasServerPaths = FEATURES.auth || takesUploads
  const { t } = useTranslation()
  const { isLoading } = useSettings('guardrail')

  const mode = useResolved('guardrail', 'mode', pending, 'ENFORCE')
  const inputEnabled = useResolved('guardrail', 'input-enabled', pending, 'true') === 'true'
  const outputEnabled = useResolved('guardrail', 'output-enabled', pending, 'true') === 'true'
  const retrievalEnabled = useResolved('guardrail', 'retrieval-enabled', pending, 'true') === 'true'

  const detectSecrets = useResolved('guardrail', 'detect-secrets', pending, 'true') === 'true'
  const detectPii = useResolved('guardrail', 'detect-pii', pending, 'true') === 'true'
  const detectInjection = useResolved('guardrail', 'detect-prompt-injection', pending, 'true') === 'true'
  const detectPaths = useResolved('guardrail', 'detect-path-leak', pending, 'true') === 'true'

  const llmEnabled = useResolved('guardrail', 'llm-enabled', pending, 'false') === 'true'
  const failClosed = useResolved('guardrail', 'fail-closed', pending, 'false') === 'true'
  // 'false' is the fallback as well as the backend default, so a settings store that has never
  // heard of this key reads as off rather than as absent - the one direction that matters here.
  const captureMatchedText =
    useResolved('guardrail', 'capture-matched-text', pending, 'false') === 'true'

  const canaryEnabled = useResolved('guardrail', 'canary-enabled', pending, 'false') === 'true'
  // Read as a NUMBER and rendered as a switch. Any non-zero value counts as on, so a deployment
  // that set 40 by hand before this changed still reads as on and keeps its 40 until somebody
  // toggles it off.
  const repeatThreshold = useResolved('guardrail', 'repeat-threshold', pending, '0')
  const repeatOn = Number(repeatThreshold) > 0

  const semanticEnabled = useResolved('guardrail', 'semantic-enabled', pending, 'false') === 'true'
  const semanticOnOutput = useResolved('guardrail', 'semantic-on-output', pending, 'false') === 'true'

  if (isLoading) return <TabFieldsSkeleton count={6} />

  // A section switch writes its field as 'true'/'false'.
  const toggle = (field: string, checked: boolean) => ({
    checked,
    onChange: (v: boolean) => trackChange('guardrail', field, String(v)),
  })

  return (
    <SettingsSections intro={<>
      {t('Guardrails check what people send, what the agent reads and what it answers.')}{' '}
      <ExternalLink href="https://proxyma.ai/docs/manual/#guardrails">{t('How guardrails work')}</ExternalLink>
    </>}>
      {/* Mode first, because it governs everything below it. An operator arriving to turn
          guardrails on for the first time should see that there is a way to watch before
          enforcing, rather than discovering it after the first refused message. */}
      <SettingsSection title={t('Mode')}>
        <ChoiceField
          hint={t('Monitor runs every check but applies none; results appear in Logs.')}
          value={mode}
          onChange={v => trackChange('guardrail', 'mode', v)}
          options={GUARDRAIL_MODES}
          horizontal
        />
      </SettingsSection>

      {/* Stage 1. First in the order a turn actually travels. A message held back here is never
          stored and never leaves this deployment - no provider call has been made yet. */}
      <SettingsSection
        title={t('Check what people send')}
        caption={t('Messages, before they are saved or sent to a provider.')}
        toggle={toggle('input-enabled', inputEnabled)}
      >
        <Presence show={inputEnabled}><GuardrailStageAction stage="input" trackChange={trackChange} pending={pending} /></Presence>
      </SettingsSection>

      {/* Stage 2. Pattern checks run as the answer is written, which is why the last few hundred
          characters arrive when it finishes rather than word by word - that pause is what makes
          checking mid-answer possible at all. */}
      <SettingsSection
        title={t('Check what the agent answers')}
        caption={t('Replies, as they are written, so nothing matching is ever shown.')}
        toggle={toggle('output-enabled', outputEnabled)}
      >
        <Presence show={outputEnabled}><GuardrailStageAction stage="output" trackChange={trackChange} pending={pending} /></Presence>
      </SettingsSection>

      {/* Stage 3, and the one nobody pictures when they hear "guardrail". In a product built
          around retrieval, what the agent READS is most of what the model reads: nobody typed it
          and the model has not written anything yet, so the two stages above never see it. A
          credential left in a file, or instructions planted in a page for an assistant to find,
          arrive here or nowhere.

          Pattern checks only, by rule and not by setting - this runs once per TOOL CALL rather
          than once per message, so a model call here would multiply the cost of every agentic
          turn by an amount nobody could predict from this screen. */}
      <SettingsSection
        title={t('Check what the agent reads')}
        caption={t('Retrieved content, before the agent sees it. Pattern checks only.')}
        toggle={toggle('retrieval-enabled', retrievalEnabled)}
      >
        <Presence show={retrievalEnabled}><GuardrailStageAction stage="retrieval" trackChange={trackChange} pending={pending} /></Presence>
      </SettingsSection>

      {/* What the stages look for. One list rather than three, because every one of these is
          wanted everywhere it applies or nowhere, and three copies of the same four switches would
          only invite them to disagree. Which stages each applies to is part of the one line, since
          that is the half an operator cannot guess. */}
      <SettingsSection
        title={t('What to look for')}
        caption={t('Pattern checks. They recognize shapes, not meaning.')}
        gap="list"
      >
        <List>
          <GuardrailCheck
            title={t('Credentials')}
            description="API keys, tokens and private keys. All three stages."
            checked={detectSecrets}
            onChange={v => trackChange('guardrail', 'detect-secrets', String(v))}
          />
          {/* Retrieved content is excluded deliberately and the line says so: a support ticket or a
              customer record is contact details by construction, so checking it here would blank the
              answer to most of the questions people ask this product. */}
          <GuardrailCheck
            title={t('Personal data')}
            description="Emails, phone numbers and card numbers. Messages and answers only."
            checked={detectPii}
            onChange={v => trackChange('guardrail', 'detect-pii', String(v))}
          />
          {/* "Speed bump" stays in the one line. It is the single most important caveat on this tab:
              the check catches a copied attempt and not a reworded one, and an operator who believes
              otherwise has mispriced their whole exposure. */}
          <GuardrailCheck
            title={t('Prompt injection')}
            description="Attempts to talk the agent out of its instructions. A speed bump, not a lock."
            checked={detectInjection}
            onChange={v => trackChange('guardrail', 'detect-prompt-injection', String(v))}
          />
          {/* Not on desktop: every path there is on the user's own machine, so there is no server
              path to leak and the backend does not run this check (GuardrailService.settings). */}
          {hasServerPaths && (
            <GuardrailCheck
              title={t('Server file paths')}
              description="Full paths on this machine, appearing in an answer. Answers only."
              checked={detectPaths}
              onChange={v => trackChange('guardrail', 'detect-path-leak', String(v))}
            />
          )}
        </List>
      </SettingsSection>

      {/* The paid layer, and the description says so before the toggle rather than after it. */}
      <SettingsSection
        title={t('Judge meaning with a model')}
        caption={t('Judges the text against your rules. One model call per message.')}
        toggle={toggle('llm-enabled', llmEnabled)}
      >
        {/* The model layer opens under its switch and closes with it (Presence). */}
        <Presence show={llmEnabled}>
          <div className="space-y-6">
            <ProviderModelRow
              role="guardrail"
              providerLabel="Guardrail provider"
              modelLabel="Guardrail model"
              modelPlaceholder="claude-haiku-4-5"
              modelHint={t('Nothing is checked until a model is chosen. The pattern checks above keep working either way.')}
              trackChange={trackChange}
              pending={pending}
            />

            {/* Documents FIRST, then the typed list.
                This was the other way round, and the typed box being the first - and, until you
                scrolled, the only - thing under this toggle made the whole document feature
                invisible: it reads as "this is where the rules go". The richer source leads.

                The clause count and the relevance floor used to be two boxes at the bottom of this
                block. Both are gone for the reason at the top of this file - the floor especially,
                which decides whether this layer runs at all and whose failure mode is silent: set
                it too high and there is no retrieval, no call, no protection, and nothing anywhere
                saying so. That is a bad thing to put behind a number an operator is guessing at.

                Fail-closed is the last row, INSIDE the classifier block, because it is meaningless
                outside it: nothing else here can be unavailable. Putting it at the top level would
                offer a deployment with no guardrail model a switch that does nothing. Both answers
                are defensible and neither is free. Off, an outage at the model provider quietly
                removes this layer; on, it stops people working. The pattern checks run either way. */}
            <List>
              <div>
                <SwitchRow
                  title={t('Rules from your documents')}
                  caption={t('Upload your policies as files. Only the clauses that bear on a message are sent.')}
                  checked={semanticEnabled}
                  onChange={v => trackChange('guardrail', 'semantic-enabled', String(v))}
                />
                <Presence show={semanticEnabled}>
                  <div className="pb-3">
                    <PolicyDocumentsSection
                      kind="RULES"
                      emptyText="Nothing here yet. Until a document is added, this layer does nothing and costs nothing."
                    />
                  </div>
                </Presence>
              </div>
              <Presence show={semanticEnabled}>
                <SwitchRow
                  title={t('Also check answers')}
                  caption={t('Hold replies to the same policy. Doubles what this adds per turn.')}
                  checked={semanticOnOutput}
                  onChange={v => trackChange('guardrail', 'semantic-on-output', String(v))}
                />
              </Presence>
              <SwitchRow
                title={t('Refuse when the model cannot be reached')}
                caption={t('A timeout or a non-verdict currently lets the message through. This refuses it instead.')}
                checked={failClosed}
                onChange={v => trackChange('guardrail', 'fail-closed', String(v))}
              />
            </List>
          </div>
        </Presence>
      </SettingsSection>

      {/* Two controls that belong to neither the pattern layer nor the model layer: one watches
          the system prompt, the other watches the person. Grouped last because both are about
          detecting a pattern of behavior rather than judging a message. */}
      <SettingsSection title={t('Watch for a pattern')} caption={t('Neither reads a message.')} gap="list">
        <List>
          {/* The canary catches an answer that QUOTES the instructions, which is the attack that
              usually works. It does not catch a paraphrase - nothing here does. Free: no model, no
              network, and it cannot mistake anything else for a leak, because the marker exists
              nowhere else in the world. */}
          <GuardrailCheck
            title={t('Detect a leaked system prompt')}
            description="Refuses any answer repeating a hidden marker. Catches quoting, not paraphrase."
            checked={canaryEnabled}
            onChange={v => trackChange('guardrail', 'canary-enabled', String(v))}
          />

          {/* THREE NUMBER BOXES UNTIL 2026-09-02: refusals, window, cooldown. Everything else on
              this page judges one message on its own, which makes the fortieth attempt look exactly
              like the first, and this is the only thing that counts - but the counting is a rate
              limiter's job and its three constants were not a policy decision anybody was making
              deliberately. One switch now, writing REPEAT_ON_THRESHOLD or 0; the window and the
              cooldown keep their backend defaults.

              The warning stays because it is the failure mode: somebody pasting error logs that each
              contain a token trips every check honestly, several times in a row, and locking them
              out is a worse outcome than the one this prevents. */}
          <GuardrailCheck
            title={t('Slow down repeated attempts')}
            description="Pauses someone refused several times in a row. Forgotten on restart."
            checked={repeatOn}
            onChange={v => trackChange('guardrail', 'repeat-threshold', v ? REPEAT_ON_THRESHOLD : '0')}
            footnote={
              <Presence show={repeatOn}>
                <Caption>
                  {t('Legitimate work can trip this - pasting several logs that each contain a token is enough.')}
                </Caption>
              </Presence>
            }
          />
        </List>
      </SettingsSection>

      {/* KEEPING THE MATCHED TEXT IS ITS OWN BLOCK, above Logs rather than among the checks,
          because it changes nothing about what is DETECTED. Every other control on this screen
          decides what happens to a message; this one decides only whether a decision that has
          already been made can be explained afterwards. Putting it among the detectors would read
          as another thing to switch on for safety, and it is the opposite of that.

          The warning is not a disclaimer. A secret rule fires precisely because the span it
          matched is a credential, so this really does mean holding credentials in memory - and
          the person who needs to weigh that is standing here, about to decide. */}
      <SettingsSection
        title={t('Keep the matched text')}
        caption={t('Also records the text each rule matched, to check its findings.')}
        toggle={toggle('capture-matched-text', captureMatchedText)}
      >
        <Presence show={!!captureMatchedText}>
          <Caption tone="warning">
            {t('A credential a rule finds is now recorded, in memory only, where any administrator can read it.')}
          </Caption>
        </Presence>
      </SettingsSection>

      {/* What the checks have been doing moved to Logs on 2026-10-12 - every check, over any
          period, rather than the last 200 since the app started. The heading stays as the way there. */}
      <SignpostSection
        title={t('Logs')}
        caption={t('Every check, its decision and the rules that fired are recorded in Logs.')}
        action={<edition.AuditLogsLink log="guardrails" onOpenTab={onOpenTab}>{t('Open Logs')}</edition.AuditLogsLink>}
      />
    </SettingsSections>
  )
}

// The policy corpus: what is in it, and adding or removing a document.
//
// Its own index, and that isolation is structural rather than a promise. Chat search reaches
// collections through registered connectors, and this collection belongs to no connector - so
// nothing a user asks can retrieve a policy document, however the question is phrased.

function PolicyDocumentsSection({ kind, emptyText }: { kind: PolicyKind; emptyText: string }) {
  const { t } = useTranslation()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const [error, setError] = useState<string | null>(null)
  // A document is removed only once confirmed - every other danger-quiet in Settings asks first.
  const [removing, setRemoving] = useState<{ id: string; name: string } | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['guardrail-policies'],
    queryFn: guardrailPolicyApi.list,
    // Indexing happens on the server's own thread and the request does not wait for it - a PDF
    // takes as long as it takes. Poll only while something is actually PENDING, so a settled
    // corpus costs nothing.
    refetchInterval: (q) =>
      q.state.data?.documents.some(d => d.status === 'PENDING') ? 2000 : false,
  })

  // One endpoint serves both corpora, so each section filters to its own. Sharing the query key
  // means adding to one list refreshes the other, which is right: they are one manifest.
  const documents = (data?.documents ?? []).filter(d => (d.kind ?? 'RULES') === kind)
  const maxMb = Math.round((data?.maxBytes ?? 0) / (1024 * 1024))

  const invalidate = () => { void qc.invalidateQueries({ queryKey: ['guardrail-policies'] }) }
  // The backend answers a bad request with a sentence written for an operator to read, so it is
  // shown verbatim rather than replaced with a generic failure.
  const onError = (e: unknown) => setError(apiErrorText(e) ?? t('Could not add that document.'))

  const upload = useMutation({
    mutationFn: (v: { file: File; kind: PolicyKind }) => guardrailPolicyApi.upload(v.file, v.kind),
    onSuccess: () => { setError(null); invalidate() },
    onError,
  })
  // Neither said anything when it failed; both do now.
  const remove = useMutation({
    mutationFn: guardrailPolicyApi.remove,
    onSuccess: () => { setRemoving(null); invalidate() },
    onError: (e: unknown) => addToast(withReason(t('Could not remove that document'), e), 'danger'),
  })
  const reindex = useMutation({
    mutationFn: guardrailPolicyApi.reindex,
    onSuccess: invalidate,
    onError: (e: unknown) => addToast(withReason(t('Could not rebuild the index'), e), 'danger'),
  })

  // FILES ONLY as of 2026-09-06. A policy document used to be addable by URL as well, which
  // meant the product would fetch an arbitrary address on the operator's behalf with no
  // allow-list and no private-range block - the SSRF-shaped hole recorded as open in the
  // hand-off. Removing the field closes it at the surface a person can reach; the backend
  // endpoint is untouched and is the thing to remove next.
  const busy = upload.isPending

  return (
    <div className="space-y-3">
      {/* The block's description sits under its name, before the list and the button it explains
          (the field rule of 2026-10-29) - it was two captions after the Add button. The action
          stays beside the name, as a SectionHeading's does. */}
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <FieldLabel as="p" className="mb-0">{t('Policy documents')}</FieldLabel>
          {/* One key, with the emphasised word as a slot a translation can move (Rich). */}
          <Caption className="mt-1 max-w-[60ch]">
            <Rich
              text={t('Similarity only decides which clauses are {relevant}, and the model decides whether the message actually breaks one.')}
              parts={{ relevant: <em>{t('relevant')}</em> }}
            />
          </Caption>
          <Caption className="mt-1 max-w-[60ch]">
            {t('Up to {size} MB each; rebuild the index after changing the embedding model.', { size: maxMb })}
          </Caption>
        </div>
        <Presence show={documents.length > 0} collapse={false}>
          <Button variant="quiet" onClick={() => reindex.mutate()} disabled={reindex.isPending}>
            {reindex.isPending ? t('Rebuilding...') : t('Rebuild index')}
          </Button>
        </Presence>
      </div>

      <FadeSwap swapKey={isLoading ? 'loading' : documents.length > 0}>
      {isLoading ? (
        <ListSkeleton count={2} />
      ) : documents.length === 0 ? (
        <EmptyState className="py-0">{t(emptyText)}</EmptyState>
      ) : (
        // A named row with a caption, so a TEXT action - Remove in the danger ink, last.
        <PagedItems items={documents}>
          {page => (
            <List>
              {page.map(doc => (
                <ListRow
                  key={doc.id}
                  title={doc.displayName}
                  caption={<MetaList items={[
                    doc.status === 'PENDING' && t('Indexing...'),
                    doc.status === 'INDEXED' && t('{count} passage indexed|{count} passages indexed', { count: doc.chunkCount }),
                    doc.status === 'FAILED' && (serverText(doc.failureMessage) || t('Could not be indexed')),
                    doc.sourceUrl && doc.status !== 'FAILED' && doc.sourceUrl,
                  ]} />}
                  action={
                    <Button
                      variant="danger-quiet"
                      onClick={() => setRemoving({ id: doc.id, name: doc.displayName })}
                      aria-label={t('Remove {name}', { name: doc.displayName })}
                    >
                      {t('Remove')}
                    </Button>
                  }
                />
              ))}
            </List>
          )}
        </PagedItems>
      )}
      </FadeSwap>

      {/* The kit's FilePickerButton: the native file input hidden behind a kit Button, emptied
          after each pick so the same file can be chosen again. */}
      <div className="flex gap-2">
        <FilePickerButton
          onFiles={files => upload.mutate({ file: files[0], kind })}
          disabled={busy}
        >
          {upload.isPending ? t('Uploading...') : t('Add a file')}
        </FilePickerButton>
      </div>

      <Presence show={!!error}><InlineNotice tone="danger">{error}</InlineNotice></Presence>

      {removing && (
        <RemoveConfirm
          name={removing.name}
          title={t('Remove policy document')}
          verb={t('remove')}
          confirmLabel={t('Remove')}
          pendingLabel={t('Removing...')}
          consequences={[t('Its rules leave the policy index, and messages are no longer checked against them.')]}
          pending={remove.isPending}
          onConfirm={() => remove.mutate(removing.id)}
          onCancel={() => setRemoving(null)}
        />
      )}
    </div>
  )
}

// One check, one switch row - the kit SwitchRow, so the page reads as one control repeated rather
// than four variations of it.
function GuardrailCheck({ title, description, checked, onChange, footnote }: {
  title: string
  description: string
  checked: boolean
  onChange: (v: boolean) => void
  footnote?: ReactNode
}) {
  // title arrives translated; description arrives as the English literal, which the catalog checker
  // reads from the call site (DYNAMIC jsxProp description) - so it is translated here.
  const { t } = useTranslation()
  return <SwitchRow title={title} caption={t(description)} checked={checked} onChange={onChange} footnote={footnote} />
}

