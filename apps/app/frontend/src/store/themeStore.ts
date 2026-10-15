import { create } from 'zustand'
import {
  SYSTEM_DARK_QUERY,
  applyAppearance,
  applyAppearanceSmoothly,
  parseAppearance,
  resolveAppearance,
  type AppearancePreference,
} from '@/components/ui/kit'

// Wire shape returned by GET /api/v1/config's `branding` field - see ProductIdentity
// (proxyma-core-settings). There is exactly one product, so this carries only what the UI
// actually renders; it is not a tenant-skin mechanism and nothing about it is configurable at
// deploy time.
export interface ProductBranding {
  productName: string
  faviconUrl: string
}

export const DEFAULT_BRANDING: ProductBranding = {
  productName: 'Proxyma',
  faviconUrl: '/favicon.svg',
}

// The same key the two-way light/dark flip wrote, so an existing choice survives the upgrade:
// parseAppearance reads its `dark` as Gray. The pre-paint script (public/appearance-init.js) reads
// it too - keep the two in step.
const MODE_STORAGE_KEY = 'proxyma-theme-mode'

// System, when nothing is stored: follow the operating system, as every Proxyma app and the
// landing site do (decided 2026-09-25). A choice already stored is kept. The pre-paint script
// (public/appearance-init.js) repeats this default; change the two together.
const DEFAULT_PREFERENCE: AppearancePreference = 'system'

function readStoredPreference(): AppearancePreference {
  try {
    return parseAppearance(localStorage.getItem(MODE_STORAGE_KEY), DEFAULT_PREFERENCE)
  } catch {
    return DEFAULT_PREFERENCE
  }
}

function systemQuery(): MediaQueryList | null {
  return typeof window.matchMedia === 'function' ? window.matchMedia(SYSTEM_DARK_QUERY) : null
}

// data-mode is light or dark, and data-shade="black" is Dark's second attribute - see the kit's
// appearance.ts. (A `data-brand` attribute was written here for token blocks keyed off it; none
// has been since the tenant skin went, and it was dropped with the other tenant leftovers.)
// `smoothly` for a change while the page is open - one crossfade of the page (the kit's motion.ts);
// the startup application has nothing to fade from.
function applyPreference(preference: AppearancePreference, smoothly = false) {
  const html = document.documentElement
  const resolved = resolveAppearance(preference, systemQuery()?.matches ?? true)
  if (smoothly) applyAppearanceSmoothly(resolved)
  else applyAppearance(html, resolved)
}

interface ThemeState {
  branding: ProductBranding
  // Personal, per-browser preference - persisted to localStorage.
  preference: AppearancePreference
  setPreference: (preference: AppearancePreference) => void
  _setBrandingFromServer: (branding: ProductBranding) => void
}

const initialPreference = readStoredPreference()
applyPreference(initialPreference)

export const useThemeStore = create<ThemeState>(set => ({
  branding: DEFAULT_BRANDING,
  preference: initialPreference,
  setPreference: (preference) => {
    set(() => {
      applyPreference(preference, true)
      try { localStorage.setItem(MODE_STORAGE_KEY, preference) } catch { /* private browsing, ignore */ }
      return { preference }
    })
  },
  _setBrandingFromServer: (branding) => set({ branding }),
}))

// System follows the OS LIVE: switching the OS to dark at sunset turns an open window dark
// without a reload. Registered once for the page's lifetime, and a no-op for any other choice.
systemQuery()?.addEventListener('change', () => {
  const { preference } = useThemeStore.getState()
  if (preference === 'system') applyPreference(preference, true)
})

// `useIsDefaultTenant()` used to live here, returning a hardcoded `true`. Every `if (isDefault)`
// in the UI therefore had one reachable branch and one that could not run under any input -
// Button, Card, Badge, Input, GradientHeading, SecretInput, PasswordInput, Topbar, AppShell,
// ChatPage, SourcesPage, AdminPage and SettingsModal each carried a full second rendering
// path for a tenant mechanism that no longer exists. All of it is gone; the surviving branch is
// what the shared kit now defines once. Do not reintroduce a deploy-time theming override.
