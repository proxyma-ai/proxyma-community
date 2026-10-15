import { Logo as KitLogo } from '@/components/ui/kit'
import { useThemeStore } from '@/store/themeStore'

interface LogoProps {
  /** Forwarded to the mark so the header can drive its idle rotation. */
  iconRef?: React.Ref<SVGSVGElement>
  size?: number
  /** Mark alone, for a surface that already names the product in its own copy. */
  markOnly?: boolean
  className?: string
}

/**
 * The kit's Logo, with the product name injected from the store.
 *
 * This wrapper is the only reason the app does not import the kit's Logo directly: the
 * product name here comes from `GET /api/v1/config` (ProductIdentity, proxyma-core-settings)
 * rather than being a constant, and threading `useThemeStore` through all six call sites to
 * pass it would be worse than one wrapper. The consoles and the marketing site have no such
 * endpoint and use the kit's 'Proxyma' default.
 */
export function Logo(props: LogoProps) {
  const productName = useThemeStore(s => s.branding.productName)
  return <KitLogo {...props} productName={productName} />
}
