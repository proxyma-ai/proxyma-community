import { useState, useEffect } from 'react'
import {
  Callout, Field, FormStack, InlineCode, ListEditor, Presence, RadioGroupSkeleton, SettingsSection,
  SettingsSections,
} from '@/components/ui/kit'
import { EditableText } from '@/components/ui/EditableText'
import { useSettings } from '../hooks/useSettings'
import { useSettingGroup } from '../hooks/useSettingGroup'
import { TabFieldsSkeleton, useResolved, useSettingsSaver } from '../pages/shared'
import { FEATURES } from '@/config/features'
import { edition } from '@settings-pages'
import { useTranslation } from '@/i18n'
import type { TabProps } from '../pages/shared'
import { ChoiceField } from '../components/settingsParts'
import { SettingText } from '../components/SettingFields'

// Network tab: proxy-mode radio group + a conditional block of 3 inputs.
function NetworkTabSkeleton() {
  return (
    <div className="space-y-6">
      <RadioGroupSkeleton count={3} />
    </div>
  )
}

// - Network tab -

const PROXY_MODES = [
  { key: 'none',   label: 'No proxy' },
  { key: 'system', label: 'System proxy' },
  { key: 'custom', label: 'Custom proxy' },
]

export function NetworkTab({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const { isLoading } = useSettings('proxy')
  // THE MODE AND ITS ADDRESSES ARE ONE SETTING (the click-to-edit rule, 2026-11-02): "Custom proxy"
  // with no address yet, or an address under another mode, is not a route anybody chose - and each
  // half alone would have asked for a restart. So the section is a group, saved as one when the
  // person leaves it (or the tab, or the window).
  const group = useSettingGroup()
  const savedMode = useResolved('proxy', 'mode', pending, 'none')
  const savedHttp = useResolved('proxy', 'http-proxy', pending)
  const savedHttps = useResolved('proxy', 'https-proxy', pending)
  const savedNoProxy = useResolved('proxy', 'no-proxy', pending)
  const mode = group.value('proxy', 'mode', savedMode)
  const edit = (field: string, saved: string) => (value: string) => group.set({ prefix: 'proxy', field, value }, { saved })

  if (isLoading) return <NetworkTabSkeleton />

  return (
    <SettingsSections>
      {/* One sub-section: the mode and whatever it reveals belong together. The restart it needs is
          said here, and again by the banner once it is saved. */}
      <SettingsSection
        title={t('Proxy mode')}
        caption={t('How outgoing traffic is routed. Changes apply after Proxyma restarts.')}
      >
        <div className="space-y-6" {...group.bind}>
          <ChoiceField
            value={mode}
            onChange={edit('mode', savedMode)}
            options={PROXY_MODES}
          />

          <Presence show={mode === 'system'}>
            <Callout tone="neutral">
              {t('Uses proxy settings from environment variables:')} <InlineCode>HTTPS_PROXY</InlineCode>, <InlineCode>HTTP_PROXY</InlineCode>, <InlineCode>NO_PROXY</InlineCode>
            </Callout>
          </Presence>

          {/* Beside the 'system' notice above, and like it, eased in and out with the mode. */}
          <Presence show={mode === 'custom'}>
            <FormStack>
              <SettingText
                label={t('HTTPS proxy')}
                value={group.value('proxy', 'https-proxy', savedHttps)}
                onCommit={edit('https-proxy', savedHttps)}
                placeholder="http://proxy.example.com:8080"
              />
              <SettingText
                label={t('HTTP proxy')}
                value={group.value('proxy', 'http-proxy', savedHttp)}
                onCommit={edit('http-proxy', savedHttp)}
                placeholder="http://proxy.example.com:8080"
              />
              <SettingText
                label={t('No proxy')}
                hint={t('Comma-separated hosts that skip the proxy.')}
                value={group.value('proxy', 'no-proxy', savedNoProxy)}
                onCommit={edit('no-proxy', savedNoProxy)}
                placeholder="localhost,127.0.0.1,::1"
              />
            </FormStack>
          </Presence>
        </div>
      </SettingsSection>

      {/* Server only. A desktop machine behind a TLS-inspecting proxy already trusts that proxy's
          root through the OS trust store, which the JVM reads - so the field could only ever break
          a working install there. The desktop build does not contain it; see
          features/settings/pages/CaCertificateSection.{desktop,server}.tsx. */}
      <edition.CaCertificateSection trackChange={trackChange} pending={pending} />

      {/* The deployment's own registration for a service its users then sign in to - on Enterprise
          Server, Microsoft 365's app registration (super admin only). Nothing on Desktop and the
          Home Server. See features/settings/pages/types.ts. */}
      <edition.ServiceSetupSection trackChange={trackChange} pending={pending} />

      {/* Enterprise Server only: an optional ceiling on the file servers Network share connections
          may name. Desktop and Home Server have one user, whose connection is the whole answer. */}
      {FEATURES.auth && <NetworkShareServers trackChange={trackChange} pending={pending} />}
    </SettingsSections>
  )
}

// The file servers Network share CONNECTIONS may name - Enterprise Server's optional ceiling, since
// 2026-10-01, when a user's domain account became a connection and its server the one host its
// sources may reach (ConnectorConnectionService.fileServerAllowedByPolicy). Empty means no
// ceiling: each user may name any server for their own account. Listed, a connection naming
// another server is refused, and a source whose connection the list stops allowing stops syncing.
//
// On the Network tab, deployment-wide and super-admin-only (the `network-share` prefix is
// deliberately NOT user-scoped - SettingsController). One row per server with "Add server", and no
// sync interval: the six-hour default stays in the backend, and every source has Sync now.
//
// Each server is a text edited where it is read (EditableText), and the list is saved whole - the
// one setting it is, one server per line - when a server is left, added or removed.
function NetworkShareServers({ pending }: TabProps) {
  const { t } = useTranslation()
  const { save, readOnly } = useSettingsSaver()
  const { isLoading } = useSettings('network-share')
  const allowedHosts = useResolved('network-share', 'allowed-hosts', pending)
  // Rows are local, so an empty row just added survives until something is typed in it; the
  // setting only ever receives the non-blank ones, one per line - the format the backend reads.
  const [rows, setRows] = useState<string[] | null>(null)
  // The row just added, which opens ready to type in.
  const [added, setAdded] = useState<number | null>(null)
  useEffect(() => {
    if (rows === null && !isLoading) {
      setRows((allowedHosts ?? '').split(/\r?\n/).map(h => h.trim()).filter(Boolean))
    }
  }, [rows, isLoading, allowedHosts])

  if (isLoading || rows === null) return <TabFieldsSkeleton count={1} />

  // Saves the list as `next` has it; a refusal puts the rows back as they were (the toast says why).
  const commit = (next: string[]) => {
    const before = rows
    setRows(next)
    return save([{ prefix: 'network-share', field: 'allowed-hosts', value: next.map(h => h.trim()).filter(Boolean).join('\n') }])
      .catch(e => { setRows(before); throw e })
  }
  // A row left empty goes, as a note left empty does - it was never a server.
  const dropIfEmpty = (i: number, text: string) => {
    setAdded(null)
    if (!text.trim()) setRows(r => (r ?? []).filter((_, j) => j !== i))
  }

  return (
    <SettingsSection
      title={t('Allowed file servers')}
      caption={t('Optional. Network shares may only use these servers.')}
    >
      {/* One row per server, the kit's ListEditor as a provider's custom headers are: the server,
          then a remove X behind a tooltip, and the text action that adds a row under them. Each
          remove button is named by its own server. */}
      <Field hint={t('*.corp.example.com matches every server under that domain.')}>
        <ListEditor
          items={rows}
          renderRow={(host, i) => (
            <div className="flex-1 min-w-0">
              <EditableText
                value={host}
                onCommit={text => commit(rows.map((h, j) => (j === i ? text : h)))}
                onDone={text => dropIfEmpty(i, text)}
                onCancel={() => dropIfEmpty(i, host)}
                startOpen={added === i}
                editable={!readOnly}
                label={t('File server')}
                placeholder="fileserver.example.com"
                className="text-sm text-ink-primary"
              />
            </div>
          )}
          onAdd={() => { setAdded(rows.length); setRows([...rows, '']) }}
          onRemove={i => { void commit(rows.filter((_, j) => j !== i)).catch(() => undefined) }}
          addLabel={t('Add server')}
          removeLabel={host => t('Remove {name}', { name: host || t('File server') })}
          removeTooltip={t('Remove')}
        />
      </Field>
    </SettingsSection>
  )
}
