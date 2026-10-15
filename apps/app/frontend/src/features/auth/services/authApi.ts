import { http } from '@/services/httpClient'
import type { AuthUser } from '../types/auth.types'

export interface AuthProviders {
  password: boolean
  oauth: string[]
  ssoDisplayName: string
  passwordDisabledReason: string | null
  // Whether people may create their own accounts here (PROXYMA_SELF_REGISTRATION, off by default).
  selfRegistration: boolean
  // Whether "Forgot password?" can email a link; without mail a super admin makes one instead.
  passwordResetByEmail: boolean
}

export interface SetupStatus {
  needed: boolean
}

export interface DeletionRequestResponse {
  ok: boolean
  mode: 'grace-period' | 'sso-data-only'
  graceDays: number
}

// Served by LegalDocumentController. `version` is echoed back verbatim on signup - the backend
// refuses a signup that names anything other than the version currently in force, which is what
// stops a stale tab from recording acceptance of terms it never displayed.
export interface ConsentRequirements {
  version: string
  termsUrl: string
  privacyUrl: string
  eulaUrl: string
}

// What the user ticked. `acceptedLegalVersion` carries the required consent; omitting it is how a
// client says "not accepted", and the backend then refuses to create the account at all.
export interface ConsentPayload {
  acceptedLegalVersion: string
}

/** The public sandbox's shared accounts and their published password - see SandboxController. */
export interface SandboxInfo {
  password: string | null
  accounts: { email: string; displayName: string; role: 'SUPER_ADMIN' | 'ADMIN' | 'VIEWER' }[]
}

export const authApi = {
  sandbox: () => http.get<SandboxInfo>('/api/v1/auth/sandbox').then(r => r.data),
  me: () => http.get<AuthUser>('/api/v1/auth/me').then(r => r.data),
  logout: () => http.post('/api/v1/auth/logout'),
  providers: () => http.get<AuthProviders>('/api/v1/auth/providers').then(r => r.data),
  consentRequirements: () =>
    http.get<ConsentRequirements>('/api/v1/legal/consent-requirements').then(r => r.data),
  // Clears the re-consent gate for the signed-in account. Same echo-the-version rule as signup:
  // the backend refuses anything other than the version in force. Returns the updated user, so the
  // caller can seed the ['me'] cache with a row that is no longer stale rather than re-fetching.
  renewConsent: (acceptedLegalVersion: string) =>
    http.post<AuthUser>('/api/v1/auth/me/consent', { acceptedLegalVersion }).then(r => r.data),
  login: (email: string, password: string) =>
    http.post<AuthUser>('/api/v1/auth/login', { email, password }).then(r => r.data),
  register: (email: string, displayName: string, password: string, consent: ConsentPayload) =>
    http.post<AuthUser>('/api/v1/auth/register', { email, displayName, password, ...consent }).then(r => r.data),
  setupStatus: () => http.get<SetupStatus>('/api/v1/setup/status').then(r => r.data),
  // setupToken: the one-time token the server printed in its log - see SetupToken on the server.
  setup: (setupToken: string, email: string, displayName: string, password: string, consent: ConsentPayload) =>
    http.post('/api/v1/setup', { setupToken, email, displayName, password, ...consent }).then(r => r.data),
  requestDeletion: () =>
    http.post<DeletionRequestResponse>('/api/v1/auth/account/delete-request').then(r => r.data),
  cancelDeletion: () => http.post('/api/v1/auth/account/delete-cancel'),
  deleteNow: () => http.post('/api/v1/auth/account/delete-now'),
  forgotPassword: (email: string) =>
    http.post('/api/v1/auth/forgot-password', { email }).then(r => r.data),
  resetPassword: (token: string, newPassword: string) =>
    http.post('/api/v1/auth/reset-password', { token, newPassword }).then(r => r.data),
}
