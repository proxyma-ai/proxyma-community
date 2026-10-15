import { useEffect } from 'react'
import { useAppConfig } from '@/hooks/useAppConfig'
import { useThemeStore } from '@/store/themeStore'

function applyFavicon(faviconUrl: string) {
  const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (link) link.href = faviconUrl
}

// Hydrates the deployment-wide tenant branding from /api/v1/config - the one public,
// unauthenticated bootstrap endpoint already fetched before login resolves, so branding is
// correct on the Login/Setup pages too (those routes sit outside AppShell, see Router.tsx).
// Mode is already applied synchronously at module load in themeStore.ts from localStorage;
// this only ever updates the branding half of the two-attribute scheme.
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const setBrandingFromServer = useThemeStore(s => s._setBrandingFromServer)

  const { data } = useAppConfig()

  useEffect(() => {
    if (data?.branding) {
      setBrandingFromServer(data.branding)
      applyFavicon(data.branding.faviconUrl)
    }
  }, [data?.branding, setBrandingFromServer])

  return <>{children}</>
}
