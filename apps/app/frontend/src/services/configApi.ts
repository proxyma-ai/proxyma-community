import { http } from './httpClient'
import type { ProductBranding } from '@/store/themeStore'

interface AppConfig {
  version: string
  serverPort: number
  platform: 'windows' | 'macos' | 'linux'
  branding: ProductBranding
  /**
   * The public ENTERPRISE sandbox: the server edition on a public address, writable, wiped every
   * night. SandboxGuardFilter is what limits it; this only drives its banner and the sign-in page's
   * account list. Absent everywhere else.
   *
   * A RUNTIME flag, not a build-time one, unlike the desktop/server split in FEATURES - the sandbox
   * is a deployment decision rather than an edition.
   */
  sandbox?: boolean
  /**
   * Whether a file source on this deployment is UPLOADED bytes (true) or a folder the user points
   * at (false). Runtime for the same reason `sandbox` is, and see useFileUpload for why it stopped
   * being derivable from the build: the home server is the desktop bundle and takes uploads.
   *
   * Absent on an older backend, where useFileUpload falls back to the build-time answer.
   */
  fileUpload?: boolean
  /**
   * This is the Home Server edition: the desktop build, run in the homeserver container. FEATURES
   * cannot tell the two apart because the bundle is the same, so the image says so at runtime.
   *
   * Absent on an older backend, which reads as Desktop - what About said before this existed.
   */
  homeServer?: boolean
  // An edition may add switches of its own (PublicConfigContributor on the server) - Enterprise
  // Server's Microsoft 365 source switches. What the Connect menu offers comes from the connector
  // catalog, so nothing here reads them.
}

export const configApi = {
  get: () => http.get<AppConfig>('/api/v1/config').then(r => r.data),
}
