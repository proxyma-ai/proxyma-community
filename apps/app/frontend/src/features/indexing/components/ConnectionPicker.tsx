import { useState } from 'react'
import { Plus } from 'lucide-react'
import { Button, Callout, EmptyState, FadeSwap, Field, Presence, Select } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { ConnectionModal } from '@/features/settings/components/ConnectionModal'
import {
  useConnections, type Connection, type ConnectionProtocol,
} from '@/features/settings/services/connectionsApi'
import { useServiceSetupMissing } from '@edition'
import { useConnectionSignInWait, useStartConnectionSignIn } from '@/features/settings/hooks/useConnectionSignIn'
import { useCatalogProtocol } from '../hooks/useConnectorCatalog'
import { serverText } from '@/i18n/serverText'

export interface ConnectionChoice {
  protocol: ConnectionProtocol | undefined
  /** The caller's connections of this protocol. */
  options: Connection[]
  /** What the source will read through: the one picked, else the default. */
  selected: Connection | undefined
  choose: (id: string) => void
  loading: boolean
  /**
   * Nothing to register against - no connection of this service at all, or one that signs in
   * (Microsoft 365, any OAuth service) nobody is signed in to. A source added then would sync
   * nothing and report it later, as a failure on a page the user is no longer looking at.
   */
  blocked: boolean
  /**
   * The caller has no connection of this service at all - not merely a signed-out one. The dialog
   * around the picker then offers "Add a connection" as its PRIMARY footer action (see
   * ConnectorConfigModal), since adding one is the only step forward.
   */
  none: boolean
  /**
   * The connection the source names was deleted (ConnectorConnectionService.delete keeps its sources).
   * Nothing is picked in its place - the default shown as chosen would read as the connection already in
   * use, and choosing it would change nothing - so the select waits for a choice and says why.
   */
  missing: boolean
  /** Opens the add-connection dialog. The picker renders it; any button may ask for it. */
  startAdding: () => void
  adding: boolean
  stopAdding: () => void
}

/**
 * Which of the caller's connections a source reads through. `protocol` undefined means the source
 * type needs none (folders, websites, network shares) and everything here stays idle.
 *
 * <p>Starts on `initial` (a source's current connection) or, when that is absent or has since
 * been deleted, on the service's default - the one the backend would use for a source that names
 * none, so what the picker shows is what registering would do anyway.
 *
 * <p>`sourceType` (the type's wire name) also lets in another service's connections that serve it -
 * one Atlassian Cloud sign-in reaches Jira and Confluence alike - listed after the type's own.
 */
export function useConnectionChoice(
  protocol: ConnectionProtocol | undefined, initial?: string | null, sourceType?: string,
): ConnectionChoice {
  const { data = [], isLoading } = useConnections(!!protocol)
  const [chosen, setChosen] = useState(initial ?? '')
  const [adding, setAdding] = useState(false)
  const options = protocol
    ? [
        ...data.filter(c => c.protocol === protocol),
        ...data.filter(c => c.protocol !== protocol && !!sourceType && !!c.sourceTypes?.includes(sourceType)),
      ]
    : []
  const loading = !!protocol && isLoading
  // A source's own connection, gone: no stand-in until another is chosen.
  const missing = !!protocol && !loading && !!initial && chosen === initial && !options.some(c => c.id === initial)
  const selected = missing ? undefined : options.find(c => c.id === chosen) ?? options.find(c => c.isDefault) ?? options[0]
  return {
    protocol,
    options,
    selected,
    choose: setChosen,
    loading,
    blocked: !!protocol && !loading
      && (!selected || (selected.authKind === 'OAUTH' && selected.status !== 'CONNECTED')),
    none: !!protocol && !loading && options.length === 0,
    missing,
    startAdding: () => setAdding(true),
    adding,
    stopAdding: () => setAdding(false),
  }
}

/**
 * The Connection field of Add Source and of a source's own settings: a select of the caller's
 * connections for this service with the default marked, and a way to add one without leaving the
 * dialog. With none at all it is one plain sentence, and the dialog around it withholds its submit
 * and puts "Add a connection" in the footer as the primary action - see ConnectionChoice.none.
 *
 * <p>NOT A WARNING, since 2026-09-30 (the maintainer's call on the UI review). Having no
 * connection yet is the normal first step of adding a Jira project, not something gone wrong, so
 * it lost the orange triangle and the centered notice, and the action moved from an outline
 * button in the body to where a dialog's next step always is.
 */
export function ConnectionPicker({ choice, disabled, addInFooter }: {
  choice: ConnectionChoice
  disabled?: boolean
  /** The dialog puts "Add a connection" in its footer when there is none, so the body does not. */
  addInFooter?: boolean
}) {
  const { t } = useTranslation()
  const info = useCatalogProtocol(choice.protocol)
  const selected = choice.selected
  const oauth = selected?.authKind === 'OAUTH'
  // A service whose deployment-wide setup a user can find missing here - Enterprise Server's
  // Microsoft 365 app registration; every other sign-in's client is the connection's own, or one an
  // administrator set (a failed start says so). Null when there is nothing missing.
  const setupMissing = useServiceSetupMissing(choice.protocol)
  // Nothing to pick for a type that reads through no connection, and nothing yet while the list is
  // on its way; the picker then eases in (and out) rather than appearing in one frame.
  const shown = !!choice.protocol && !choice.loading

  const service = info ? t(info.displayName) : (choice.protocol ?? '')
  const signedOut = !!selected && oauth && selected.status !== 'CONNECTED'
  // On Desktop and Home Server the sign-in opens in the browser; the picker watches for it to finish.
  const startSignIn = useStartConnectionSignIn()
  const browserSignIn = useConnectionSignInWait(oauth ? selected : undefined)

  const addLink = (
    <Button variant="hyperlink" onClick={choice.startAdding} disabled={disabled} className="inline-flex items-center gap-1">
      <Plus size={14} aria-hidden="true" />{t('Add a connection')}
    </Button>
  )

  return (
    <Presence show={shown}>
    <div className="space-y-3">
      {/* None yet, or the list to choose from - the one fades into the other when a connection is added. */}
      <FadeSwap swapKey={choice.options.length === 0 ? 'none' : 'list'}>
      {choice.options.length === 0 ? (
        <EmptyState className="py-0" action={!addInFooter ? addLink : undefined}>
          {t('No {service} connection yet.', { service })}
        </EmptyState>
      ) : (
        <>
          <Field
            label={t('Connection')}
            aside={addLink}
            hint={<span className="block truncate">{oauth ? (selected?.accountLabel ?? '') : (selected?.baseUrl ?? '')}</span>}
          >
            {id => (
              <Select
                id={id}
                value={selected?.id ?? ''}
                onChange={choice.choose}
                disabled={disabled}
                placeholder={t('Choose...')}
                options={choice.options.map(c => ({
                  value: c.id,
                  label: c.isDefault ? t('{name} (default)', { name: c.name }) : c.name,
                }))}
              />
            )}
          </Field>
          <Presence show={choice.missing} className="mt-3">
            <Callout tone="warning">{t('Its connection was deleted. Choose another one to sync it again.')}</Callout>
          </Presence>
          {/* Only a sign-in makes such a connection usable, and it is this user's to do - unless the
              deployment itself is not set up for the service, which only an administrator can fix. */}
          <Presence show={signedOut} className="mt-3">
            {signedOut && selected && (setupMissing ? (
              <Callout tone="warning">{setupMissing}</Callout>
            ) : (
              <Callout
                tone="warning"
                actions={
                  <Button variant="secondary" onClick={() => { void startSignIn(selected) }} disabled={disabled}>
                    {selected.status === 'NEEDS_RECONNECT' ? t('Sign in again') : t('Sign in')}
                  </Button>
                }
              >
                <p>
                  {selected.status === 'NEEDS_RECONNECT'
                    ? t('Sign in to {name} again.', { name: selected.name })
                    : t('Sign in to {name} first.', { name: selected.name })}
                </p>
                <Presence show={!!selected.statusDetail}>
                  <p className="mt-1 text-ink-caption">{serverText(selected.statusDetail)}</p>
                </Presence>
                <Presence show={!!browserSignIn.wait}>
                  <p className="mt-1 text-ink-caption">{t('Your browser opened for the sign-in.')}</p>
                </Presence>
              </Callout>
            ))}
          </Presence>
        </>
      )}
      </FadeSwap>
      {choice.adding && choice.protocol && (
        <ConnectionModal
          connection={null}
          presetProtocol={choice.protocol}
          onClose={choice.stopAdding}
          onSaved={saved => choice.choose(saved.id)}
        />
      )}
    </div>
    </Presence>
  )
}
