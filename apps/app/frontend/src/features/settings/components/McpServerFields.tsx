import { useRef, useState, type FocusEvent, type KeyboardEvent, type ReactNode } from 'react'
import { Plus, X } from 'lucide-react'
import {
  Button, Caption, FadeSwap, Field, FormColumns, Input, ListEditor, Presence, SecretInput, Select, Textarea, Tooltip,
  iconButton,
} from '@/components/ui/kit'
import { EditableText } from '@/components/ui/EditableText'
import { SecretAutosave, quietly } from '@/components/ui/AutosaveFields'
import { useSecretLabels } from './settingsParts'
import { useTranslation } from '@/i18n'
import type { McpAuth, McpTransport } from '../services/connectionsApi'
import type { McpFormState, NamedValue } from '../utils/mcpForm'

// Where the sign-in comes back to, for a server that wants the client registered by hand.
const CALLBACK_PATH = '/api/v1/mcp-oauth/callback'

type Update = (patch: Partial<McpFormState>) => void
/**
 * An EXISTING server's change, saved on its own (ConnectionModal's edit view). May return a promise:
 * a refusal puts the field back, and the dialog says why.
 */
type Commit = (patch: Partial<McpFormState>) => void | Promise<unknown>

/**
 * WHERE an MCP server is: its transport, then its address - or, for a local one, the command that
 * starts it. The left column of ConnectionModal, under the name.
 *
 * <p>Adding a server, every control reports to `update` and the dialog's Add sends them all. Given
 * `onCommit` - an existing server - each one saves on its own: the transport as it is chosen, the
 * address, the command and the arguments click-to-edit, saved when they are left.
 *
 * <p>A local command is offered on Enterprise Server to a Super admin only (`localAllowed`): it runs
 * as the server process. The server refuses it to anyone else as well; hiding it is the courtesy.
 */
export function McpWhereFields({ state, update, onCommit, localAllowed }: {
  state: McpFormState
  update?: Update
  onCommit?: Commit
  localAllowed: boolean
}) {
  const { t } = useTranslation()
  const transports: { value: McpTransport; label: string }[] = [
    { value: 'STREAMABLE_HTTP', label: t('Streamable HTTP') },
    { value: 'SSE', label: t('Server-Sent Events (SSE)') },
    ...(localAllowed || state.transport === 'STDIO' ? [{ value: 'STDIO' as const, label: t('Local command (stdio)') }] : []),
  ]
  const change = (patch: Partial<McpFormState>) => (onCommit ? quietly(onCommit(patch)) : update?.(patch))
  const commandHint = t('A program on this computer, like npx, uvx or docker.')
  const urlHint = state.transport === 'SSE'
    ? t('The address of the SSE endpoint, often ending in /sse.')
    : t('The address of the MCP endpoint, often ending in /mcp.')
  const urlPlaceholder = state.transport === 'SSE' ? 'https://mcp.example.com/sse' : 'https://mcp.example.com/mcp'
  const argsPlaceholder = '-y\n@modelcontextprotocol/server-filesystem'
  // A block of its own with its own rhythm: it sits in a Presence in the form, so the form's own
  // spacing reaches it as ONE child.
  return (<div className="space-y-4">
    <Field label={t('Transport')}>
      {id => <Select id={id} value={state.transport} onChange={v => change({ transport: v })} options={transports} />}
    </Field>

    {/* A command or an address, faded across as the transport changes. */}
    <FadeSwap swapKey={state.transport === 'STDIO'}>
    {state.transport === 'STDIO' ? (
      <div className="space-y-4">
        <Field label={t('Command')} hint={commandHint}>
          {id => onCommit ? (
            <EditableText value={state.command} label={t('Command')} placeholder="npx" required={t('Required')}
              className="font-mono" onCommit={command => onCommit({ command })} />
          ) : (
            <Input id={id} value={state.command} onChange={e => update?.({ command: e.target.value })} placeholder="npx" autoComplete="off" />
          )}
        </Field>
        <Field label={t('Arguments')} hint={t('One per line.')}>
          {id => onCommit ? (
            <EditableText value={state.argsText} label={t('Arguments')} placeholder={argsPlaceholder} multiline
              className="font-mono" onCommit={argsText => onCommit({ argsText })} />
          ) : (
            <Textarea
              id={id}
              mono
              rows={3}
              value={state.argsText}
              onChange={e => update?.({ argsText: e.target.value })}
              placeholder={argsPlaceholder}
            />
          )}
        </Field>
      </div>
    ) : (
      <Field label={t('Server URL')} hint={urlHint}>
        {id => onCommit ? (
          <EditableText value={state.url} label={t('Server URL')} placeholder={urlPlaceholder} required={t('Required')}
            onCommit={url => onCommit({ url })} />
        ) : (
          <Input
            id={id}
            value={state.url}
            onChange={e => update?.({ url: e.target.value })}
            placeholder={urlPlaceholder}
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
 * and shares the stored-credential handling every connection has. `onCommit`, as McpWhereFields'.
 */
export function McpAccessFields(props: McpAccessProps) {
  // A command's variables or a server's sign-in, faded across as the transport changes.
  return <FadeSwap swapKey={props.state.transport === 'STDIO'}><McpAccessBody {...props} /></FadeSwap>
}

interface McpAccessProps {
  state: McpFormState
  update?: Update
  onCommit?: Commit
  tokenField: ReactNode
  editing: boolean
  signedIn: boolean
}

function McpAccessBody({ state, update, onCommit, tokenField, editing, signedIn }: McpAccessProps) {
  const { t } = useTranslation()
  const secretLabels = useSecretLabels()
  const change = (patch: Partial<McpFormState>) => (onCommit ? quietly(onCommit(patch)) : update?.(patch))

  if (state.transport === 'STDIO') {
    return (
      <Field label={t('Environment variables')} hint={t('Passed to the command. Leave empty to keep a saved value.')}>
        {onCommit ? (
          <SavedNamedValues
            rows={state.env}
            onCommit={env => onCommit({ env })}
            namePlaceholder="API_KEY"
            addLabel={t('Add variable')}
            removeLabel={t('Remove variable')}
          />
        ) : (
          <NamedValues
            rows={state.env}
            onChange={env => update?.({ env })}
            namePlaceholder="API_KEY"
            addLabel={t('Add variable')}
            removeLabel={t('Remove variable')}
          />
        )}
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
      {id => <Select id={id} value={state.auth} onChange={v => change({ auth: v })} options={auths} />}
    </Field>

    <Presence show={state.auth === 'BEARER'}>{tokenField}</Presence>

    <Presence show={state.auth === 'HEADERS'}>
      <Field label={t('Headers')} hint={t('Sent with every request. Leave empty to keep a saved value.')}>
        {onCommit ? (
          <SavedNamedValues
            rows={state.headers}
            onCommit={headers => onCommit({ headers })}
            namePlaceholder="X-Api-Key"
            addLabel={t('Add header')}
            removeLabel={t('Remove header')}
          />
        ) : (
          <NamedValues
            rows={state.headers}
            onChange={headers => update?.({ headers })}
            namePlaceholder="X-Api-Key"
            addLabel={t('Add header')}
            removeLabel={t('Remove header')}
          />
        )}
      </Field>
    </Presence>

    <Presence show={state.auth === 'OAUTH'}>
      <div className="space-y-3">
        <Caption>
          {!editing
            ? t('After saving, press Sign in on the connection.')
            : signedIn
              ? t('Signed in.')
              : t('Not signed in yet. Use Sign in on the connection list.')}
        </Caption>
        {/* What the client fields are for, before them (the field rule of 2026-10-29). */}
        <Caption>
          {t('Most servers need no client. Redirect URL: {url}.', { url: window.location.origin + CALLBACK_PATH })}
        </Caption>
        <FormColumns className="items-start">
          <Field label={t('Client ID')}>
            {id => onCommit ? (
              <EditableText value={state.oauthClientId} label={t('Client ID')} placeholder={t('Optional')}
                onCommit={oauthClientId => onCommit({ oauthClientId })} />
            ) : (
              <Input id={id} value={state.oauthClientId} onChange={e => update?.({ oauthClientId: e.target.value })} placeholder={t('Optional')} autoComplete="off" />
            )}
          </Field>
          {/* The kit's one secret control. It has no stored value to show: the server does not
              say whether a client secret is saved, so a blank box keeps whatever is (mcpForm sends
              it only when typed). On an existing server a typed one is saved when the box is left. */}
          <Field label={t('Client secret')}>
            {id => onCommit ? (
              <SecretAutosave
                id={id}
                onCommit={oauthClientSecret => onCommit({ oauthClientSecret })}
                placeholder={t('Optional')}
                labels={secretLabels}
              />
            ) : (
              <SecretInput
                id={id}
                value={state.oauthClientSecret}
                onChange={v => update?.({ oauthClientSecret: v })}
                placeholder={t('Optional')}
                labels={secretLabels}
              />
            )}
          </Field>
        </FormColumns>
        <Field label={t('Scope')}>
          {id => onCommit ? (
            <EditableText value={state.oauthScope} label={t('Scope')} placeholder={t('Optional')}
              onCommit={oauthScope => onCommit({ oauthScope })} />
          ) : (
            <Input id={id} value={state.oauthScope} onChange={e => update?.({ oauthScope: e.target.value })} placeholder={t('Optional')} autoComplete="off" />
          )}
        </Field>
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

/**
 * An EXISTING server's named values, saved row by row. A saved name is fixed (renaming it would carry
 * its value to a name it was not given for) and its value is never shown: typing one replaces it,
 * saved when the box is left. Remove saves at once. "Add" opens a row of two boxes, saved when the
 * row is left with both filled - the server keeps no name without a value - and dropped when it is
 * left empty.
 */
function SavedNamedValues({ rows, onCommit, namePlaceholder, addLabel, removeLabel }: {
  rows: NamedValue[]
  onCommit: (rows: NamedValue[]) => void | Promise<unknown>
  namePlaceholder: string
  addLabel: string
  removeLabel: string
}) {
  const { t } = useTranslation()
  const [adding, setAdding] = useState(false)
  return (
    <div className="space-y-2">
      {rows.map((row, i) => (
        <div key={row.name} className="flex items-center gap-2 animate-fade-in">
          <span className="flex-1 min-w-0 truncate font-mono text-sm text-ink-primary px-2">{row.name}</span>
          <SecretAutosave
            className="flex-1 min-w-0"
            onCommit={value => onCommit(rows.map((r, j) => (j === i ? { ...r, value } : r)))}
            placeholder={row.stored ? t('Saved - type to replace') : t('Value')}
            labels={{ reveal: t('Reveal value'), hide: t('Hide value'), show: t('Show value') }}
          />
          <Tooltip label={removeLabel}>
            <button
              type="button"
              aria-label={`${removeLabel}: ${row.name}`}
              className={iconButton('md', 'danger')}
              onClick={() => quietly(onCommit(rows.filter((_, j) => j !== i)))}
            >
              <X size={14} aria-hidden="true" />
            </button>
          </Tooltip>
        </div>
      ))}
      <Presence show={adding}>
        {adding && (
          <NewNamedValue
            namePlaceholder={namePlaceholder}
            taken={rows.map(r => r.name.trim())}
            onSave={(name, value) => onCommit([...rows, { name, value, stored: false }])}
            onClose={() => setAdding(false)}
          />
        )}
      </Presence>
      {!adding && (
        <Button variant="hyperlink" type="button" onClick={() => setAdding(true)}>
          <Plus size={14} aria-hidden="true" />
          {addLabel}
        </Button>
      )}
    </div>
  )
}

// A name and a value being added to an existing server: saved when the row is left with both, dropped
// when it is left empty or on Escape, and kept open with a note when only one of them is there.
function NewNamedValue({ namePlaceholder, taken, onSave, onClose }: {
  namePlaceholder: string
  taken: string[]
  onSave: (name: string, value: string) => void | Promise<unknown>
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [value, setValue] = useState('')
  const [note, setNote] = useState<string | null>(null)
  const saving = useRef(false)

  const leave = () => {
    const n = name.trim()
    if (!n && !value) { onClose(); return }
    if (!n || !value) { setNote(t('Needs a name and a value.')); return }
    if (taken.includes(n)) { setNote(t('That name is already saved.')); return }
    if (saving.current) return
    saving.current = true
    Promise.resolve(onSave(n, value)).then(onClose, () => { saving.current = false })
  }
  return (
    <div
      onBlur={(e: FocusEvent<HTMLDivElement>) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) leave() }}
      onKeyDown={(e: KeyboardEvent<HTMLDivElement>) => {
        if (e.nativeEvent.isComposing) return
        if (e.key === 'Enter') { e.preventDefault(); leave() }
        if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onClose() }
      }}
    >
      <div className="flex items-center gap-2">
        <Input
          className="flex-1 min-w-0 font-mono"
          aria-label={t('Name')}
          autoFocus
          value={name}
          onChange={e => { setName(e.target.value); setNote(null) }}
          placeholder={namePlaceholder}
          autoComplete="off"
        />
        <Input
          className="flex-1 min-w-0"
          aria-label={t('Value')}
          type="password"
          value={value}
          onChange={e => { setValue(e.target.value); setNote(null) }}
          placeholder={t('Value')}
          autoComplete="new-password"
        />
      </div>
      <Presence show={!!note}>
        <Caption tone="danger" className="mt-1">{note ?? ''}</Caption>
      </Presence>
    </div>
  )
}
