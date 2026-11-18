'use client'

import { Copy, Minus, Square, X } from 'lucide-react'
import { cn } from './cn'
import { Tooltip } from './Tooltip'

/**
 * A BORDERLESS WINDOW'S BUTTONS - minimize, maximize or restore, close - drawn by the page, since the
 * desktop app's window has no frame of its own (the maintainer's glass shell, 2026-10-29). Plain
 * glyphs on the glass, each a 40x32 target; close turns red under the pointer, as a title bar's does.
 * Not draggable themselves (`no-drag`), though they sit in the window's drag strip.
 *
 * Presentational: the app supplies what each does and the words (its useDesktopWindow).
 */
export function WindowControls({ maximized, onMinimize, onToggleMaximize, onClose, labels, className }: {
  maximized: boolean
  onMinimize: () => void
  onToggleMaximize: () => void
  onClose: () => void
  labels: { minimize: string; maximize: string; restore: string; close: string }
  className?: string
}) {
  const button = 'w-10 h-8 grid place-items-center rounded-md text-ink-secondary transition-colors focus-ring [-webkit-app-region:no-drag]'
  return (
    <div className={cn('flex items-center gap-0.5', className)}>
      {/* Named by the kit Tooltip, never a native title (frontend.md). */}
      <Tooltip label={labels.minimize} className="contents">
        <button type="button" aria-label={labels.minimize} onClick={onMinimize}
          className={cn(button, 'mouse:hover:bg-surface/60 mouse:hover:text-ink-primary')}>
          <Minus size={16} aria-hidden="true" />
        </button>
      </Tooltip>
      <Tooltip label={maximized ? labels.restore : labels.maximize} className="contents">
        <button type="button" aria-label={maximized ? labels.restore : labels.maximize}
          onClick={onToggleMaximize} className={cn(button, 'mouse:hover:bg-surface/60 mouse:hover:text-ink-primary')}>
          {maximized ? <Copy size={13} aria-hidden="true" className="-scale-x-100" /> : <Square size={13} aria-hidden="true" />}
        </button>
      </Tooltip>
      <Tooltip label={labels.close} className="contents">
        <button type="button" aria-label={labels.close} onClick={onClose}
          className={cn(button, 'mouse:hover:bg-danger mouse:hover:text-on-status')}>
          <X size={16} aria-hidden="true" />
        </button>
      </Tooltip>
    </div>
  )
}
