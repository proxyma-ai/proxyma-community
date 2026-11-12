import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, LogIn, Lock } from 'lucide-react'
import {
  BrandMark, Button, Callout, Caption, Checkbox, CopyField, DialogBody, DialogFooter, DialogHeader, ExternalLink, FadeSwap, Field,
  FieldSkeleton, FormColumns, FormStack, InlineNotice, Input, Presence, SecretInput, Select, StaticValue, SwitchRow, Tooltip,
} from '@/components/ui/kit'
import { EditableText } from '@/components/ui/EditableText'
import { SecretAutosave, quietly } from '@/components/ui/AutosaveFields'
import { useAutosave } from '@/components/ui/useAutosave'
import { SaveStatus } from '@/components/ui/SaveStatus'
import { protocolMarkId } from '@/features/indexing/utils/sourceMarks'
import { ModalShell, useFormDirty } from '@/components/ui/ModalShell'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { FEATURES } from '@/config/features'
import { WriteToolConsentModal } from './WriteToolConsentModal'
import { useSecretLabels } from './settingsParts'
import {
  connectionsApi, connectionError, useConnections,
  type Connection, type ConnectionInput, type ConnectionProtocol,
} from '../services/connectionsApi'
import { useServiceSetupMissing } from '@edition'
import { useConnectionSignInWait, useStartConnectionSignIn } from '../hooks/useConnectionSignIn'
import { useAuthStore } from '@/store/authStore'
import { McpAccessFields, McpWhereFields } from './McpServerFields'
import {
  buildMcpInput, initialMcpForm, mcpFormReady, mcpMissing, type McpFormState,
} from '../utils/mcpForm'

import { useConnectorCatalog } from '@/features/indexing/hooks/useConnectorCatalog'
import { FieldSpecForm } from '@/features/indexing/components/FieldSpecForm'
import type { FieldSpec } from '@/features/indexing/services/catalogApi'
import { fieldsForSignIn, hasOwnClient, usesBuiltInClient } from '../utils/builtInClient'
import { setupGuideUrl } from '../utils/setupGuide'
import {
  fieldProblems, fieldValuesChanged, initialFieldValues, submittedFieldValues, type FieldProblem,
} from '@/features/indexing/utils/fieldForm'

// The key the server's address travels under in the form's values - sent as `baseUrl`, beside the
// method's fields rather than among them.
const BASE_URL = 'baseUrl'

// Where a service sends the browser back after its sign-in (OAuth2ConnectionService.CALLBACK_PATH),
// at the address this page is open at - which is how the server builds it too. The owner registers it
// on the OAuth client they create at the service; Microsoft 365 has a callback of its own.
const CALLBACK_PATH = '/api/v1/connections/oauth/callback'

/**
 * Add or edit one connection - a server and the credential this user reaches it with, or a sign-in.
 * Never a group of fields behind the Settings page's shared bar.
 *
 * <p>TWO SHAPES (the maintainer, 2026-11-02: an existing item is edited in place, field by field;
 * adding keeps the form). ADDING (`connection` null) is a form with its own validation and its Add
 * button. An EXISTING connection is a view of it whose every field saves on its own when it is left
 * or changed (ConnectionEditor) - there is no Save.
 *
 * <p>DRAWN FROM THE SERVICE'S DESCRIPTOR (the connector catalog): its address field, its sign-in
 * methods - a choice when it offers more than one - and each method's fields, rendered by
 * FieldSpecForm. Nothing here knows a service by name but Microsoft 365, whose deployment-wide app
 * registration is a setup of its own, and an MCP server, whose form is its own (customDialog).
 * A method that signs in (OAUTH2_CODE) saves and then goes straight to the service's sign-in page.
 *
 * <p>`ownClient` and `presetWrite` open a new one already on the person's own OAuth client, and with Write on - the
 * YouTube assistant's "Your own Google Cloud app" (AddChannelDialog). That form asks only what the person's app gives
 * (the maintainer, 2026-11-09: what the app fixes "should not show here"): no "More options" - the API address is
 * Google's - no way back to the built-in client, which the person chose against a step before, and no redirect address
 * where the page is on a loopback address - Desktop, where the guide's Google "Desktop app" client signs in to any
 * loopback address unregistered, as the built-in one does. A Home Server is reached by its name, and keeps it.
 *
 * <p>`presetProtocol` opens a NEW connection on one service - from a source dialog that has none
 * yet, or from an `app://settings/connectors/jira` link. `onSaved` hands the saved connection back -
 * so a source dialog can select the one just added - and, on an existing one, each save.
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
export function ConnectionModal({ connection, presetProtocol, ownClient, presetWrite, onClose, onSaved }: {
  connection: Connection | null
  presetProtocol?: ConnectionProtocol
  /** A new connection opens on the person's own OAuth client rather than Desktop's built-in one - and asks only what
   *  that client gives: no advanced fields, no switch back, no redirect address on a loopback address. */
  ownClient?: boolean
  /** A new connection opens with Allow agent to write on (the YouTube assistant's channel: a reply is a write). */
  presetWrite?: boolean
  onClose: () => void
  onSaved?: (saved: Connection) => void
}) {
  return connection
    ? <ConnectionEditor connection={connection} onClose={onClose} onSaved={onSaved} />
    : <ConnectionAdder presetProtocol={presetProtocol} ownClient={ownClient} presetWrite={presetWrite} onClose={onClose} onSaved={onSaved} />
}

// On Enterprise Server a local command runs as the server process, and a shared server is in every
// user's agent: both are a Super admin's. Elsewhere there is one user, and both are theirs.
function useMcpRights() {
  const isSuperAdmin = useAuthStore(s => s.user)?.role === 'SUPER_ADMIN'
  return { mayRunLocal: !FEATURES.auth || isSuperAdmin, mayShare: FEATURES.auth && isSuperAdmin }
}

/** A NEW connection: the form, checked as a whole and sent by its Add (or Sign in) button. */
function ConnectionAdder({ presetProtocol, ownClient: startOwn = false, presetWrite = false, onClose, onSaved }: {
  presetProtocol?: ConnectionProtocol
  ownClient?: boolean
  presetWrite?: boolean
  onClose: () => void
  onSaved?: (saved: Connection) => void
}) {
  const { t, locale } = useTranslation()
  const queryClient = useQueryClient()
  const startSignIn = useStartConnectionSignIn()
  const { catalog } = useConnectorCatalog()
  const protocols = catalog.protocols
  const { data: existing = [] } = useConnections()

  const [name, setName] = useState('')
  // Empty until chosen: the first offered protocol stands in, so the select is never blank while
  // the catalogue is loading and never names one this deployment does not offer.
  const [chosenProtocol, setProtocol] = useState<ConnectionProtocol | ''>(presetProtocol ?? '')
  const protocol = chosenProtocol || protocols[0]?.protocol || ''
  const [secret, setSecret] = useState('')
  const [isDefault, setIsDefault] = useState(false)
  const [allowRead, setAllowRead] = useState(true)
  const [allowWrite, setAllowWrite] = useState(presetWrite)
  const [consentOpen, setConsentOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [problems, setProblems] = useState<Record<string, FieldProblem>>({})
  const [mcp, setMcp] = useState<McpFormState>(() => initialMcpForm(null, null))
  const updateMcp = (patch: Partial<McpFormState>) => setMcp(m => ({ ...m, ...patch }))
  const { mayRunLocal, mayShare } = useMcpRights()

  const info = protocols.find(p => p.protocol === protocol)
  const serviceLabel = info ? t(info.displayName) : ''
  const supportsWrite = info?.supportsWrite ?? false
  const isMcp = protocol === 'MCP_SERVER'

  // The sign-in method: the service's first, unless another is chosen. A choice only where there are two.
  const methods = info?.authMethods ?? []
  const [chosenMethod, setMethod] = useState<string>('')
  const method = methods.find(m => m.kind === chosenMethod) ?? methods[0]
  const signsIn = method?.kind === 'OAUTH2_CODE'
  // Desktop's built-in client (utils/builtInClient.ts): no Client ID or secret to type, unless the person chooses their own.
  const [ownClient, setOwnClient] = useState(startOwn)
  const builtIn = usesBuiltInClient(method, ownClient)
  // The form's fields: where the service is (when it has an address), then the method's own - opened on the person's
  // own client (startOwn), only the ones on the form's face: what is behind "More options" keeps its default.
  const fields: FieldSpec[] = useMemo(() => fieldsForSignIn([
    ...(info?.baseUrl ? [{ ...info.baseUrl, key: BASE_URL }] : []),
    ...(method?.fields ?? []),
  ], builtIn).filter(f => !(startOwn && f.advanced)), [info, method, builtIn, startOwn])
  // A loopback page (Desktop): a Google "Desktop app" client needs no redirect address registered.
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(window.location.hostname)
  // What the form opens with - each field's default. Typed values sit over it, and go when the
  // service or the method changes.
  const initial = useMemo(() => initialFieldValues(fields), [fields])
  const [edits, setEdits] = useState<Record<string, string>>({})
  const values = { ...initial, ...edits }
  const onField = (key: string, value: string) => {
    setEdits(e => ({ ...e, [key]: value }))
    setProblems(p => {
      if (!(key in p)) return p
      const next = { ...p }
      delete next[key]
      return next
    })
  }
  const pickProtocol = (p: string) => { setProtocol(p); setMethod(''); setEdits({}); setProblems({}); setOwnClient(false) }
  const pickMethod = (m: string) => { setMethod(m); setEdits({}); setProblems({}); setOwnClient(false) }

  // A service the license leaves out - Microsoft 365 on an Enterprise Server with no license in force.
  const locked = !!info && !info.licensed
  // The deployment not set up for the service (Enterprise Server's Microsoft 365 app registration):
  // the sign-in cannot start, so it is not offered. Null when nothing is missing.
  const setupMissing = useServiceSetupMissing(protocol)
  // CHOSEN values, not the effective ones: the stand-in arriving with the catalogue is not an edit,
  // and counting it would ask "Discard unsaved changes?" of a form nobody touched.
  const { dirty: otherDirty } = useFormDirty({ name, chosenProtocol, chosenMethod, secret, isDefault, allowRead, allowWrite, mcp })
  const dirty = otherDirty || fieldValuesChanged(fields, initial, values)

  // The backend makes the first connection of a service its default whatever this box says, so
  // where the answer is already decided the box shows it and cannot be changed.
  const firstOfService = !existing.some(c => c.protocol === protocol)

  const save = useMutation({
    mutationFn: () => {
      const { [BASE_URL]: address, ...methodFields } = submittedFieldValues(fields, values)
      const input: ConnectionInput = isMcp ? {
        name: name.trim(),
        ...(mcp.transport !== 'STDIO' ? { baseUrl: mcp.url.trim() } : {}),
        ...(mcp.transport !== 'STDIO' && mcp.auth === 'BEARER' && secret.trim() ? { secret: secret.trim() } : {}),
        allowRead,
        mcp: buildMcpInput(mcp, mayShare),
      } : {
        name: name.trim(),
        // An address left empty on a new connection takes the service's own (a vendor's public API).
        ...(info?.baseUrl && address ? { baseUrl: address } : {}),
        ...(method ? { authMethod: method.kind } : {}),
        fields: methodFields,
        // Only ever TRUE: that is what moves the default here. Unsetting it is not something a
        // connection can do on its own - another one takes it over.
        ...(isDefault || firstOfService ? { isDefault: true } : {}),
        allowRead,
        allowWrite: supportsWrite && allowWrite,
      }
      return connectionsApi.create({ ...input, protocol })
    },
    onSuccess: saved => {
      queryClient.invalidateQueries({ queryKey: keys.connections() })
      // A source shows the name of the connection it reads through.
      queryClient.invalidateQueries({ queryKey: keys.connectorsByType() })
      queryClient.invalidateQueries({ queryKey: keys.connectorSummary() })
      // A new connection that signs in goes straight to the service's sign-in - the one step left
      // before it can be used. On Desktop and Home Server it opens in the browser and the dialog
      // closes: the connection's row says to finish there and updates by itself. On Enterprise
      // Server the page itself goes, and comes back with ?connection-oauth=<outcome> (or, for
      // Microsoft 365, ?microsoft=<outcome>), and a notice says how it went.
      if (signsIn && !setupMissing) {
        void startSignIn(saved)
        if (!FEATURES.auth) {
          onSaved?.(saved)
          onClose()
        }
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
      const found = fieldProblems(fields, values)
      // An address the service has a default for may be left empty.
      if (info?.baseUrl?.defaultValue && found[BASE_URL]?.code === 'required') delete found[BASE_URL]
      setProblems(found)
      if (Object.keys(found).length > 0) return
    }
    save.mutate()
  }

  const secretLabels = useSecretLabels()
  const tokenField = (
    <Field label={t('Access token')} hint={t('Sent as a bearer token.')}>
      {id => <SecretInput id={id} value={secret} onChange={setSecret} labels={secretLabels} />}
    </Field>
  )

  return (
    <ModalShell
      onClose={onClose}
      closable={!save.isPending}
      dirty={dirty}
      initialFocus="first-field"
      size="form"
      ariaLabel={t('Add connection')}
    >
      {requestClose => (<>
        <DialogHeader title={t('Add connection')} />
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
                  {/* Each service with its mark, in the text's own ink - the same mark the connection's
                      row and its sources' cards carry. */}
                  {id => (
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
                  />
                </FadeSwap>
              </Presence>
              {/* Where what these fields ask for comes from, step by step (utils/setupGuide.ts) - none to read for a
                  sign-in through the built-in client, which asks nothing. */}
              <Presence show={!isMcp && !builtIn && fields.length > 0 && !!setupGuideUrl(info?.docsAnchor, locale)}>
                <ExternalLink href={setupGuideUrl(info?.docsAnchor, locale) ?? ''}>
                  {t('How to set up {service}', { service: serviceLabel })}
                </ExternalLink>
              </Presence>

              {/* A sign-in has no secret to type: the connection is reached as whoever signs in. */}
              <Presence show={signsIn && !isMcp && (!startOwn || !!setupMissing)}>
                <FadeSwap swapKey={setupMissing ? 'setup' : 'new'}>
                {setupMissing ? (
                  <Caption tone="warning">{setupMissing}</Caption>
                ) : (
                  <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
                    <Caption>
                      {t('Saving takes you to {service} to sign in.', { service: serviceLabel })}
                    </Caption>
                    {method?.builtInClient && (
                      <Button size="sm" variant="quiet" onClick={() => { setOwnClient(o => !o); setEdits({}); setProblems({}) }}>
                        {ownClient ? t('Use the built-in sign-in') : t('Use my own OAuth client')}
                      </Button>
                    )}
                  </div>
                )}
                </FadeSwap>
              </Presence>
              <Presence show={signsIn && !isMcp && !setupMissing && !builtIn && protocol !== 'MICROSOFT_365' && !(startOwn && loopback)}>
                <CopyField
                  label={t('Redirect address')}
                  value={window.location.origin + CALLBACK_PATH}
                  hint={t('Add it as a redirect address on your OAuth client at {service}.', { service: serviceLabel })}
                  copyLabel={t('Copy')}
                  copiedLabel={t('Copied')}
                />
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
                editing={false}
                signedIn={false}
              />
              <div>
                <SwitchRow
                  title={t('Allow agent to use')}
                  caption={t("Offer this server's tools to the agent. Each call asks first, unless in Auto.")}
                  checked={allowRead}
                  onChange={setAllowRead}
                />
                {mayShare && (
                  <SwitchRow
                    title={t('Share with everyone')}
                    caption={t('Every user can use its tools.')}
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
                disabled={firstOfService}
                hint={firstOfService
                  ? t('Your first {service} connection is the default.', { service: serviceLabel })
                  : t('Used by the agent and by sources with no connection set.')}
              >
                {t('Use as default for {service}', { service: serviceLabel })}
              </Checkbox>

              <SwitchRow
                className="mt-2"
                title={t('Allow agent to read')}
                caption={t('Let the agent search and read {service}.', { service: serviceLabel })}
                checked={allowRead}
                onChange={setAllowRead}
              />

              <Presence show={supportsWrite}>
                <SwitchRow
                  title={t('Allow agent to write')}
                  caption={t('Let the agent create, update and delete {service} content.', { service: serviceLabel })}
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

/**
 * An existing connection as it is edited: everything the add form holds, each saved on its own. The
 * whole connection is sent each time (connectionsApi.update, one change over what the server last
 * confirmed - useAutosave), with a secret only when one was just typed: a blank one is never sent,
 * since the stored one is never sent to this page.
 */
interface EditState {
  name: string
  /** The sign-in method's kind; '' for a service with none. */
  method: string
  /** The address and the method's fields as the server holds them - every secret '' (never sent here). */
  fieldValues: Record<string, string>
  /** Secrets just typed, by field - sent with the save they arrive in, then gone. */
  secrets: Record<string, string>
  clearSecret: boolean
  /** An MCP server's bearer token, just typed. */
  bearer: string
  isDefault: boolean
  allowRead: boolean
  allowWrite: boolean
  mcp: McpFormState
}

/**
 * AN EXISTING CONNECTION, EDITED IN PLACE. A text is click-to-edit and saved when it is left; a
 * switch, a box or a choice as it changes; a secret keeps its masked box and is saved when it is
 * left. A change the server refuses goes back, and the refusal is said under the fields.
 *
 * <p>A CHANGE THAT CANNOT STAND ALONE IS HELD: a sign-in method whose fields are not filled in yet, an
 * MCP server switched to a local command with no command, or to an address with none, or to a bearer
 * token with none stored. It shows, a note says what it waits for, and it is saved with the field
 * that completes it. Closing the dialog then asks before dropping it.
 */
function ConnectionEditor({ connection, onClose, onSaved }: {
  connection: Connection
  onClose: () => void
  onSaved?: (saved: Connection) => void
}) {
  const { t, locale } = useTranslation()
  const queryClient = useQueryClient()
  const { catalog } = useConnectorCatalog()
  const info = catalog.protocols.find(p => p.protocol === connection.protocol)
  const secretLabels = useSecretLabels()
  const { mayRunLocal, mayShare } = useMcpRights()
  // The connection as the server last answered - what the dialog says about it (signed in, the
  // stored credential's mask) is read from here, never from what was typed.
  const [current, setCurrent] = useState(connection)
  const currentRef = useRef(current)
  currentRef.current = current
  const [error, setError] = useState<string | null>(null)
  const [held, setHeld] = useState<Partial<EditState>>({})
  const [consentOpen, setConsentOpen] = useState(false)
  // A save that dropped the sign-in - a changed address forgets it (McpServerConnectionTest), and so
  // does a changed OAuth client: a new client id or secret, or address (OAuthClientEditTest).
  const [signInLost, setSignInLost] = useState(false)
  const startSignIn = useStartConnectionSignIn()

  // A sign-in finished in the browser while the dialog is open: the list is read again
  // (useConnectionSignInWait), and what it says about the sign-in is taken over here.
  const { data: listed } = useConnections()
  const live = listed?.find(c => c.id === connection.id)
  useEffect(() => {
    if (!live) return
    setCurrent(c => (c.status === live.status && c.connectedAt === live.connectedAt && c.accountLabel === live.accountLabel
      ? c
      : { ...c, status: live.status, statusDetail: live.statusDetail, accountLabel: live.accountLabel,
          connectedAt: live.connectedAt, secretConfigured: live.secretConfigured, mcp: live.mcp ?? c.mcp }))
  }, [live?.status, live?.connectedAt, live?.accountLabel])
  const browserSignIn = useConnectionSignInWait(current.authKind === 'OAUTH' ? current : undefined)

  const isMcp = connection.protocol === 'MCP_SERVER'
  const serviceLabel = info ? t(info.displayName) : connection.protocolLabel
  const supportsWrite = info?.supportsWrite ?? connection.supportsWrite
  const methods = info?.authMethods ?? []
  const methodOf = (kind: string) => methods.find(m => m.kind === kind) ?? methods[0]
  const fieldsFor = (kind: string): FieldSpec[] => [
    ...(info?.baseUrl ? [{ ...info.baseUrl, key: BASE_URL }] : []),
    ...(methodOf(kind)?.fields ?? []),
  ]
  // What the server holds that a field shows: its address, user name and settings - never a secret.
  const storedValues = (c: Connection) => ({ ...(c.settings ?? {}), [BASE_URL]: c.baseUrl, username: c.username })
  const stateOf = (c: Connection): EditState => {
    const method = methodOf(c.authMethod ?? '')?.kind ?? ''
    return {
      name: c.name,
      method,
      fieldValues: initialFieldValues(fieldsFor(method), storedValues(c)),
      secrets: {},
      clearSecret: false,
      bearer: '',
      isDefault: c.isDefault,
      allowRead: c.allowRead,
      allowWrite: c.allowWrite,
      mcp: initialMcpForm(c.mcp, c.baseUrl),
    }
  }
  // Signed in as the connection's row says it (ConnectionsTab): a grant held, whether or not it still works.
  const signedInOf = (c: Connection) => (isMcp ? !!c.mcp?.signedIn : c.status === 'CONNECTED' || c.status === 'NEEDS_RECONNECT')

  const inputOf = (s: EditState): ConnectionInput => {
    if (isMcp) {
      const remote = s.mcp.transport !== 'STDIO'
      return {
        name: s.name.trim(),
        ...(remote ? { baseUrl: s.mcp.url.trim() } : {}),
        ...(remote && s.mcp.auth === 'BEARER'
          ? (s.bearer.trim() ? { secret: s.bearer.trim() } : s.clearSecret ? { clearSecret: true } : {})
          : {}),
        allowRead: s.allowRead,
        mcp: buildMcpInput(s.mcp, mayShare),
      }
    }
    const method = methodOf(s.method)
    const { [BASE_URL]: address, ...methodFields } = submittedFieldValues(fieldsFor(s.method), { ...s.fieldValues, ...s.secrets })
    return {
      name: s.name.trim(),
      ...(info?.baseUrl ? { baseUrl: address ?? '' } : {}),
      ...(method ? { authMethod: method.kind } : {}),
      fields: methodFields,
      ...(s.clearSecret ? { clearSecret: true } : {}),
      // Only ever TRUE: that is what moves the default here. Unsetting it is not something a
      // connection can do on its own - another one takes it over.
      ...(s.isDefault ? { isDefault: true } : {}),
      allowRead: s.allowRead,
      allowWrite: supportsWrite && s.allowWrite,
    }
  }

  // Waits for the catalog for a service's fields; an MCP server's are its own.
  const base = useMemo(() => (isMcp || info ? stateOf(current) : undefined), [current, info, isMcp])
  const { value, save, status: saveStatus, idle } = useAutosave<EditState>(base, async next => {
    const saved = await connectionsApi.update(connection.id, inputOf(next))
    queryClient.invalidateQueries({ queryKey: keys.connections() })
    // A source shows the name of the connection it reads through.
    queryClient.invalidateQueries({ queryKey: keys.connectorsByType() })
    queryClient.invalidateQueries({ queryKey: keys.connectorSummary() })
    if (signedInOf(currentRef.current) && !signedInOf(saved)) setSignInLost(true)
    if (signedInOf(saved)) setSignInLost(false)
    setCurrent(saved)
    setError(null)
    onSaved?.(saved)
    return stateOf(saved)
  }, {
    failure: t('Could not save this connection.'),
    // The backend writes these refusals to be read verbatim - "There is already a connection
    // called Work", "Jira needs a server URL" - so they are shown as they are, under the fields.
    onError: e => setError(connectionError(e) ?? t('Could not save this connection.')),
  })
  const shown: EditState | undefined = value ? { ...value, ...held } : undefined
  const savedMethod = base?.method ?? ''

  // What a held change still waits for, in words; null when it can be saved.
  const missingOf = (s: EditState): string | null => {
    if (isMcp) {
      const c = currentRef.current
      const missing = mcpMissing(s.mcp, c.secretConfigured && c.mcp?.auth === 'BEARER', !!s.bearer.trim())
      return missing === 'command' ? t('Type the command to save this change.')
        : missing === 'url' ? t('Type the server URL to save this change.')
          : missing === 'token' ? t('Type the token to save this change.')
            : null
    }
    if (s.method === savedMethod) return null
    const found = fieldProblems(fieldsFor(s.method), { ...s.fieldValues, ...s.secrets })
    // An address the service has a default for may be left empty.
    if (info?.baseUrl?.defaultValue && found[BASE_URL]?.code === 'required') delete found[BASE_URL]
    return Object.keys(found).length > 0 ? t('Fill in the fields below to switch.') : null
  }

  // One change: saved with whatever is held when that completes it; saved on its own when it does
  // not touch what is held; else held with it.
  const commit = (patch: Partial<EditState>): Promise<unknown> => {
    if (!value) return Promise.reject(new Error('not loaded'))
    if (!missingOf({ ...value, ...held, ...patch })) {
      const all = { ...held, ...patch }
      setHeld({})
      return save(all)
    }
    const touchesHeld = Object.keys(patch).some(k => k in held)
    if (Object.keys(held).length > 0 && !touchesHeld && !missingOf({ ...value, ...patch })) return save(patch)
    setHeld(h => ({ ...h, ...patch }))
    return Promise.resolve()
  }
  const heldNote = shown && Object.keys(held).length > 0 ? missingOf(shown) : null

  const method = shown ? methodOf(shown.method) : undefined
  const signsIn = method?.kind === 'OAUTH2_CODE'
  // Signed in through Desktop's built-in client (utils/builtInClient.ts): the client's fields stay out until the person
  // chooses their own - one stored on the connection always shows.
  const [ownClientChosen, setOwnClientChosen] = useState(false)
  const builtIn = usesBuiltInClient(method, ownClientChosen || hasOwnClient(current.settings))
  const fields = shown ? fieldsForSignIn(fieldsFor(shown.method), builtIn) : []
  const secretFields = (method?.fields ?? []).filter(f => f.type === 'SECRET')
  // The secrets already held, as the masked box shows them: one secret is masked by the server; of
  // several, each is only known to be set. None for a method not saved yet - its secrets are new.
  const storedSecrets: Record<string, string> = {}
  if (shown && shown.method === savedMethod) {
    for (const f of secretFields) {
      if (secretFields.length === 1 && current.secretConfigured) storedSecrets[f.key] = current.secretMasked || '********'
      else if (current.secretsConfigured?.includes(f.key)) storedSecrets[f.key] = '********'
    }
  }
  const pickMethod = (m: string) => {
    if (!shown || m === shown.method) return
    // Back to the saved method: nothing to save, the held switch is dropped.
    if (m === savedMethod) { setHeld({}); return }
    quietly(commit({ method: m, fieldValues: initialFieldValues(fieldsFor(m), storedValues(current)), secrets: {} }))
  }
  const onField = (key: string, v: string) => {
    if (!shown) return Promise.reject(new Error('not loaded'))
    return secretFields.some(f => f.key === key)
      ? commit({ secrets: { ...shown.secrets, [key]: v } })
      : commit({ fieldValues: { ...shown.fieldValues, [key]: v } })
  }
  const commitMcp = (patch: Partial<McpFormState>) => (shown ? commit({ mcp: { ...shown.mcp, ...patch } }) : Promise.reject(new Error('not loaded')))

  // A service the license leaves out - Microsoft 365 on an Enterprise Server with no license in force.
  const locked = !!info && !info.licensed
  // The deployment not set up for the service: the sign-in cannot start. Null when nothing is missing.
  const setupMissing = useServiceSetupMissing(connection.protocol)

  // A save just forgot the sign-in - the client it was given to changed - and it has not been made again.
  const clientChanged = !isMcp && signInLost && !signedInOf(current)
  // The sign-in, as the list's row and the add form start it (useStartConnectionSignIn): in the browser
  // on Desktop and Home Server, by leaving the page on Enterprise Server. It waits for every save still on
  // its way first - the click that pressed it may have just left a field, and leaving the page would
  // drop that save.
  const [signingIn, setSigningIn] = useState(false)
  const signIn = async () => {
    setSigningIn(true)
    try {
      await idle()
      await startSignIn(currentRef.current)
    } finally {
      setSigningIn(false)
    }
  }
  const signInLabel = signedInOf(current) || clientChanged ? t('Sign in again') : t('Sign in')
  const signInButton = (
    <Tooltip label={t('Sign in to {service} through this connection.', { service: serviceLabel })}>
      <Button variant="secondary" size="sm" onClick={() => { void signIn() }} disabled={locked || signingIn} loading={signingIn}>
        {!signingIn && <LogIn size={14} aria-hidden="true" />}
        {signInLabel}
      </Button>
    </Tooltip>
  )

  // The stored credential, as the server masks it - the kit SecretInput shows it masked until it is
  // revealed or typed over.
  const storedSecret = current.secretConfigured ? (current.secretMasked ?? '') : undefined
  const tokenField = (
    <Field label={t('Access token')} hint={t('Sent as a bearer token.')}>
      {id => <SecretAutosave id={id} serverValue={storedSecret} onCommit={v => commit({ bearer: v })} labels={secretLabels} />}
    </Field>
  )
  // Whether there is a stored credential to remove: an MCP server holds one only for a bearer; a
  // sign-in's grant is removed by signing out, on the connection's row.
  const removableSecret = !!shown && (isMcp
    ? current.secretConfigured && current.mcp?.auth === 'BEARER' && shown.mcp.transport !== 'STDIO' && shown.mcp.auth === 'BEARER'
    : !signsIn && secretFields.length > 0 && shown.method === savedMethod
      && (current.secretConfigured || (current.secretsConfigured?.length ?? 0) > 0))
  // Removing it is its own act, never an empty box (an empty box is what every stored secret looks
  // like here) - and it is saved at once, as every change here is. A new one can be typed after.
  const removeSecretButton = (
    <Presence show={removableSecret}>
      <div>
        <Button variant="danger-quiet" onClick={() => quietly(commit({ clearSecret: true }))}>
          {t('Remove the stored credential')}
        </Button>
      </div>
    </Presence>
  )

  return (
    <ModalShell
      onClose={onClose}
      // A held change is the one thing closing would lose.
      dirty={Object.keys(held).length > 0}
      initialFocus="panel"
      size="form"
      ariaLabel={t('Edit connection')}
    >
      {() => (<>
        <DialogHeader title={t('Edit connection')} actions={<SaveStatus word={saveStatus} />} />
        <DialogBody>
          <FadeSwap swapKey={!!shown}>
          {!shown ? (
            <div aria-hidden="true" className="space-y-4"><FieldSkeleton /><FieldSkeleton /><FieldSkeleton /></div>
          ) : (
          <FormColumns from="lg" density="columns" className="items-start">
            <FormStack className="min-w-0">
              <FormColumns className="items-start">
                <Field label={t('Name')}>
                  <EditableText
                    value={shown.name}
                    label={t('Name')}
                    placeholder={serviceLabel}
                    required={t('Name is required')}
                    onCommit={name => commit({ name })}
                  />
                </Field>
                <Field label={t('Service')}>
                  {/* Fixed once saved: a Jira connection's sources were registered against Jira's
                      API, and no other service could read them. The kit's StaticValue - the value at
                      the field's height - rather than a control. */}
                  <StaticValue icon={<BrandMark id={protocolMarkId(connection.protocol)} size={14} />}>{serviceLabel}</StaticValue>
                </Field>
              </FormColumns>

              {isMcp && (
                <McpWhereFields state={shown.mcp} onCommit={commitMcp} localAllowed={mayRunLocal} />
              )}

              {/* How it signs in - a choice only where the service offers more than one way, and fixed
                  for a sign-in, whose grant belongs to the method it was given for. */}
              <Presence show={!isMcp && methods.length > 1}>
                <Field label={t('Sign in with')}>
                  {id => (
                    <Select
                      id={id}
                      value={method?.kind ?? ''}
                      onChange={pickMethod}
                      disabled={!!current.authMethod && methodOf(savedMethod)?.kind === 'OAUTH2_CODE'}
                      options={methods.map(m => ({ value: m.kind, label: t(m.label) }))}
                    />
                  )}
                </Field>
              </Presence>

              {/* Each field saved on its own (FieldSpecForm's onCommit). Keyed by method, so a switch
                  fades the new fields in rather than morphing. */}
              <Presence show={!isMcp && fields.length > 0}>
                <FadeSwap swapKey={method?.kind ?? ''}>
                  <FieldSpecForm
                    fields={fields}
                    values={{ ...shown.fieldValues }}
                    onCommit={onField}
                    storedSecrets={storedSecrets}
                  />
                </FadeSwap>
              </Presence>

              {!isMcp && removeSecretButton}
              <Presence show={!isMcp && !builtIn && fields.length > 0 && !!setupGuideUrl(info?.docsAnchor, locale)}>
                <ExternalLink href={setupGuideUrl(info?.docsAnchor, locale) ?? ''}>
                  {t('How to set up {service}', { service: serviceLabel })}
                </ExternalLink>
              </Presence>

              {/* THE SIGN-IN, HERE AS ON THE ADD FORM (the maintainer, 2026-11-03: editing the client
                  showed "no connect/login button like when connecting for the first time"). Who it is
                  signed in as, and the sign-in itself - "Sign in", or "Sign in again" over a grant. Right
                  after a change of client, which forgets the grant (OAuthClientEditTest), one warning line
                  says to sign in again with the new client, the button beside it. */}
              <Presence show={signsIn && !isMcp}>
                <FadeSwap swapKey={setupMissing ? 'setup' : clientChanged ? 'changed' : 'state'}>
                {setupMissing ? (
                  <Caption tone="warning">{setupMissing}</Caption>
                ) : clientChanged ? (
                  <Callout tone="warning" actions={signInButton}>
                    {t('The sign-in was for the old client. Sign in again with the new one.')}
                  </Callout>
                ) : (
                  <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
                    <Caption>
                      {current.accountLabel
                        ? t('Signed in as {account}.', { account: current.accountLabel })
                        : signedInOf(current) ? t('Signed in.') : t('Not signed in yet.')}
                    </Caption>
                    <div className="flex flex-wrap items-center gap-2">
                      {builtIn && (
                        <Button size="sm" variant="quiet" onClick={() => setOwnClientChosen(true)}>
                          {t('Use my own OAuth client')}
                        </Button>
                      )}
                      {signInButton}
                    </div>
                  </div>
                )}
                </FadeSwap>
              </Presence>
              <Presence show={signsIn && !isMcp && !!browserSignIn.wait}>
                <Caption>{t('Your browser opened for the sign-in.')}</Caption>
              </Presence>
              <Presence show={signsIn && !isMcp && !setupMissing && !builtIn && connection.protocol !== 'MICROSOFT_365'}>
                <CopyField
                  label={t('Redirect address')}
                  value={window.location.origin + CALLBACK_PATH}
                  hint={t('Add it as a redirect address on your OAuth client at {service}.', { service: serviceLabel })}
                  copyLabel={t('Copy')}
                  copiedLabel={t('Copied')}
                />
              </Presence>

              {/* What a held change waits for, a save the server refused, and a sign-in a save dropped. */}
              <Presence show={!!heldNote}>
                <Caption tone="warning">{heldNote ?? ''}</Caption>
              </Presence>
              <Presence show={!!error}>
                <InlineNotice tone="danger" icon={AlertTriangle}>{error ?? ''}</InlineNotice>
              </Presence>
              <Presence show={isMcp && signInLost && !signedInOf(current)}>
                <InlineNotice tone="warning">{t('Sign in again to use its tools.')}</InlineNotice>
              </Presence>
            </FormStack>

            {/* The right column: what the agent may do. */}
            {isMcp ? (
            <div className="min-w-0 space-y-4">
              <McpAccessFields
                state={shown.mcp}
                onCommit={commitMcp}
                tokenField={tokenField}
                editing
                signedIn={!!current.mcp?.signedIn}
              />
              {removeSecretButton}
              <div>
                <SwitchRow
                  title={t('Allow agent to use')}
                  caption={t("Offer this server's tools to the agent. Each call asks first, unless in Auto.")}
                  checked={shown.allowRead}
                  onChange={allowRead => quietly(commit({ allowRead }))}
                />
                {mayShare && (
                  <SwitchRow
                    title={t('Share with everyone')}
                    caption={t('Every user can use its tools.')}
                    checked={shown.mcp.shared}
                    onChange={shared => quietly(commitMcp({ shared }))}
                  />
                )}
              </div>
            </div>
            ) : (
            <div className="min-w-0">
              {/* The default moves only when another is made the default, so the default's own box
                  is shown ticked and fixed. */}
              <Checkbox
                checked={shown.isDefault}
                onChange={on => { if (on) quietly(commit({ isDefault: true })) }}
                disabled={current.isDefault}
                hint={current.isDefault
                  ? t('To change it, make another connection the default.')
                  : t('Used by the agent and by sources with no connection set.')}
              >
                {t('Use as default for {service}', { service: serviceLabel })}
              </Checkbox>

              <SwitchRow
                className="mt-2"
                title={t('Allow agent to read')}
                caption={t('Let the agent search and read {service}.', { service: serviceLabel })}
                checked={shown.allowRead}
                onChange={allowRead => quietly(commit({ allowRead }))}
              />

              <Presence show={supportsWrite}>
                <SwitchRow
                  title={t('Allow agent to write')}
                  caption={t('Let the agent create, update and delete {service} content.', { service: serviceLabel })}
                  checked={shown.allowWrite}
                  onChange={v => (v ? setConsentOpen(true) : quietly(commit({ allowWrite: false })))}
                />
              </Presence>

              <Presence show={locked}>
                <Caption icon={Lock} className="mt-3">
                  {t('{service} needs an Enterprise license.', { service: serviceLabel })}
                </Caption>
              </Presence>
            </div>
            )}
          </FormColumns>
          )}
          </FadeSwap>
        </DialogBody>

        {consentOpen && (
          <WriteToolConsentModal
            connectorLabel={shown?.name.trim() || serviceLabel}
            onAccept={() => { setConsentOpen(false); quietly(commit({ allowWrite: true })) }}
            onCancel={() => setConsentOpen(false)}
          />
        )}
      </>)}
    </ModalShell>
  )
}
