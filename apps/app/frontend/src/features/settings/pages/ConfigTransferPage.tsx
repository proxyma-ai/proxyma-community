import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Download, RotateCw, Upload } from 'lucide-react'
import {
  Button, Caption, Card, CardBody, CardHeader, Checkbox, cn, FadeSwap, Field, FieldLabel, FilePickerButton,
  FormStack, HScroll, InlineCode, InlineNotice, PasswordInput, Presence, SettingsSection, SettingsSections,
  TABLE_CLASS, TD_COMPACT, Th, THEAD_TR, Tooltip, TR,
} from '@/components/ui/kit'
import { apiErrorText } from '@/services/apiError'
import { Rich } from '@/i18n/kit'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { FEATURES } from '@/config/features'
import {
  configTransferApi, MIN_PASSPHRASE_CHARS,
  type ConfigExportFile, type ImportEntry, type ImportOutcome, type ImportReport, type TransferGroup,
} from '../services/configTransferApi'
import { RestartBanner, useRestartNow } from '../components/RestartBanner'
import { useTranslation } from '@/i18n'

// Move a whole configuration off this deployment, or put one on.
//
// NOT a backup. The server's Administration page has one of those - it dumps the database and the
// search index for disaster recovery, and its restore is an operator runbook. This is portability:
// the settings, the provider instances, the skills, the workflows and the system prompt, as one
// readable JSON file. Nothing here touches conversations, documents, users or the index.
//
// Shared by both editions, unlike most of this directory: what a configuration IS does not differ
// between desktop and server. Who may do it does - the server gates the endpoint at SUPER_ADMIN -
// but that is enforced on the backend, and this page never consults a role.
//
// WHAT TRAVELS IS CHOSEN, since 2026-09-27 (the maintainer's request): export offers every group,
// import offers the groups the file holds, both named after the Settings tabs. Everything travels
// except API tokens, the account and the connectors' Read/Write switches (a consent record - see
// SettingsPrefixRegistry.EXCLUDED_PREFIXES). An Enterprise Server's license is not a setting at all
// and never travels either.

// Every group, in the Settings rail's order, with the tab name it is known by. Network is the one
// that needs a restart once written - its proxy is read once, at startup. The option does not say
// so (it carried an icon until 2026-10-03; the maintainer asked for it gone): the preview names
// each such setting and the banner after an import offers the restart.
const TRANSFER_GROUPS: { id: TransferGroup; label: string }[] = [
  { id: 'connectors',     label: 'Connections' },
  { id: 'skills',         label: 'Skills' },
  { id: 'workflows',      label: 'Workflows' },
  { id: 'providers',      label: 'AI Providers' },
  { id: 'agents',         label: 'Agents' },
  { id: 'retrieval',      label: 'Search' },
  { id: 'documents',      label: 'Conversion' },
  { id: 'guardrails',     label: 'Guardrails' },
  { id: 'usage',          label: 'Usage & Cost' },
  { id: 'network',        label: 'Network' },
  { id: 'retention',      label: 'Data Retention' },
  { id: 'updates',        label: 'Updates' },
  { id: 'privacy',        label: 'Privacy' },
  { id: 'administration', label: 'Administration' },
  { id: 'other',          label: 'Other' },
]

// What this edition can export. Administration is the server's page; workflows are behind their
// feature flag. Import is not filtered this way - it offers whatever the file holds.
const EXPORTABLE_GROUPS: TransferGroup[] = TRANSFER_GROUPS
  .map(g => g.id)
  .filter(id => (id !== 'administration' || FEATURES.auth) && (id !== 'workflows' || FEATURES.workflows))

// "Created" is work that worked - the brand ink, not a health verdict's green.
function outcomeStyle(outcome: ImportOutcome): string {
  switch (outcome) {
    case 'CREATED':   return 'text-brand-legible'
    case 'UPDATED':   return 'text-warning'
    case 'SKIPPED':   return 'text-ink-disabled'
    case 'UNCHANGED': return 'text-ink-caption'
  }
}

// The outcome as a reader says it - it was the wire value lower-cased, in English in every
// language. Literal calls, so the catalog checker sees each one.
function useOutcomeLabel() {
  const { t } = useTranslation()
  return (outcome: ImportOutcome): string => {
    switch (outcome) {
      case 'CREATED':   return t('Created')
      case 'UPDATED':   return t('Updated')
      case 'SKIPPED':   return t('Skipped')
      case 'UNCHANGED': return t('Unchanged')
    }
  }
}

export function ConfigTransferPage() {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const qc = useQueryClient()

  const [includeSecrets, setIncludeSecrets] = useState(true)
  const [exportPassphrase, setExportPassphrase] = useState('')
  const [importPassphrase, setImportPassphrase] = useState('')
  const [pendingFile, setPendingFile] = useState<ConfigExportFile | null>(null)
  const [fileName, setFileName] = useState<string | null>(null)
  const [preview, setPreview] = useState<ImportReport | null>(null)
  const [applied, setApplied] = useState<ImportReport | null>(null)
  const [exportGroups, setExportGroups] = useState<TransferGroup[]>(EXPORTABLE_GROUPS)
  // Null until the file's first preview says which groups it holds; then every one of them.
  const [importGroups, setImportGroups] = useState<TransferGroup[] | null>(null)
  const [restartNeeded, setRestartNeeded] = useState(false)
  const [restartFailed, setRestartFailed] = useState(false)
  const restartNow = useRestartNow()

  const passphraseTooShort = includeSecrets && exportPassphrase.length < MIN_PASSPHRASE_CHARS

  const exportMutation = useMutation({
    mutationFn: () => configTransferApi.exportConfig(includeSecrets ? exportPassphrase : null, exportGroups),
    onSuccess: (file) => {
      // The one place in this SPA that hands the browser a file. A blob URL rather than a data:
      // URI so a large configuration is not pushed through a string, and revoked immediately -
      // the anchor has already been clicked by then, and leaving it alive keeps the whole file,
      // credentials included, reachable from the page for as long as the tab lives.
      const blob = new Blob([JSON.stringify(file, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `proxyma-config-${new Date().toISOString().slice(0, 10)}.json`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      setExportPassphrase('')
      addToast(includeSecrets
        ? t('Configuration exported. Keep the passphrase - nothing else can open the file.')
        : t('Configuration exported without credentials.'), 'success')
    },
    onError: (e: unknown) => addToast(errorText(e, t('Could not export the configuration.')), 'danger'),
  })

  const previewMutation = useMutation({
    mutationFn: ({ file, groups }: { file: ConfigExportFile; groups: TransferGroup[] | null }) =>
      configTransferApi.previewImport(file, importPassphrase || null, groups),
    onSuccess: (report, { groups }) => {
      setPreview(report)
      if (groups === null) setImportGroups(report.groups)
    },
    onError: (e: unknown) => {
      setPreview(null)
      addToast(errorText(e, t('Could not read that file.')), 'danger')
    },
  })

  const applyMutation = useMutation({
    mutationFn: () => configTransferApi.applyImport(pendingFile!, importPassphrase || null, importGroups),
    onSuccess: (report) => {
      setApplied(report)
      setPreview(null)
      // The same banner a Settings save shows: a proxy that changed is not used until a restart,
      // and a line in the report is too easy to read past.
      if (report.requiresRestart) setRestartNeeded(true)
      // Everything this import could have touched. Settings and providers are read through
      // Tanstack Query all over the app, so without this the pages the user visits next would
      // show what was there before the import for as long as their cache stays fresh.
      qc.invalidateQueries({ queryKey: keys.providerInstances() })
      qc.invalidateQueries({ queryKey: keys.chatSkills() })
      qc.invalidateQueries({ queryKey: keys.chatWorkflows() })
      qc.invalidateQueries({ queryKey: ['settings'] })
      addToast(report.requiresRestart
        ? t('Configuration imported. Some of it needs a restart to take effect.')
        : t('Configuration imported.'), 'success')
    },
    onError: (e: unknown) => addToast(errorText(e, t('Could not import the configuration.')), 'danger'),
  })

  // The kit's FilePickerButton empties its input after each pick, so choosing the SAME file again
  // still arrives here - otherwise a second attempt after a failed passphrase looked dead.
  function onFileChosen(chosen: File | undefined) {
    if (!chosen) return
    setApplied(null)
    setPreview(null)
    setImportGroups(null)
    chosen.text()
      .then(text => {
        const parsed = JSON.parse(text) as ConfigExportFile
        if (!parsed?.proxyma?.format) {
          throw new Error(t('That file is not a Proxyma configuration export.'))
        }
        setPendingFile(parsed)
        setFileName(chosen.name)
        // Only previewed once a passphrase is in hand for a file that needs one - previewing
        // without it would report every credential as "skipped" and read like a broken file.
        if (!parsed.proxyma.hasSecrets || importPassphrase) previewMutation.mutate({ file: parsed, groups: null })
      })
      .catch(err => {
        setPendingFile(null)
        setFileName(null)
        addToast(err instanceof Error ? err.message : t('That file could not be read.'), 'danger')
      })
  }

  const chooseImportGroups = (groups: TransferGroup[]) => {
    setImportGroups(groups)
    if (pendingFile) previewMutation.mutate({ file: pendingFile, groups })
  }

  const onRestartNow = async () => {
    setRestartNeeded(false)
    setRestartFailed(false)
    if (!(await restartNow())) setRestartFailed(true)
  }

  return (
    <SettingsSections>
      {/* Over the two sections while a restart is owed: in and out, never a jump. */}
      <Presence show={restartNeeded || restartFailed}>
        <div className="space-y-3">
          <Presence show={restartNeeded}>
            <RestartBanner
              message={t('Configuration imported. A restart is required for some of it to take effect.')}
              onRestartNow={onRestartNow}
              onRestartLater={() => setRestartNeeded(false)}
            />
          </Presence>
          <Presence show={restartFailed}>
            <InlineNotice tone="danger">{t('Restart failed. Please restart manually.')}</InlineNotice>
          </Presence>
        </div>
      </Presence>

      <SettingsSection
        gap="list"
        title={t('Export')}
        caption={t('Saves the chosen settings to a file, without API tokens. Not a backup.')}
      >
        <FormStack>
          <GroupPicker available={EXPORTABLE_GROUPS} value={exportGroups} onChange={setExportGroups} />
          <Checkbox checked={includeSecrets} onChange={setIncludeSecrets}>
            {t('Include credentials, encrypted with a passphrase')}
          </Checkbox>
          {/* The passphrase, or what happens without one - faded across as the box is ticked. */}
          <FadeSwap swapKey={includeSecrets} className="space-y-4">
          {includeSecrets ? (
            <>
              {/* The warning is the field's hint, under its name and before the box (the field rule
                  of 2026-10-29). Stated plainly rather than as a footnote. Once the file leaves this
                  machine the passphrase is the only thing standing between a Downloads folder and
                  every API key this deployment holds, and there is no way to revoke a file. */}
              <Field
                label={t('Passphrase')}
                hint={(
                  <Caption tone="warning" icon={AlertTriangle}>
                    {t('The passphrase cannot be recovered. Without it, nobody can read the credentials in this file.')}
                  </Caption>
                )}
              >
                {(id, aria) => (
                  <PasswordInput labels={{ show: t('Show password'), hide: t('Hide password') }}
                    id={id}
                    {...aria}
                    value={exportPassphrase}
                    onChange={e => setExportPassphrase(e.target.value)}
                    placeholder={t('At least {count} characters', { count: MIN_PASSPHRASE_CHARS })}
                    autoComplete="new-password"
                  />
                )}
              </Field>
            </>
          ) : (
            <Caption>
              <Rich
                text={t('Credentials are written as {placeholder}. Enter them by hand after importing.')}
                parts={{ placeholder: <InlineCode>***omitted***</InlineCode> }}
              />
            </Caption>
          )}
          </FadeSwap>
          <div>
            <Button
              onClick={() => exportMutation.mutate()}
              disabled={passphraseTooShort || exportGroups.length === 0 || exportMutation.isPending}
            >
              <Download size={14} aria-hidden="true" />
              {exportMutation.isPending ? t('Exporting...') : t('Export configuration')}
            </Button>
          </div>
        </FormStack>
      </SettingsSection>

      <SettingsSection
        gap="list"
        title={t('Import')}
        caption={t("Adds the file's settings without deleting any; you review them first.")}
      >
        <FormStack>
          <Field label={t('Passphrase')}>
            {id => (
              <PasswordInput labels={{ show: t('Show password'), hide: t('Hide password') }}
                id={id}
                value={importPassphrase}
                onChange={e => setImportPassphrase(e.target.value)}
                placeholder={t('Passphrase, if the file has credentials')}
                autoComplete="off"
              />
            )}
          </Field>
          {/* The kit's FilePickerButton - the native input hidden behind it, emptied after a pick. */}
          <div className="flex items-center gap-3 min-w-0">
            <FilePickerButton accept="application/json,.json" onFiles={files => onFileChosen(files[0])}>
              <Upload size={14} aria-hidden="true" />
              {t('Choose file')}
            </FilePickerButton>
            <Presence show={!!fileName} collapse={false} className="min-w-0">
              <Caption as="span" className="block truncate">{fileName}</Caption>
            </Presence>
          </div>

          {/* Each step of an import eases in under the last as it becomes possible. */}
          <Presence show={!!pendingFile && !preview && !applied && !!pendingFile?.proxyma.hasSecrets}>
            <div>
              <Button
                variant="secondary"
                onClick={() => pendingFile && previewMutation.mutate({ file: pendingFile, groups: importGroups })}
                disabled={previewMutation.isPending}
              >
                {previewMutation.isPending ? t('Reading...') : t('Read this file')}
              </Button>
            </div>
          </Presence>

          <Presence show={!!pendingFile && !!importGroups && !!preview && preview.groups.length > 0}>
            {preview && importGroups && (
              <GroupPicker available={preview.groups} value={importGroups} onChange={chooseImportGroups} />
            )}
          </Presence>

          <Presence show={!!preview}>
            {preview && (
              <ReportBlock
                report={preview}
                title={t('This is what importing would change')}
                footer={
                  <Button
                    onClick={() => applyMutation.mutate()}
                    disabled={applyMutation.isPending || previewMutation.isPending || importGroups?.length === 0}
                  >
                    {applyMutation.isPending ? t('Importing...') : t('Import it')}
                  </Button>
                }
              />
            )}
          </Presence>
          <Presence show={!!applied}>
            {applied && <ReportBlock report={applied} title={t('Imported')} />}
          </Presence>
        </FormStack>
      </SettingsSection>
    </SettingsSections>
  )
}

function ReportBlock({ report, title, footer }: {
  report: ImportReport
  title: string
  footer?: React.ReactNode
}) {
  const { t } = useTranslation()
  return (
    <Card padding="none" className="overflow-hidden">
      <CardHeader
        divided
        title={title}
        readout={t('{created} new, {updated} changed, {skipped} skipped', {
          created: report.created, updated: report.updated, skipped: report.skipped,
        })}
      />

      <Presence show={!report.secretsApplied}>
        <Caption tone="warning" icon={AlertTriangle} className="px-4 sm:px-6 py-2 border-b border-line/40">
          {t('No credentials were read from this file. Anything needing one keeps the value already configured here.')}
        </Caption>
      </Presence>

      {/* A list of unknown length, so it scrolls inside the card: the kit's table at its COMPACT
          density, a row per entry. The outer cells take the card's own gutter, so the columns line
          up with the header above them. */}
      <div className="max-h-72 overflow-y-auto">
        {report.entries.length === 0 ? (
          <CardBody className="py-2">
            <Caption>{t('Nothing in this file differs from what is already configured here.')}</Caption>
          </CardBody>
        ) : (
          <HScroll wheel={false}>
            <table className={TABLE_CLASS}>
              <thead>
                <tr className={THEAD_TR}>
                  <Th compact className="pl-4 sm:pl-6">{t('Group')}</Th>
                  <Th compact>{t('Name')}</Th>
                  <Th compact>{t('Status')}</Th>
                  <Th compact className="pr-4 sm:pr-6">{t('Detail')}</Th>
                </tr>
              </thead>
              <tbody>
                {report.entries.map((entry, i) => <ReportRow key={i} entry={entry} />)}
              </tbody>
            </table>
          </HScroll>
        )}
      </div>

      <Presence show={!!report.requiresRestart}>
        <Caption tone="warning" icon={RotateCw} className="px-4 sm:px-6 py-2 border-t border-line/40">
          {t('Some of these only take effect after a restart.')}
        </Caption>
      </Presence>
      {footer && <div className="px-4 sm:px-6 py-3 border-t border-line/40">{footer}</div>}
    </Card>
  )
}

function ReportRow({ entry }: { entry: ImportEntry }) {
  const { t } = useTranslation()
  const outcomeLabel = useOutcomeLabel()
  const group = TRANSFER_GROUPS.find(g => g.id === entry.group)
  // The item is the row's name on a phone, where the table stacks: every other cell is labelled.
  return (
    <tr className={TR}>
      <td data-label={t('Group')} className={cn(TD_COMPACT, 'pl-4 sm:pl-6 text-ink-disabled whitespace-nowrap')}>
        {group ? t(group.label) : entry.section}
      </td>
      <td className={cn(TD_COMPACT, 'text-ink-primary break-words')}>{entry.item}</td>
      <td data-label={t('Status')} className={cn(TD_COMPACT, 'whitespace-nowrap')}>
        <span className="inline-flex items-center gap-2">
          {entry.requiresRestart && (entry.outcome === 'CREATED' || entry.outcome === 'UPDATED') && (
            <Tooltip label={t('Takes effect after a restart')}>
              <span role="img" aria-label={t('Takes effect after a restart')} className="inline-flex shrink-0 text-warning">
                <RotateCw size={12} aria-hidden="true" />
              </span>
            </Tooltip>
          )}
          <span className={outcomeStyle(entry.outcome)}>{outcomeLabel(entry.outcome)}</span>
        </span>
      </td>
      <td data-label={t('Detail')} className={cn(TD_COMPACT, 'pr-4 sm:pr-6 text-ink-caption')}>
        {entry.detail && (
          <Tooltip label={entry.detail}>
            <span className="block max-w-64 truncate">{entry.detail}</span>
          </Tooltip>
        )}
      </td>
    </tr>
  )
}

// A checkbox per group, in two or three columns, with All and None.
function GroupPicker({ available, value, onChange }: {
  available: TransferGroup[]
  value: TransferGroup[]
  onChange: (groups: TransferGroup[]) => void
}) {
  const { t } = useTranslation()
  const groups = TRANSFER_GROUPS.filter(g => available.includes(g.id))
  const toggle = (id: TransferGroup, on: boolean) =>
    onChange(on ? available.filter(g => g === id || value.includes(g)) : value.filter(g => g !== id))
  return (
    <Card>
      <div className="flex items-center justify-between gap-3 mb-3">
        <FieldLabel as="span" className="mb-0">{t('What to include')}</FieldLabel>
        <div className="flex items-center gap-3">
          <Button variant="hyperlink" onClick={() => onChange(available)}>{t('All')}</Button>
          <Button variant="hyperlink" onClick={() => onChange([])}>{t('None')}</Button>
        </div>
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-x-4 gap-y-2">
        {groups.map(g => (
          <Checkbox key={g.id} checked={value.includes(g.id)} onChange={on => toggle(g.id, on)}>
            {t(g.label)}
          </Checkbox>
        ))}
      </div>
    </Card>
  )
}

// The server's reason in any of the shapes it answers in (apiErrorText), else the fallback.
function errorText(e: unknown, fallback: string): string {
  return apiErrorText(e) ?? fallback
}
