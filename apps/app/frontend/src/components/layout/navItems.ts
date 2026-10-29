import { useLocation } from 'react-router-dom'
import { BarChart3, Database, MessageSquare, Orbit, ShieldCheck, type LucideIcon } from 'lucide-react'
import { useAuthStore } from '@/store/authStore'
import { useConstellationsShown, useKeyAccountsShown } from '@edition'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'

type NavMinRole = false | 'viewer' | 'admin'

// Settings is in a MENU in both editions and not in this list - UserMenu on server, AppMenu on
// desktop - and, since 2026-10-29, a row at the foot of the sidebar. It is account-level
// configuration, not a workspace destination people jump to repeatedly during a session.
//
// Sources is 'viewer' (any logged-in role, not just ADMIN/SUPER_ADMIN) - VIEWERs register and manage
// their own sources too. "Sources", not "Connectors": Settings has a Connections tab, and two
// destinations with one name is one too many.
//
// Key accounts (docs/architecture.html#spec-11) is an Enterprise Server page: signed in, and only
// while the license status says `keyAccountsAllowed`. Constellations is gated the same way, on
// `constellationsAllowed`. Every gate here is a convenience - the route and every endpoint refuse
// without it.
const NAV_ITEMS: ReadonlyArray<{ to: string; label: string; icon: LucideIcon; auth: NavMinRole; licensed?: 'keyAccounts' | 'constellations' }> = [
  { to: '/',               label: 'Chat',           icon: MessageSquare, auth: false    },
  { to: '/sources',        label: 'Sources',        icon: Database,      auth: 'viewer' },
  { to: '/key-accounts',   label: 'Key accounts',   icon: BarChart3,     auth: 'viewer', licensed: 'keyAccounts' },
  { to: '/constellations', label: 'Constellations', icon: Orbit,         auth: 'viewer', licensed: 'constellations' },
  { to: '/admin',          label: 'Administration', icon: ShieldCheck,   auth: 'admin'  },
]

export interface NavItem { to: string; label: string; icon: LucideIcon; isActive: boolean }

/**
 * The app's pages this reader may open, in order, each translated and marked when it is the page
 * on screen - one list for the three places that draw it: the header's words, the phone menu's rows
 * and the sidebar's rows.
 */
export function useNavItems(): NavItem[] {
  const location = useLocation()
  const user = useAuthStore(s => s.user)
  const isAdmin = user?.role === 'SUPER_ADMIN' || user?.role === 'ADMIN'
  const keyAccountsAllowed = useKeyAccountsShown()
  const constellationsShown = useConstellationsShown()
  const { t } = useTranslation()
  return NAV_ITEMS.filter(item => {
    // Administration is server-only, and hidden here AND guarded on the route (RequireServerMode in
    // Router) - a nav item is a convenience, not an access control.
    if (item.auth === 'admin' && !FEATURES.auth) return false
    if (item.auth === 'admin' && FEATURES.auth && !isAdmin) return false
    if (item.auth === 'viewer' && FEATURES.auth && !user) return false
    if (item.licensed === 'keyAccounts' && !keyAccountsAllowed) return false
    if (item.licensed === 'constellations' && !constellationsShown) return false
    return true
  }).map(item => ({
    to: item.to,
    icon: item.icon,
    label: t(item.label),
    isActive: item.to === '/'
      ? location.pathname === '/' || location.pathname.startsWith('/chat')
      : location.pathname.startsWith(item.to),
  }))
}
