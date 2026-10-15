import { useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Lock } from 'lucide-react'
import {
  BrandMark, Button, Caption, Checkbox, DialogBody, DialogFooter, DialogHeader, FadeSwap, Field, FormColumns, FormStack,
  InlineNotice, Input, Presence, SecretInput, Select, StaticValue, SwitchRow,
} from '@/components/ui/kit'
import { protocolMarkId } from '@/features/indexing/utils/sourceMarks'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { FEATURES } from '@/config/features'
import { WriteToolConsentModal } from './WriteToolConsentModal'
import { useSecretLabels } from './settingsParts'
import {
  connectionsApi, connectionError, startConnectionSignIn, useConnections,
  type Connection, type ConnectionInput, type ConnectionProtocol,
} from '../services/connectionsApi'
import { useServiceSetupMissing } from '@edition'
import { useAuthStore } from '@/store/authStore'
import { McpAccessFields, McpWhereFields } from './McpServerFields'
import { buildMcpInput, initialMcpForm, mcpFormReady, type McpFormState } from '../utils/mcpForm'

import { useConnectorCatalog } from '@/features/indexing/hooks/useConnectorCatalog'
import { FieldSpecForm } from '@/features/indexing/components/FieldSpecForm'
import type { FieldSpec } from '@/features/indexing/services/catalogApi'
import {
  fieldProblems, fieldValuesChanged, initialFieldValues, submittedFieldValues, type FieldProblem,
} from '@/features/indexing/utils/fieldForm'

// The key the server's address travels under in the form's values - sent as `baseUrl`, beside the
// method's fields rather than among them.
const BASE_URL = 'baseUrl'

/**
 * Add or edit one connection - a server and the credential this user reaches it with, or a sign-in.
 * Modeled on ProviderModal: a create-or-edit form with its own validation and its own save, never a
 * group of fields behind the Settings page's shared bar.
 *
 * <p>DRAWN FROM THE SERVICE'S DESCRIPTOR (the connector catalog): its address field, its sign-in
 * methods - a choice when it offers more than one - and each method's fields, rendered by
 * FieldSpecForm. Nothing here knows a service by name but Microsoft 365, whose deployment-wide app
 * registration is a setup of its own, and an MCP server, whose form is its own (customDialog).
 * A method that signs in (OAUTH2_CODE) saves and then goes straight to the service's sign-in page.
 *
 * <p>`presetProtocol` opens a NEW connection on one service - from a source dialog that has none
 * yet, or from an `app://settings/connectors/jira` link. `onSaved` hands the saved connection back,
 * so a source dialog can select the one just added.
 *
 * <p>THE WRITE SWITCH GOES THROUGH WriteToolConsentModal, as the per-service Write toggles it
 * replaced did: letting the agent create and delete things in somebody's Jira should be a
 * deliberate act. Turning it off is immediate.
 *
 * <p>AN MCP SERVER is a connection with fields of its own (McpServerFields): a transport, an address
 * or a local command, and how to sign in. It has no Write switch - every call to one of its tools
 * asks first (WriteToolRegistry.isMcpTool) - and no default, since nothing is read through it by
 * default. On Enterprise Server a Super admin alone may start a local command or share a server.
 */
export function ConnectionModal({ connection, presetProtocol, onClose, onSaved }: {
  connection: Connection | null
  presetProtocol?: ConnectionProtocol
  onClose: () => void
  onSaved?: (saved: Connection) => void
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { catalog } = useConnectorCatalog()
  const protocols = catalog.protocols
  const { data: existing = [] } = useConnections()
  const editing = connection !== null

  const [name, setName] = useState(connection?.name ?? '')
  // Empty until chosen: the first offered protocol stands in, so the select is never blank while
  // the catalogue is loading and never names one this deployment does not offer.
  const [chosenProtocol, setProtocol] = useState<ConnectionProtocol | ''>(connection?.protocol ?? presetProtocol ?? '')
  const protocol = chosenProtocol || protocols[0]?.protocol || ''
  const [secret, setSecret] = useState('')
  const [clearSecret, setClearSecret] = useState(false)
  const [isDefault, setIsDefault] = useState(connection?.isDefault ?? false)
  const [allowRead, setAllowRead] = useState(connection?.allowRead ?? true)
  const [allowWrite, setAllowWrite] = useState(connection?.allowWrite ?? false)
  const [consentOpen, setConsentOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [problems, setProblems] = useState<Record<string, FieldProblem>>({})
  const [mcp, setMcp] = useState<McpFormState>(() => initialMcpForm(connection?.mcp, connection?.baseUrl))
  const updateMcp = (patch: Partial<McpFormState>) => setMcp(m => ({ ...m, ...patch }))
  // On Enterprise Server a local command runs as the server process, and a shared server is in every
  // user's agent: both are a Super admin's. Elsewhere there is one user, and both are theirs.
  const isSuperAdmin = useAuthStore(s => s.user)?.role === 'SUPER_ADMIN'
  const mayRunLocal = !FEATURES.auth || isSuperAdmin
  const mayShare = FEATURES.auth && isSuperAdmin

  const info = protocols.find(p => p.protocol === protocol)
  const serviceLabel = info ? t(info.displayName) : (connection?.protocolLabel ?? '')
  const supportsWrite = info?.supportsWrite ?? connection?.supportsWrite ?? false
  const isMcp = protocol === 'MCP_SERVER'

  // The sign-in method: the one saved, else the service's first. A choice only where there are two.
  const methods = info?.authMethods ?? []
  const [chosenMethod, setMethod] = useState<string>(connection?.authMethod ?? '')
  const method = methods.find(m => m.kind === chosenMethod) ?? methods[0]
  const signsIn = method?.kind === 'OAUTH2_CODE'
  // The form's fields: where the service is (when it has an address), then the method's own.
  const fields: FieldSpec[] = useMemo(() => [
    ...(info?.baseUrl ? [{ ...info.baseUrl, key: BASE_URL }] : []),
    ...(method?.fields ?? []),
  ], [info, method])
  // What the form opens with - the connection's saved values on an edit (its address, user name and
  // settings; never a secret), else each field's default. Typed values sit over it, and go when the
  // service or the method changes.
  const initial = useMemo(() => initialFieldValues(fields, connection ? {
    ...(connection.settings ?? {}),
    [BASE_URL]: connection.baseUrl,
    username: connection.username,
  } : undefined), [fields, connection])
  const [edits, setEdits] = useState<Record<string, string>>({})
  const values = { ...initial, ...edits }
  const secretFields = (method?.fields ?? []).filter(f => f.type === 'SECRET')
  const onField = (key: string, value: string) => {
    setEdits(e => ({ ...e, [key]: value }))
    setProblems(p => {
      if (!(key in p)) return p
      const next = { ...p }
      delete next[key]
      return next
    })
    if (secretFields.some(f => f.key === key)) setClearSecret(false)
  }
  const pickProtocol = (p: string) => { setProtocol(p); setMethod(''); setEdits({}); setProblems({}) }
  const pickMethod = (m: string) => { setMethod(m); setEdits({}); setProblems({}) }

  // The secrets already held, as the masked box shows them: one secret is masked by the server;
  // of several, each is only known to be set.
  const storedSecrets: Record<string, string> = {}
  if (connection && !clearSecret) {
    for (const f of secretFields) {
      if (secretFields.length === 1 && connection.secretConfigured) storedSecrets[f.key] = connection.secretMasked || '********'
      else if (connection.secretsConfigured?.includes(f.key)) storedSecrets[f.key] = '********'
    }
  }

  // A service the license leaves out - Microsoft 365 on an Enterprise Server with no license in force.
  const locked = !!info && !info.licensed
  // The deployment not set up for the service (Enterprise Server's Microsoft 365 app registration):
  // the sign-in cannot start, so it is not offered. Null when nothing is missing.
  const setupMissing = useServiceSetupMissing(protocol)
  // CHOSEN values, not the effective ones: the stand-in arriving with the catalogue is not an edit,
  // and counting it would ask "Discard unsaved changes?" of a form nobody touched.
  const { dirty: otherDirty } = useFormDirty({ name, chosenProtocol, chosenMethod, secret, clearSecret, isDefault, allowRead, allowWrite, mcp })
  const dirty = otherDirty || fieldValuesChanged(fields, initial, values)

  // The backend makes the first connection of a service its default whatever this box says, and
  // moves the default only when another is set to it - so where the answer is already decided the
  // box shows it and cannot be changed.
  const firstOfService = !editing && !existing.some(c => c.protocol === protocol)
  const defaultFixed = firstOfService || !!connection?.isDefault

  const save = useMutation({
    mutationFn: () => {
      const { [BASE_URL]: address, ...methodFields } = submittedFieldValues(fields, values)
      const input: ConnectionInput = isMcp ? {
        name: name.trim(),
        ...(mcp.transport !== 'STDIO' ? { baseUrl: mcp.url.trim() } : {}),
        ...(mcp.transport !== 'STDIO' && mcp.auth === 'BEARER'
          ? (secret.trim() ? { secret: secret.trim() } : clearSecret ? { clearSecret: true } : {})
          : {}),
        allowRead,
        mcp: buildMcpInput(mcp, mayShare),
      } : {
        name: name.trim(),
        // An address left empty on a new connection takes the service's own (a vendor's public API).
        ...(info?.baseUrl && (address || editing) ? { baseUrl: address ?? '' } : {}),
        ...(method ? { authMethod: method.kind } : {}),
        fields: methodFields,
        ...(clearSecret ? { clearSecret: true } : {}),
        // Only ever TRUE: that is what moves the default here. Unsetting it is not something a
        // connection can do on its own - another one takes it over.
        ...(isDefault || firstOfService ? { isDefault: true } : {}),
        allowRead,
        allowWrite: supportsWrite && allowWrite,
      }
      return editing
        ? connectionsApi.update(connection!.id, input)
        : connectionsApi.create({ ...input, protocol })
    },
    onSuccess: saved => {
      queryClient.invalidateQueries({ queryKey: keys.connections() })
      // A source shows the name of the connection it reads through.
      queryClient.invalidateQueries({ queryKey: keys.connectorsByType() })
      queryClient.invalidateQueries({ queryKey: keys.connectorSummary() })
      // A new connection that signs in goes straight to the service's sign-in page - the one step
      // left before it can be used. The page comes back with ?connection-oauth=<outcome> (or, for
      // Microsoft 365, ?microsoft=<outcome>), and a notice says how it went.
      if (!editing && signsIn && !setupMissing) {
        startConnectionSignIn(saved)
        return
      }
      onSaved?.(saved)
      onClose()
    },
    // The backend writes these refusals to be read verbatim - "There is already a connection
    // called Work", "Jira needs a server URL" - so they are shown as they are.
    onError: (e: unknown) => setError(connectionError(e) ?? t('Could not save this connection.')),
  })

  const submit = () => {
    setError(null)
    if (!isMcp) {
      const found = fieldProblems(fields, values, Object.keys(storedSecrets))
      // An address the service has a default for may be left empty.
      if (info?.baseUrl?.defaultValue && found[BASE_URL]?.code === 'required') delete found[BASE_URL]
      // On an edit an untouched secret keeps what is stored, whatever the masked box shows.
      if (editing) for (const f of secretFields) if (found[f.key]?.code === 'required') delete found[f.key]
      setProblems(found)
      if (Object.keys(found).length > 0) return
    }
    save.mutate()
  }

  // The stored credential, as the server masks it - the kit SecretInput shows it masked until it
  // is revealed or typed over. Nothing once its removal is asked for: the box is then empty.
  const storedSecret = connection?.secretConfigured && !clearSecret ? (connection.secretMasked ?? '') : undefined
  const secretLabels = useSecretLabels()
  const onSecret = (v: string) => { setSecret(v); setClearSecret(false) }
  const tokenField = (
    <Field label={t('Access token')} hint={t('Sent to the server as a bearer token with every request.')}>
      {id => <SecretInput id={id} value={secret} serverValue={storedSecret} onChange={onSecret} labels={secretLabels} />}
    </Field>
  )
  // Whether there is a stored credential this form could remove: an MCP server holds one only for a
  // bearer; a sign-in's grant is removed by signing out, on the connection's row.
  const removableSecret = isMcp
    ? !!connection?.secretConfigured && mcp.transport !== 'STDIO' && mcp.auth === 'BEARER'
    : !signsIn && secretFields.length > 0 && !!connection
      && (connection.secretConfigured || (connection.secretsConfigured?.length ?? 0) > 0)
      && !secretFields.some(f => (values[f.key] ?? '') !== '')

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      initialFocus={editing ? 'panel' : 'first-field'}
      size="form"
      // Named like its title - it opened with no accessible name.
      ariaLabel={editing ? t('Edit connection') : t('Add connection')}
    >
      {requestClose => (<>
        <DialogHeader title={editing ? t('Edit connection') : t('Add connection')} />
        <DialogBody>
          {/* Two columns from `lg` - where the server is and how to reach it on the left, what the
              agent may do with it on the right - so the form fits a normal window without
              scrolling. */}
          <FormColumns from="lg" density="columns" className="items-start">
            <FormStack className="min-w-0">
              <FormColumns className="items-start">
                <Field label={t('Name')}>
                  {id => (
                    <Input
                      id={id}
                      value={name}
                      onChange={e => setName(e.target.value)}
                      placeholder={serviceLabel}
                    />
                  )}
                </Field>
                <Field label={t('Service')}>
                  {/* Fixed once saved: a Jira connection's sources were registered against Jira's
                      API, and no other service could read them. */}
                  {/* Each service with its mark, in the text's own ink - the same mark the connection's
                      row and its sources' cards carry. Read-only, it is the kit's StaticValue - the
                      value at the field's height - rather than a control. */}
                  {id => editing ? (
                    <StaticValue icon={<BrandMark id={protocolMarkId(protocol)} size={14} />}>{serviceLabel}</StaticValue>
                  ) : (
                    <Select
                      id={id}
                      value={protocol}
                      onChange={pickProtocol}
                      options={protocols.map(p => ({
                        value: p.protocol, label: t(p.displayName), icon: <BrandMark id={protocolMarkId(p.protocol)} size={14} />,
                      }))}
                    />
                  )}
                </Field>
              </FormColumns>

              {/* What the chosen service asks for eases in and out as the service changes (Presence). */}
              <Presence show={isMcp}>
                <McpWhereFields state={mcp} update={updateMcp} localAllowed={mayRunLocal} />
              </Presence>

              {/* How it signs in - a choice only where the service offers more than one way. */}
              <Presence show={!isMcp && methods.length > 1}>
                <Field label={t('Sign in with')}>
                  {id => (
                    <Select
                      id={id}
                      value={method?.kind ?? ''}
                      onChange={pickMethod}
                      disabled={editing && !!connection?.authMethod && signsIn}
                      options={methods.map(m => ({ value: m.kind, label: t(m.label) }))}
                    />
                  )}
                </Field>
              </Presence>

              {/* Where it is and what it signs in with, as the service's descriptor states them. Keyed
                  by service and method, so a change fades the new form in rather than morphing. */}
              <Presence show={!isMcp && fields.length > 0}>
                <FadeSwap swapKey={`${protocol}:${method?.kind ?? ''}`}>
                  <FieldSpecForm
                    fields={fields}
                    values={values}
                    onChange={onField}
                    problems={problems}
                    disabled={save.isPending}
                    storedSecrets={storedSecrets}
                  />
                </FadeSwap>
              </Presence>

              {/* Clearing is its own act, never an empty box: the stored secret is not sent to this
                  page, so an empty box is what every untouched form looks like. A removal, so the
                  danger text action - and the hyperlink one to take it back. */}
              <Presence show={removableSecret && !isMcp}>
                <div>
                  <Button variant={clearSecret ? 'hyperlink' : 'danger-quiet'} onClick={() => setClearSecret(c => !c)}>
                    {clearSecret ? t('Keep the stored credential') : t('Remove the stored credential')}
                  </Button>
                </div>
              </Presence>
              <Presence show={!!clearSecret && !isMcp}>
                <Caption tone="warning">{t('The stored credential is removed when you save.')}</Caption>
              </Presence>

              {/* A sign-in has no secret to type: the connection is reached as whoever signs in. */}
              <Presence show={signsIn && !isMcp}>
                <FadeSwap swapKey={setupMissing ? 'setup' : editing ? 'edit' : 'new'}>
                {setupMissing ? (
                  <Caption tone="warning">{setupMissing}</Caption>
                ) : !editing ? (
                  <Caption>
                    {t('Saving takes you to {service} to sign in. The sign-in is never shared with other users.', { service: serviceLabel })}
                  </Caption>
                ) : (
                  <Caption>
                    {connection?.accountLabel
                      ? t('Signed in as {account}. Sign in again from the connection list to use a different account.', { account: connection.accountLabel })
                      : t('Not signed in yet. Use Sign in on the connection list.')}
                  </Caption>
                )}
                </FadeSwap>
              </Presence>
            </FormStack>

            {/* The right column: what the agent may do - an MCP server's or a service's - faded
                across when the service changes. */}
            <FadeSwap swapKey={isMcp} className="min-w-0">
            {isMcp ? (
            <div className="min-w-0 space-y-4">
              <McpAccessFields
                state={mcp}
                update={updateMcp}
                tokenField={tokenField}
                editing={editing}
                signedIn={!!connection?.mcp?.signedIn}
              />
              <Presence show={removableSecret && !secret}>
                <div>
                  <Button variant={clearSecret ? 'hyperlink' : 'danger-quiet'} onClick={() => setClearSecret(c => !c)}>
                    {clearSecret ? t('Keep the stored credential') : t('Remove the stored credential')}
                  </Button>
                </div>
              </Presence>
              <Presence show={!!clearSecret}>
                <Caption tone="warning">{t('The stored credential is removed when you save.')}</Caption>
              </Presence>
              <div>
                <SwitchRow
                  title={t('Allow agent to use')}
                  caption={t("Offer this server's tools to the agent. Each call waits for your approval unless the conversation is in Auto.")}
                  checked={allowRead}
                  onChange={setAllowRead}
                />
                {mayShare && (
                  <SwitchRow
                    title={t('Share with everyone')}
                    caption={t('Every user on this server can use its tools through this connection.')}
                    checked={mcp.shared}
                    onChange={v => updateMcp({ shared: v })}
                  />
                )}
              </div>
            </div>
            ) : (
            <div className="min-w-0">
              <Checkbox
                checked={isDefault || firstOfService}
                onChange={setIsDefault}
                disabled={defaultFixed}
                hint={firstOfService
                  ? t('Your first {service} connection is the default.', { service: serviceLabel })
                  : connection?.isDefault
                    ? t('To change it, make another connection the default.')
                    : t('A source that names no connection, and the agent, use the default.')}
              >
                {t('Use as default for {service}', { service: serviceLabel })}
              </Checkbox>

              <SwitchRow
                className="mt-2"
                title={t('Allow agent to read')}
                caption={t('Let the agent search and read {service} through this connection, from chat and from an attached MCP client.', { service: serviceLabel })}
                checked={allowRead}
                onChange={setAllowRead}
              />

              <Presence show={supportsWrite}>
                <SwitchRow
                  title={t('Allow agent to write')}
                  caption={t('Let the agent create, update and delete {service} content through this connection, on your behalf.', { service: serviceLabel })}
                  checked={allowWrite}
                  onChange={v => (v ? setConsentOpen(true) : setAllowWrite(false))}
                />
              </Presence>

              {/* Said here rather than refused on Save. */}
              <Presence show={locked}>
                <Caption icon={Lock} className="mt-3">
                  {t('{service} needs an Enterprise license.', { service: serviceLabel })}
                </Caption>
              </Presence>
            </div>
            )}
            </FadeSwap>
          </FormColumns>
        </DialogBody>

        <DialogFooter start={error && <InlineNotice tone="danger" icon={AlertTriangle}>{error}</InlineNotice>}>
          <Button variant="secondary" onClick={requestClose}>{t('Cancel')}</Button>
          <Button
            onClick={submit}
            disabled={!name.trim() || !protocol || locked || save.isPending || (isMcp && !mcpFormReady(mcp))}
          >
            {save.isPending
              ? t('Saving...')
              : editing
                ? t('Save')
                : signsIn && !setupMissing
                  ? t('Sign in with {service}', { service: serviceLabel })
                  : t('Add connection')}
          </Button>
        </DialogFooter>

        {consentOpen && (
          <WriteToolConsentModal
            connectorLabel={name.trim() || serviceLabel}
            onAccept={() => { setAllowWrite(true); setConsentOpen(false) }}
            onCancel={() => setConsentOpen(false)}
          />
        )}
      </>)}
    </ModalShell>
  )
}
