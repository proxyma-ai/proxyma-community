import type { ComponentProps, HTMLAttributes } from 'react'
import { MODAL_SCRIM_DARK, SplashScreen, StatusScreen, cn } from '@/components/ui/kit'
import { ModalShell } from './ModalShell'

/**
 * The kit's `StatusScreen` - restarting, quitting, stopped, under maintenance - as a DIALOG LAYER.
 *
 * The screen is the kit's look; what this adds is the app's layer stack, which is what kept these
 * screens in control of the keyboard when each was its own `ModalShell`: Escape reaches nothing
 * beneath, Tab cannot leave, and `level` ranks the layer above dialogs opened after it (2 for the
 * restart and maintenance screens, which outrank the onboarding tour). No `onClose` anywhere - there
 * is nothing to dismiss.
 *
 * The layer's panel is a transparent full-window box rather than `contents`, because a
 * `display: contents` element cannot hold focus - and the panel holding focus is what traps Tab
 * on a screen with nothing focusable in it. `z` is the layer's place among the app's named layers -
 * `z-status` by default, the top one, since nothing is usable under these screens.
 *
 * `splash` draws the kit's `SplashScreen` instead - a card the size of the desktop launcher's
 * splash window, centred over the status screens' darker scrim, the mark turning while `busy` - for
 * Proxyma itself restarting, quitting and stopped. There `title` is the card's one status line and
 * `children` the line under its bar; `icon`, `action` and `scrim` do not apply. Its panel is the
 * card's own box, so it is the card that holds focus.
 */
export function StatusLayer({ level = 0, z = 'z-status', ariaLabel, panelProps, splash = false, ...screen }: ComponentProps<typeof StatusScreen> & {
  level?: number
  z?: string
  ariaLabel?: string
  panelProps?: HTMLAttributes<HTMLDivElement>
  splash?: boolean
}) {
  if (splash) {
    return (
      <ModalShell
        level={level}
        role="alertdialog"
        ariaLabel={ariaLabel}
        panelProps={panelProps}
        overlayClassName={cn('fixed inset-0 flex items-center justify-center p-4', z)}
        scrimClassName={MODAL_SCRIM_DARK}
        closeOnBackdrop={false}
        panelClassName="w-[440px] max-w-full"
      >
        <SplashScreen turning={!!screen.busy} status={screen.title} detail={screen.children} />
      </ModalShell>
    )
  }
  return (
    <ModalShell
      level={level}
      role="alertdialog"
      ariaLabel={ariaLabel}
      panelProps={panelProps}
      overlayClassName={cn('fixed inset-0', z)}
      scrimClassName={null}
      panelClassName="h-full w-full"
    >
      <StatusScreen {...screen} layer={false} />
    </ModalShell>
  )
}
