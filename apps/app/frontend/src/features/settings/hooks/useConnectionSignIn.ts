import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { create } from 'zustand'
import { safeExternalHref } from '@/components/ui/kit'
import { FEATURES } from '@/config/features'
import { EDITION_SIGN_INS } from '@edition'
import { keys } from '@/services/queryKeys'
import { useToastStore } from '@/store/toastStore'
import { useTranslation } from '@/i18n'
import { connectionError, connectionsApi, startConnectionSignIn, type Connection } from '../services/connectionsApi'
import { SIGN_IN_POLL_MS, signInRoute, signInWaitState, type SignInWait } from '../utils/signInFlow'
import { disclosureBeforeSignIn, type SignInDisclosure } from '../utils/signInDisclosure'

/**
 * SIGNING A CONNECTION IN LIKE ANY OTHER APP (2026-10-31): on Desktop and Home Server the service's
 * sign-in opens in the person's browser and Proxyma updates by itself once it is done
 * (signInFlow.ts says which edition goes which way, OAuth2ConnectionService why it is safe).
 *
 * The waits live in one store, keyed by connection, so a sign-in started from the connection
 * dialog - which closes - is still shown, and watched, on the connection's row and in a source's
 * connection picker. Whichever of them sees it finish clears it and says so, once.
 */
interface SignInWaitStore {
  waits: Record<string, SignInWait>
  begin: (id: string, wait: SignInWait) => void
  /** Ends a wait; true for the one call that found it still there. */
  end: (id: string) => boolean
}

const useSignInWaitStore = create<SignInWaitStore>((set, get) => ({
  waits: {},
  begin: (id, wait) => set(s => ({ waits: { ...s.waits, [id]: wait } })),
  end: id => {
    if (!get().waits[id]) return false
    set(s => {
      const rest = { ...s.waits }
      delete rest[id]
      return { waits: rest }
    })
    return true
  },
}))

type SignInTarget = Pick<Connection, 'id' | 'protocol' | 'connectedAt'> & Partial<Pick<Connection, 'signInFailure'>>

/**
 * A sign-in waiting on its disclosure (signInDisclosure.ts): what the dialog shows, and what its two
 * buttons do. One at a time - SignInDisclosureDialog, mounted once in the app shell, draws it.
 */
interface DisclosureStore {
  pending: { kind: SignInDisclosure; proceed: () => void; cancel: () => void } | null
  ask: (pending: NonNullable<DisclosureStore['pending']>) => void
  clear: () => void
}

export const useSignInDisclosureStore = create<DisclosureStore>(set => ({
  pending: null,
  ask: pending => set({ pending }),
  clear: () => set({ pending: null }),
}))

/** Starts a connection's sign-in the way this edition does it - after its disclosure, where it has one. */
export function useStartConnectionSignIn() {
  const start = useStartSignInNow()
  const ask = useSignInDisclosureStore(s => s.ask)
  const clear = useSignInDisclosureStore(s => s.clear)
  return (connection: SignInTarget): Promise<void> => {
    const kind = disclosureBeforeSignIn(connection.protocol)
    if (!kind) return start(connection)
    return new Promise<void>(resolve => {
      ask({
        kind,
        proceed: () => { clear(); void start(connection).finally(resolve) },
        cancel: () => { clear(); resolve() },
      })
    })
  }
}

function useStartSignInNow() {
  const { t } = useTranslation()
  const addToast = useToastStore(s => s.addToast)
  const begin = useSignInWaitStore(s => s.begin)
  return (connection: SignInTarget): Promise<void> => {
    const route = signInRoute({ multiUser: FEATURES.auth, editionOwn: !!EDITION_SIGN_INS[connection.protocol] })
    if (route === 'navigate') {
      startConnectionSignIn(connection)
      return Promise.resolve()
    }
    return connectionsApi.beginSignIn(connection.id).then(url => {
      // The address is the service's sign-in page, which the server built from the service's own
      // descriptor; a page that opens an address checks it all the same (security review swx-11).
      const safe = safeExternalHref(url)
      if (!safe) {
        addToast(t('Could not start the sign-in.'), 'danger')
        return
      }
      begin(connection.id, {
        startedAt: Date.now(), since: connection.connectedAt ?? null, url: safe,
        failedSince: connection.signInFailure?.at ?? null,
      })
      // The Desktop window hands an address of another origin to the system browser; a browser opens a tab.
      window.open(safe, '_blank', 'noopener')
    }).catch((e: unknown) => {
      addToast(connectionError(e) ?? t('Could not start the sign-in.'), 'danger')
    })
  }
}

/**
 * One connection's sign-in in the browser, if one is under way: watched until it reads signed in
 * (a success notice), or five minutes pass. `cancel` stops watching - the browser tab stays theirs.
 */
export function useConnectionSignInWait(
  connection: Pick<Connection, 'id' | 'name' | 'status' | 'connectedAt'> & Partial<Pick<Connection, 'signInFailure'>> | undefined,
) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const addToast = useToastStore(s => s.addToast)
  const id = connection?.id ?? ''
  const wait = useSignInWaitStore(s => (id ? s.waits[id] : undefined))
  const end = useSignInWaitStore(s => s.end)
  const status = connection?.status ?? null
  const connectedAt = connection?.connectedAt ?? null
  const name = connection?.name ?? ''
  const failure = connection?.signInFailure ?? null

  useEffect(() => {
    if (!wait || !id) return
    const check = () => {
      const state = signInWaitState(wait, { status, connectedAt, signInFailure: failure }, Date.now())
      if (state === 'done') {
        if (end(id)) {
          addToast(t('Signed in to {name}.', { name }), 'success')
          // A source names the connection it reads through, and may have been waiting on it.
          queryClient.invalidateQueries({ queryKey: keys.connectorsByType() })
        }
        return true
      }
      if (state === 'incomplete') {
        if (end(id)) {
          // The connection's row says what was left out, beside "Sign in again" (its statusDetail).
          addToast(t('Signed in to {name}, but a permission Proxyma needs was not allowed. Sign in again and leave every permission ticked.', { name }), 'warning')
          queryClient.invalidateQueries({ queryKey: keys.connectorsByType() })
        }
        return true
      }
      if (state === 'refused') {
        // Nothing changed on the connection: a grant it held before still works.
        if (end(id)) addToast(t('You did not allow Proxyma on the sign-in page, so {name} was not signed in.', { name }), 'warning')
        return true
      }
      if (state === 'failed') {
        if (end(id)) addToast(t('The sign-in to {name} did not finish. Try again.', { name }), 'danger')
        return true
      }
      if (state === 'timeout') {
        end(id)
        return true
      }
      return false
    }
    if (check()) return
    const timer = window.setInterval(() => {
      if (check()) return
      queryClient.invalidateQueries({ queryKey: keys.connections() })
    }, SIGN_IN_POLL_MS)
    return () => window.clearInterval(timer)
  }, [wait, id, status, connectedAt, failure, name, end, addToast, queryClient, t])

  return { wait, cancel: () => { if (id) end(id) } }
}
