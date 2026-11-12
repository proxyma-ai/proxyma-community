// WHAT THE DESKTOP WINDOW'S TITLE ROW SAYS ABOUT AN UPDATE (the maintainer, 2026-11-04: "after the
// app downloaded a new update, it will show a banner to restart to apply the update ... on the same
// row as the minimize/maximize/close buttons"). Until then only Settings > Updates showed a staged
// update, and nothing else in the app said one was waiting.
//
// Plain .ts with no JSX so `node --test` reaches it (updateNotice.test.ts). The component is
// features/system/components/UpdateNotice.tsx.
//
// - READY, on an installation that installs in place: "Restart to update", in the accent, opening
//   a small popover with Restart now and Later. Later hides it for this version until the app next
//   starts (session-only - the page reloads at every start) or a newer version is staged.
// - AVAILABLE (auto-update off): a quieter "Update available" opening Settings > Updates.
// - DOWNLOADING, CHECKING, UP_TO_DATE, ERROR: nothing. The Updates page shows a download's progress.
// - Only in Proxyma's own borderless window. A browser - a server's page, a desktop opened in a
//   browser - has no title row, and a server edition is updated by its operator (DETECT_ONLY,
//   canApplyInPlace false), so it shows nothing either.

/** What GET /api/v1/update/status says, as far as this decision reads it. */
export interface UpdateNoticeStatus {
  /** UP_TO_DATE | CHECKING | AVAILABLE | DOWNLOADING | READY | ERROR */
  status: string
  availableVersion: string
  canApplyInPlace: boolean
  /** PARTS | DETECT_ONLY */
  installKind: string
}

export interface UpdateNoticeInput {
  /** The page is in Proxyma's own window, which has a title row (useDesktopWindow). */
  inWindow: boolean
  /** The status, held through a check (heldStatus); undefined until it is first read. */
  update: UpdateNoticeStatus | undefined
  /** The version "Later" was pressed for in this session, else null. */
  dismissedVersion: string | null
  /** The window is wide enough for the control's words - below it, the icon alone with a tooltip. */
  wide: boolean
}

export type UpdateNotice =
  | { kind: 'none' }
  | { kind: 'ready' | 'available'; version: string; compact: boolean }

const NONE: UpdateNotice = { kind: 'none' }

export function updateNotice({ inWindow, update, dismissedVersion, wide }: UpdateNoticeInput): UpdateNotice {
  if (!inWindow || !update) return NONE
  // An installation the launcher did not start updates by downloading the new release, and a
  // server by its operator: nothing to restart into, and nothing for this row to offer.
  if (!update.canApplyInPlace || update.installKind === 'DETECT_ONLY') return NONE
  const version = update.availableVersion
  if (!version) return NONE
  const compact = !wide
  if (update.status === 'READY') {
    return version === dismissedVersion ? NONE : { kind: 'ready', version, compact }
  }
  if (update.status === 'AVAILABLE') return { kind: 'available', version, compact }
  return NONE
}

/**
 * The status to decide from, given the one last decided from and the one just read. A CHECK IS NOT
 * A CHANGE: a scheduled check flips READY to CHECKING and back while it asks GitHub, and the
 * control would leave the row and come back with every check. So CHECKING keeps what was there
 * before it; anything else is taken as it is.
 */
export function heldStatus<T extends UpdateNoticeStatus>(
  previous: T | undefined,
  next: T | undefined,
): T | undefined {
  if (!next) return previous
  if (next.status === 'CHECKING' && previous) return previous
  return next
}
