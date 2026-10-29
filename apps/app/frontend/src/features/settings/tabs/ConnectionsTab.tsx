import { useState, useEffect, useRef } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Trash2, Plus } from 'lucide-react'
import {
  Badge, FadeSwap, Button, Caption, EmptyState, ExternalLink, InlineNotice, List, ListRow, MetaList, Presence,
  RowActions, SettingsSection, SettingsSections, SwitchRow, safeExternalHref,
} from '@/components/ui/kit'
import { ProtocolMark } from '@/features/indexing/utils/sourceMarks'
import { useConnectorCatalog } from '@/features/indexing/hooks/useConnectorCatalog'
import { WriteToolConsentModal } from '../components/WriteToolConsentModal'
import { ConnectionModal } from '../components/ConnectionModal'
import {
  connectionsApi, connectionError, useConnections, serviceKeyProtocol, signOutConnection,
  startConnectionSignIn, type Connection, type ConnectionProtocol,
} from '../services/connectionsApi'
import { connectionTestMessage } from '../services/connectionTestMessage'
import { useServiceSetupMissing } from '@edition'
import { ListSkeleton, TitleAction, useResolved } from '../pages/shared'
import { useToastStore } from '@/store/toastStore'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { PagedItems } from '@/components/ui/ListPager'
import type { TabProps } from '../pages/shared'
import { RemoveConfirm } from '../components/settingsParts'

// - Connections tab -
//
// The caller's CONNECTIONS, built exactly like the AI Providers's provider list: any number per
// service, each a server (or a Microsoft 365 sign-in) with its own name, credential and Read/Write
// switches, and every source names the one it reads through. This replaced three places a server
// used to be defined - a default under each settings prefix, an "Other servers" list beside it and
// a per-source override - none of which had an id a source could point at, so a source on a second
// server was synced with the first server's token.
//
// Below the list: the network share account and the connector-management switch, which are not
// connections - one is the caller's Windows account and the other spans every source type.

// What WriteToolConsentModal is being asked to confirm on this tab: the connector-tools field to
// set and what to call it. Only connector management now - a connection's Write switch asks for
// the same consent inside ConnectionModal.
interface ConsentRequest {
  field: string
  label: string
}

// One connection. Shows what it is, where it points and what the agent may do with it; Test makes
// one authenticated call and shows the backend's own verdict, the way a provider's Test shows its
// model count. A Microsoft 365 connection has no secret to show - its credential IS a sign-in, so
// its row carries Sign in and Sign out instead.
//
// An MCP server's row says where it is (its address, or the command that starts it) and how it signs
// in. One that signs in with OAuth carries Sign in, which opens the server's own page in a new window
// (on Desktop, the browser) and reads the connection again every two seconds until the callback has
// recorded the sign-in - the window cannot tell this page itself.
function ConnectionRow({ connection, onEdit }: {
  connection: Connection
  onEdit: () => void
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const oauth = connection.authKind === 'OAUTH'
  // The deployment not set up for the service (Enterprise Server's Microsoft 365 app registration).
  const setupMissing = useServiceSetupMissing(oauth ? connection.protocol : null)
  const test = useMutation({ mutationFn: () => connectionsApi.test(connection.id) })
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: keys.connections() })
    // Sources name their connection, and removing one moves nothing - but a source list open
    // behind this dialog should not keep a name that no longer exists.
    queryClient.invalidateQueries({ queryKey: keys.connectorsByType() })
  }
  const remove = useMutation({
    mutationFn: () => connectionsApi.remove(connection.id),
    onSuccess: invalidate,
    // A connection in use is refused - "'Work' is used by 2 sources. Remove them from Sources
    // first." - and that sentence is the whole answer, so it is shown as written.
    onError: (e: unknown) => addToast(connectionError(e) ?? t('Could not delete this connection.'), 'danger'),
  })
  const signOut = useMutation({
    mutationFn: () => signOutConnection(connection),
    onSuccess: () => {
      invalidate()
      addToast(t('Signed out of {name}.', { name: connection.name }), 'success')
    },
    onError: () => addToast(t('Could not sign out of {name}.', { name: connection.name }), 'danger'),
  })

  const signedIn = connection.status === 'CONNECTED' || connection.status === 'NEEDS_RECONNECT'
  const notSetUp = !!setupMissing

  const mcp = connection.protocol === 'MCP_SERVER' ? connection.mcp ?? null : null
  const mcpOAuth = !!mcp && mcp.transport !== 'STDIO' && mcp.auth === 'OAUTH'
  // The sign-in page this row opened, and the connectedAt it is waiting to see change.
  const [mcpSignIn, setMcpSignIn] = useState<{ url: string; since: string | null } | null>(null)
  const startMcpSignIn = useMutation({
    mutationFn: () => connectionsApi.mcpSignIn(connection.id),
    onSuccess: url => {
      setMcpSignIn({ url, since: connection.connectedAt })
      // The address comes from the MCP server's own discovery document. The server refuses
      // anything but http(s) already; a page that opens it checks again (security review swx-11).
      const safe = safeExternalHref(url)
      if (safe) window.open(safe, '_blank', 'noopener')
    },
    onError: (e: unknown) => addToast(connectionError(e) ?? t('Could not start the sign-in.'), 'danger'),
  })
  useEffect(() => {
    if (!mcpSignIn) return
    if (mcp?.signedIn && connection.connectedAt !== mcpSignIn.since) {
      setMcpSignIn(null)
      addToast(t('Signed in to {name}.', { name: connection.name }), 'success')
      return
    }
    // Five minutes: the sign-in link itself is good for ten, but nobody waits that long on purpose.
    const started = Date.now()
    const timer = window.setInterval(() => {
      if (Date.now() - started > 5 * 60_000) { setMcpSignIn(null); return }
      queryClient.invalidateQueries({ queryKey: keys.connections() })
    }, 2000)
    return () => window.clearInterval(timer)
  }, [mcpSignIn, mcp?.signedIn, connection.connectedAt, connection.name, queryClient, addToast, t])

  // What the agent may do, in one phrase. Write is absent where the service has no write tools.
  const access = mcp
    ? (connection.allowRead ? t('agent may use, asking first') : t('agent has no access'))
    : connection.allowRead && connection.allowWrite
    ? t('agent may read and write')
    : connection.allowRead
      ? t('agent may read')
      : connection.allowWrite
        ? t('agent may write')
        : t('agent has no access')

  return (
    <>
      <ListRow
        // Stacked below sm, like a provider row: the buttons would otherwise squeeze the name out.
        className="flex-col sm:flex-row"
        // The service's mark leads the row, as a source's does on its card - the same mark, in the
        // same brand ink, at the row size. The service is still named in words beside the name.
        leading={<ProtocolMark protocol={connection.protocol} size={16} className="text-brand-legible" />}
        title={connection.name}
        meta={<>
          <Caption as="span">{connection.protocolLabel}</Caption>
          {/* Nothing is read through an MCP server by default, so it has no default to show. */}
          {connection.isDefault && !mcp && <Badge variant="outline" size="sm" label={t('Default')} />}
        </>}
        // TWO LINES: where it points, then what it signs in with and what the agent may do. They
        // were one truncated line, and the end it cut was the agent's access - the part that
        // differs between two connections to the same server. The address wraps rather than
        // truncating, so the whole of it is readable without a hover.
        caption={<>
          <p className="break-all">
            {oauth
              ? (connection.accountLabel
                ? t('Signed in as {account}', { account: connection.accountLabel })
                : t('Not signed in'))
              : mcp?.transport === 'STDIO'
                ? <span className="font-mono">{[mcp.command, ...mcp.args].join(' ')}</span>
                : connection.baseUrl}
          </p>
          {mcp ? (
            <MetaList items={[
              mcp.transport === 'STDIO' ? t('Local command') : mcp.transport === 'SSE' ? t('SSE') : t('Streamable HTTP'),
              mcp.transport !== 'STDIO' && mcp.auth === 'BEARER' && (connection.secretConfigured
                ? t('token {masked}', { masked: connection.secretMasked ?? '' })
                : t('no token')),
              mcp.transport !== 'STDIO' && mcp.auth === 'HEADERS'
                && t('1 header|{count} headers', { count: mcp.headerNames.length }),
              mcpOAuth && (mcp.signedIn ? t('signed in') : t('not signed in')),
              mcp.transport === 'STDIO' && mcp.envNames.length > 0
                && t('1 variable|{count} variables', { count: mcp.envNames.length }),
              mcp.shared && t('shared with everyone'),
              access,
            ]} />
          ) : (
            <MetaList items={[
              connection.authKind === 'TOKEN' && (connection.secretConfigured
                ? t('token {masked}', { masked: connection.secretMasked ?? '' })
                : t('no token')),
              connection.authKind === 'BASIC' && (connection.username || t('no username')),
              connection.authKind === 'BASIC' && !connection.secretConfigured && t('no password'),
              access,
            ]} />
          )}
          <Presence show={!!mcpSignIn}>
            {mcpSignIn && (
              <InlineNotice tone="info" className="mt-1">
                {t('Finish signing in in the window that opened.')}{' '}
                <ExternalLink href={mcpSignIn.url}>{t('Open it again')}</ExternalLink>
              </InlineNotice>
            )}
          </Presence>
          <Presence show={connection.status === 'NEEDS_RECONNECT'}>
            <InlineNotice tone="warning" icon={AlertTriangle} className="mt-1">
              {mcp ? t('Sign in again to use its tools.') : t('Sign in again to keep its sources syncing.')}
              {/* The reason is the backend's - an expired grant, a revoked consent, a changed client
                  id - and it is what tells the user whether signing in again will help. */}
              {connection.statusDetail && <span className="text-ink-caption"> {connection.statusDetail}</span>}
            </InlineNotice>
          </Presence>
          <Presence show={notSetUp}>
            <Caption className="mt-1">{setupMissing}</Caption>
          </Presence>
          <Presence show={!!test.isSuccess}>
            {test.isSuccess && <InlineNotice tone={test.data.ok ? 'success' : 'danger'} className="mt-1">{connectionTestMessage(test.data, t)}</InlineNotice>}
          </Presence>
          <Presence show={!!test.isError}>
            <InlineNotice tone="danger" className="mt-1">{connectionError(test.error) ?? t('Could not test this connection.')}</InlineNotice>
          </Presence>
        </>}
        action={
          <RowActions
            className="flex-wrap"
            // Quiet danger, set apart from the safe actions - it was a third identical outline
            // button beside Test and Edit. The confirmation carries the danger fill.
            destructive={
              <Button variant="danger-quiet" onClick={() => setConfirmDelete(true)} disabled={remove.isPending}>
                <Trash2 size={14} />
                {t('Delete')}
              </Button>
            }
          >
            {/* Sign in NAVIGATES - see connectionSignInUrl. Offered only where the deployment is set
                up: without Microsoft 365's app registration it turns straight round and reports
                `config`. */}
            {/* Each eases in and out of the row as the sign-in state changes (Presence, fade only -
                it is a row of buttons). */}
            <Presence show={oauth && !notSetUp} collapse={false}>
              <Button variant={signedIn ? 'secondary' : 'primary'} onClick={() => startConnectionSignIn(connection)}>
                {signedIn ? t('Sign in again') : t('Sign in')}
              </Button>
            </Presence>
            <Presence show={oauth && signedIn} collapse={false}>
              <Button variant="secondary" onClick={() => signOut.mutate()} disabled={signOut.isPending}>
                {signOut.isPending ? t('Signing out...') : t('Sign out')}
              </Button>
            </Presence>
            <Presence show={mcpOAuth} collapse={false}>
              <Button
                variant={mcp?.signedIn ? 'secondary' : 'primary'}
                onClick={() => startMcpSignIn.mutate()}
                disabled={startMcpSignIn.isPending}
              >
                {mcp?.signedIn ? t('Sign in again') : t('Sign in')}
              </Button>
            </Presence>
            <Button variant="secondary" onClick={() => test.mutate()} disabled={test.isPending}>
              {test.isPending ? t('Testing...') : t('Test')}
            </Button>
            <Button variant="secondary" onClick={onEdit}>{t('Edit')}</Button>
          </RowActions>
        }
      />
      {confirmDelete && (
        <RemoveConfirm
          name={connection.name}
          title={t('Delete connection')}
          verb={t('delete')}
          confirmLabel={t('Delete')}
          consequences={[
            t('Its credential and Read/Write switches are deleted with it.'),
            t('A connection still used by a source cannot be deleted - remove or move those sources first.'),
          ]}
          onConfirm={() => { remove.mutate(); setConfirmDelete(false) }}
          onCancel={() => setConfirmDelete(false)}
        />
      )}
    </>
  )
}

// Adding and removing SOURCES, which is one permission across every connector type - files and
// web pages included - and so belongs beside the connection list rather than inside any one entry.
//
// It had no control anywhere until 0.2.3, while defaulting to off and gating fourteen tools. The
// only way to grant it was to ask the agent to do something that needed it and accept the prompt
// that appeared in chat: a user who had turned on every Write toggle this page offers was still
// told, mid-conversation, to enable a permission this page had never shown them. That reads as
// the setting not having worked.
//
// Turning it ON goes through the same consent modal as a connection's Write switch, for the same
// reason: it authorizes removing a source, and a removal takes its index with it.
//
// `focused` is `app://settings/connectors/connector-management`, the link a tool that needs this
// switch hands the agent - the row is scrolled to rather than left below the fold.
function ConnectorManagementRow({ pending, trackChange, onRequestEnable, focused }: {
  onRequestEnable: (request: ConsentRequest) => void
  focused?: boolean
} & TabProps) {
  const { t } = useTranslation()
  const ref = useRef<HTMLDivElement>(null)
  const checked = useResolved('connector-tools', 'connector-management', pending, 'false') === 'true'

  useEffect(() => {
    if (focused) ref.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [focused])

  return (
    <div ref={ref}>
      <SettingsSection title={t('Sources')} gap="list">
        <SwitchRow
          className="py-0"
          title={t('Add and remove sources')}
          caption={t('Let the agent add and remove sources for you; removing one deletes its index.')}
          checked={checked}
          onChange={v => {
            if (v) {
              onRequestEnable({ field: 'connector-management', label: t('Connector Management') })
            } else {
              trackChange('connector-tools', 'connector-management', 'false')
            }
          }}
        />
      </SettingsSection>
    </div>
  )
}

// A connection id is a UUID; anything else in the focus segment is a service key or a section.
const CONNECTION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// `focusKey` is the target named by the link that opened this tab - an
// `app://settings/connectors/<focus>` href relayed from a tool error (see SettingsLinks.java), or a
// caller elsewhere in the app. Four shapes:
//
//   <connection id>        that connection's edit modal - "Allow agent to write" is off on it
//   jira | confluence |    the service's default connection, to fix its credential; with none, the
//   bitbucket | svn        Add modal preset to that service. `microsoft` is the same for Microsoft
//                          365, except that an existing one only lands on the list: its fix is the
//                          row's Sign in, not a form
//   connector-management   that switch, scrolled to
//   smb                    the network share account, expanded
//
// An unrecognized value, or a service this deployment does not offer, simply matches nothing and
// the tab renders normally.
export function ConnectorsTab({ trackChange, pending, focusKey }: {
  focusKey?: string | null
} & TabProps) {
  const { t } = useTranslation()
  const [consent, setConsent] = useState<ConsentRequest | null>(null)
  const { data: connections = [], isLoading } = useConnections()
  const { catalog: { protocols }, isLoading: protocolsLoading } = useConnectorCatalog()
  // null = closed; undefined = open for a NEW connection; a connection = open for editing it.
  const [editing, setEditing] = useState<Connection | null | undefined>(null)
  const [presetProtocol, setPresetProtocol] = useState<ConnectionProtocol | undefined>(undefined)

  // Consumed once per link, and only after both lists have answered - deciding between "edit the
  // default" and "add one" before the list arrives would always pick "add one".
  const handledFocus = useRef<string | null>(null)
  useEffect(() => {
    if (!focusKey || isLoading || protocolsLoading || handledFocus.current === focusKey) return
    handledFocus.current = focusKey
    if (CONNECTION_ID.test(focusKey)) {
      const target = connections.find(c => c.id.toLowerCase() === focusKey.toLowerCase())
      if (target) setEditing(target)
      return
    }
    const protocol = serviceKeyProtocol(focusKey)
    if (!protocols.some(p => p.protocol === protocol)) return
    const ofService = connections.filter(c => c.protocol === protocol)
    if (ofService.length === 0) {
      setPresetProtocol(protocol)
      setEditing(undefined)
    } else if (!protocols.find(p => p.protocol === protocol)?.authMethods.every(m => m.kind === 'OAUTH2_CODE')) {
      setEditing(ofService.find(c => c.isDefault) ?? ofService[0])
    }
  }, [focusKey, isLoading, protocolsLoading, connections, protocols])

  const openNew = () => {
    setPresetProtocol(undefined)
    setEditing(undefined)
  }

  return (
    <SettingsSections intro={t('The servers your sources and the agent reach, each with your credential.')}>
      {/* Tour anchor on the CONNECTION LIST, not on the paragraph above it. The walkthrough step
          is called "Connect your tools" and talks about tokens and permissions; highlighting the
          sentence that introduces them left the reader looking at a caption while the card beside
          it described Jira, Confluence and Bitbucket - none of which were inside the highlight.

          The Add button sits on the title's row once there is a list (TitleAction), and under the
          empty state's sentence while there is none - one action on screen either way, never two. */}
      <div data-tour-id="tour-settings-connectors">
        <TitleAction>
          <Presence show={!isLoading && connections.length > 0} collapse={false}>
            <Button onClick={openNew}><Plus size={14} />{t('Add connection')}</Button>
          </Presence>
        </TitleAction>
        <FadeSwap swapKey={isLoading ? 'loading' : connections.length > 0}>
        {isLoading ? (
          <ListSkeleton count={2} />
        ) : connections.length === 0 ? (
          <EmptyState
            title={t('No connections yet.')}
            action={<Button onClick={openNew}><Plus size={14} />{t('Add connection')}</Button>}
          >
            {t('Every source on a server or a service reads through one. Add the one you have an account on.')}
          </EmptyState>
        ) : (
          // Twenty a page (LIST_PAGE_SIZE), in the browser: the pickers read every connection.
          <PagedItems items={connections}>
            {page => (
              <List>
                {page.map(c => (
                  <ConnectionRow key={c.id} connection={c} onEdit={() => setEditing(c)} />
                ))}
              </List>
            )}
          </PagedItems>
        )}
        </FadeSwap>
      </div>

      <ConnectorManagementRow
        pending={pending}
        trackChange={trackChange}
        onRequestEnable={setConsent}
        focused={focusKey === 'connector-management'}
      />

      {editing !== null && (
        <ConnectionModal
          connection={editing ?? null}
          presetProtocol={presetProtocol}
          onClose={() => setEditing(null)}
        />
      )}
      {consent && (
        <WriteToolConsentModal
          connectorLabel={consent.label}
          onAccept={() => {
            trackChange('connector-tools', consent.field, 'true')
            setConsent(null)
          }}
          onCancel={() => setConsent(null)}
        />
      )}
    </SettingsSections>
  )
}

