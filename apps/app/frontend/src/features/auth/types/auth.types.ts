export interface AuthUser {
  id: string
  email: string
  displayName: string
  role: string
  accountStatus: 'ACTIVE' | 'PENDING_DELETION'
  deletionRequestedAt: string | null
  // Which legal-document version this account accepted, and when. Both null for accounts created
  // before consent was collected - deliberately not backfilled, since they never accepted anything.
  legalVersionAccepted: string | null
  legalAcceptedAt: string | null
  // Whether the documents have changed since this account last accepted them, decided by the
  // BACKEND (LegalDocuments.isStale) and not recomputed here - see UserResponse for why. True is
  // what raises the re-consent gate; a null legalVersionAccepted is stale too, so an account
  // predating consent collection is asked for the first time.
  consentStale: boolean
  // The version the gate must echo back to clear itself.
  legalVersionRequired: string
}
