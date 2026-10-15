import { useState, useEffect } from 'react'
import {
  Callout, Field, FormStack, InlineCode, Input, ListEditor, Presence, RadioGroupSkeleton, SettingsSection,
  SettingsSections,
} from '@/components/ui/kit'
import { useSettings } from '../hooks/useSettings'
import { TabFieldsSkeleton, useResolved } from '../pages/shared'
import { FEATURES } from '@/config/features'
import { edition } from '@settings-pages'
import { useTranslation } from '@/i18n'
import type { TabProps } from '../pages/shared'
import { ChoiceField } from '../components/settingsParts'

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
  const mode = useResolved('proxy', 'mode', pending, 'none')
  const httpProxy = useResolved('proxy', 'http-proxy', pending)
  const httpsProxy = useResolved('proxy', 'https-proxy', pending)
  const noProxy = useResolved('proxy', 'no-proxy', pending)

  if (isLoading) return <NetworkTabSkeleton />

  return (
    <SettingsSections>
      {/* One sub-section: the mode and whatever it reveals belong together. */}
      <SettingsSection
        title={t('Proxy mode')}
        caption={t('Configure how external HTTP traffic (AI providers, Jira, Confluence, etc.) is routed. Changes require a restart.')}
      >
        <ChoiceField
          value={mode}
          onChange={v => trackChange('proxy', 'mode', v)}
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
            <Field label={t('HTTPS proxy')}>
              {id => (
                <Input
                  id={id}
                  value={httpsProxy}
                  onChange={e => trackChange('proxy', 'https-proxy', e.target.value)}
                  placeholder="http://proxy.example.com:8080"
                />
              )}
            </Field>
            <Field label={t('HTTP proxy')}>
              {id => (
                <Input
                  id={id}
                  value={httpProxy}
                  onChange={e => trackChange('proxy', 'http-proxy', e.target.value)}
                  placeholder="http://proxy.example.com:8080"
                />
              )}
            </Field>
            <Field label={t('No proxy')} hint={t('Comma-separated list of hosts that should bypass the proxy.')}>
              {id => (
                <Input
                  id={id}
                  value={noProxy}
                  onChange={e => trackChange('proxy', 'no-proxy', e.target.value)}
                  placeholder="localhost,127.0.0.1,::1"
                />
              )}
            </Field>
          </FormStack>
        </Presence>
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
function NetworkShareServers({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const { isLoading } = useSettings('network-share')
  const allowedHosts = useResolved('network-share', 'allowed-hosts', pending)
  // Rows are local, so an empty row just added survives until something is typed in it; the
  // setting only ever receives the non-blank ones, one per line - the format the backend reads.
  const [rows, setRows] = useState<string[] | null>(null)
  useEffect(() => {
    if (rows === null && !isLoading) {
      setRows((allowedHosts ?? '').split(/\r?\n/).map(h => h.trim()).filter(Boolean))
    }
  }, [rows, isLoading, allowedHosts])

  const commit = (next: string[]) => {
    setRows(next)
    trackChange('network-share', 'allowed-hosts', next.map(h => h.trim()).filter(Boolean).join('\n'))
  }

  if (isLoading || rows === null) return <TabFieldsSkeleton count={1} />
  return (
    <SettingsSection
      title={t('Allowed file servers')}
      caption={t('Optional. When any are listed, Network share connections may only name these servers. Leave empty to allow any server.')}
    >
      {/* One row per server, the kit's ListEditor as a provider's custom headers are: the field,
          then a remove X behind a tooltip, and the text action that adds a row under them. Each
          remove button is named by its own server. */}
      <Field hint={t('*.corp.example.com matches every server under that domain.')}>
        <ListEditor
          items={rows}
          renderRow={(host, i) => (
            <Input
              className="flex-1"
              value={host}
              onChange={e => commit(rows.map((h, j) => (j === i ? e.target.value : h)))}
              placeholder="fileserver.example.com"
              aria-label={t('File server')}
            />
          )}
          onAdd={() => setRows([...rows, ''])}
          onRemove={i => commit(rows.filter((_, j) => j !== i))}
          addLabel={t('Add server')}
          removeLabel={host => t('Remove {name}', { name: host || t('File server') })}
          removeTooltip={t('Remove')}
        />
      </Field>
    </SettingsSection>
  )
}
