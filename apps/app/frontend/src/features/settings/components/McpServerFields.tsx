import type { ReactNode } from 'react'
import { Caption, FadeSwap, Field, FormColumns, Input, ListEditor, Presence, SecretInput, Select, Textarea } from '@/components/ui/kit'
import { useSecretLabels } from './settingsParts'
import { useTranslation } from '@/i18n'
import type { McpAuth, McpTransport } from '../services/connectionsApi'
import type { McpFormState, NamedValue } from '../utils/mcpForm'

// Where the sign-in comes back to, for a server that wants the client registered by hand.
const CALLBACK_PATH = '/api/v1/mcp-oauth/callback'

type Update = (patch: Partial<McpFormState>) => void

/**
 * WHERE an MCP server is: its transport, then its address - or, for a local one, the command that
 * starts it. The left column of ConnectionModal, under the name.
 *
 * <p>A local command is offered on Enterprise Server to a Super admin only (`localAllowed`): it runs
 * as the server process. The server refuses it to anyone else as well; hiding it is the courtesy.
 */
export function McpWhereFields({ state, update, localAllowed }: {
  state: McpFormState
  update: Update
  localAllowed: boolean
}) {
  const { t } = useTranslation()
  const transports: { value: McpTransport; label: string }[] = [
    { value: 'STREAMABLE_HTTP', label: t('Streamable HTTP') },
    { value: 'SSE', label: t('Server-Sent Events (SSE)') },
    ...(localAllowed || state.transport === 'STDIO' ? [{ value: 'STDIO' as const, label: t('Local command (stdio)') }] : []),
  ]
  // A block of its own with its own rhythm: it sits in a Presence in the form, so the form's own
  // spacing reaches it as ONE child.
  return (<div className="space-y-4">
    <Field label={t('Transport')}>
      {id => <Select id={id} value={state.transport} onChange={v => update({ transport: v })} options={transports} />}
    </Field>

    {/* A command or an address, faded across as the transport changes. */}
    <FadeSwap swapKey={state.transport === 'STDIO'}>
    {state.transport === 'STDIO' ? (
      <div className="space-y-4">
        <Field
          label={t('Command')}
          hint={t('A program on this computer, such as npx, uvx or docker. It starts with a minimal environment plus the variables added here.')}
        >
          {id => <Input id={id} value={state.command} onChange={e => update({ command: e.target.value })} placeholder="npx" autoComplete="off" />}
        </Field>
        <Field label={t('Arguments')} hint={t('One per line.')}>
          {id => (
            <Textarea
              id={id}
              mono
              rows={3}
              value={state.argsText}
              onChange={e => update({ argsText: e.target.value })}
              placeholder={'-y\n@modelcontextprotocol/server-filesystem'}
            />
          )}
        </Field>
      </div>
    ) : (
      <Field
        label={t('Server URL')}
        hint={state.transport === 'SSE'
          ? t('The address of the SSE endpoint, often ending in /sse.')
          : t('The address of the MCP endpoint, often ending in /mcp.')}
      >
        {id => (
          <Input
            id={id}
            value={state.url}
            onChange={e => update({ url: e.target.value })}
            placeholder={state.transport === 'SSE' ? 'https://mcp.example.com/sse' : 'https://mcp.example.com/mcp'}
            autoComplete="off"
          />
        )}
      </Field>
    )}
    </FadeSwap>
  </div>)
}

/**
 * HOW Proxyma signs in to a remote MCP server - nothing, a bearer token, named headers or an OAuth
 * sign-in - or, for a local one, its environment variables. The right column of ConnectionModal.
 * The bearer token's box is the modal's own (`tokenField`), since it is the connection's one secret
 * and shares the stored-credential handling every connection has.
 */
export function McpAccessFields(props: McpAccessProps) {
  // A command's variables or a server's sign-in, faded across as the transport changes.
  return <FadeSwap swapKey={props.state.transport === 'STDIO'}><McpAccessBody {...props} /></FadeSwap>
}

interface McpAccessProps {
  state: McpFormState
  update: Update
  tokenField: ReactNode
  editing: boolean
  signedIn: boolean
}

function McpAccessBody({ state, update, tokenField, editing, signedIn }: McpAccessProps) {
  const { t } = useTranslation()
  const secretLabels = useSecretLabels()

  if (state.transport === 'STDIO') {
    return (
      <Field label={t('Environment variables')} hint={t('Passed to the command. A saved value is kept while its box is left empty.')}>
        <NamedValues
          rows={state.env}
          onChange={env => update({ env })}
          namePlaceholder="API_KEY"
          addLabel={t('Add variable')}
          removeLabel={t('Remove variable')}
        />
      </Field>
    )
  }

  const auths: { value: McpAuth; label: string }[] = [
    { value: 'NONE', label: t('No authentication') },
    { value: 'BEARER', label: t('Bearer token') },
    { value: 'HEADERS', label: t('Custom headers') },
    { value: 'OAUTH', label: t('OAuth sign-in') },
  ]
  // Each way of signing in opens under the choice and closes when another is chosen (Presence).
  return (<div className="space-y-4">
    <Field label={t('Authentication')}>
      {id => <Select id={id} value={state.auth} onChange={v => update({ auth: v })} options={auths} />}
    </Field>

    <Presence show={state.auth === 'BEARER'}>{tokenField}</Presence>

    <Presence show={state.auth === 'HEADERS'}>
      <Field label={t('Headers')} hint={t('Sent with every request. A saved value is kept while its box is left empty.')}>
        <NamedValues
          rows={state.headers}
          onChange={headers => update({ headers })}
          namePlaceholder="X-Api-Key"
          addLabel={t('Add header')}
          removeLabel={t('Remove header')}
        />
      </Field>
    </Presence>

    <Presence show={state.auth === 'OAUTH'}>
      <div className="space-y-3">
        <Caption>
          {!editing
            ? t('After saving, press Sign in on the connection to sign in to this server.')
            : signedIn
              ? t('Signed in. Sign in again from the connection list to use a different account.')
              : t('Not signed in yet. Use Sign in on the connection list.')}
        </Caption>
        <FormColumns className="items-start">
          <Field label={t('Client ID')}>
            {id => <Input id={id} value={state.oauthClientId} onChange={e => update({ oauthClientId: e.target.value })} placeholder={t('Optional')} autoComplete="off" />}
          </Field>
          {/* The kit's one secret control. It has no stored value to show: the server does not
              say whether a client secret is saved, so a blank box keeps whatever is (mcpForm sends
              it only when typed). */}
          <Field label={t('Client secret')}>
            {id => (
              <SecretInput
                id={id}
                value={state.oauthClientSecret}
                onChange={v => update({ oauthClientSecret: v })}
                placeholder={t('Optional')}
                labels={secretLabels}
              />
            )}
          </Field>
        </FormColumns>
        <Field label={t('Scope')}>
          {id => <Input id={id} value={state.oauthScope} onChange={e => update({ oauthScope: e.target.value })} placeholder={t('Optional')} autoComplete="off" />}
        </Field>
        <Caption>
          {t('Most servers register Proxyma by themselves. Enter a client only if the server asks for one; its redirect URL is {url}.', { url: window.location.origin + CALLBACK_PATH })}
        </Caption>
      </div>
    </Presence>
  </div>)
}

// Name and value rows. A name the server already holds a value for shows that a value is saved
// rather than an empty box that reads as "none".
function NamedValues({ rows, onChange, namePlaceholder, addLabel, removeLabel }: {
  rows: NamedValue[]
  onChange: (rows: NamedValue[]) => void
  namePlaceholder: string
  addLabel: string
  removeLabel: string
}) {
  const { t } = useTranslation()
  const set = (i: number, patch: Partial<NamedValue>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  return (
    <ListEditor
      items={rows}
      addLabel={addLabel}
      removeLabel={removeLabel}
      onAdd={() => onChange([...rows, { name: '', value: '', stored: false }])}
      onRemove={i => onChange(rows.filter((_, j) => j !== i))}
      renderRow={(row, i) => (<>
        <Input
          className="flex-1 min-w-0 font-mono"
          aria-label={t('Name')}
          value={row.name}
          // A saved name is fixed: renaming it would carry its value to a name it was not given for.
          disabled={row.stored}
          onChange={e => set(i, { name: e.target.value })}
          placeholder={namePlaceholder}
          autoComplete="off"
        />
        <Input
          className="flex-1 min-w-0"
          aria-label={t('Value')}
          type="password"
          value={row.value}
          onChange={e => set(i, { value: e.target.value })}
          placeholder={row.stored ? t('Saved - type to replace') : t('Value')}
          autoComplete="new-password"
        />
      </>)}
    />
  )
}
