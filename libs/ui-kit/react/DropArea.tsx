'use client'

import type { ComponentType, DragEvent, ReactNode } from 'react'
import { useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Upload } from 'lucide-react'
import { Button } from './Button'
import type { ButtonSize, ButtonVariant } from './buttonClass'
import { cn } from './cn'

export interface FileDropHandlers {
  onDragEnter: (e: DragEvent<HTMLElement>) => void
  onDragOver: (e: DragEvent<HTMLElement>) => void
  onDragLeave: (e: DragEvent<HTMLElement>) => void
  onDrop: (e: DragEvent<HTMLElement>) => void
}

const carriesFiles = (e: DragEvent<HTMLElement>) => Array.from(e.dataTransfer?.types ?? []).includes('Files')

/**
 * A FILE DROP TARGET'S STATE, with an enter/leave COUNTER. Crossing a child element fires a leave
 * on the parent before the enter on the child, so a boolean toggled by enter and leave flickers
 * off and on as the pointer moves - the upload dialog's box did, which is what the dashed ring's
 * old comment warned about. Four copies of this existed, one of them the boolean.
 *
 * `stop` keeps a nested target (a folder row inside the Sources page) from also lighting the page.
 */
export function useFileDrop({ enabled = true, onDrop, stop = false }: {
  enabled?: boolean
  onDrop: (data: DataTransfer) => void
  stop?: boolean
}): { active: boolean; handlers: FileDropHandlers } {
  const [active, setActive] = useState(false)
  const depth = useRef(0)
  const take = (e: DragEvent<HTMLElement>) => {
    if (!enabled || !carriesFiles(e)) return false
    e.preventDefault()
    if (stop) e.stopPropagation()
    return true
  }
  const onDragEnter = (e: DragEvent<HTMLElement>) => {
    if (!take(e)) return
    depth.current += 1
    setActive(true)
  }
  const onDragOver = (e: DragEvent<HTMLElement>) => {
    if (take(e)) e.dataTransfer.dropEffect = 'copy'
  }
  const onDragLeave = (e: DragEvent<HTMLElement>) => {
    if (!take(e)) return
    depth.current = Math.max(0, depth.current - 1)
    if (depth.current === 0) setActive(false)
  }
  const handleDrop = (e: DragEvent<HTMLElement>) => {
    if (!take(e)) return
    depth.current = 0
    setActive(false)
    onDrop(e.dataTransfer)
  }

  return { active, handlers: { onDragEnter, onDragOver, onDragLeave, onDrop: handleDrop } }
}

/**
 * THE DROP BOX of a dialog: a dashed box with a glyph and a sentence, the box itself opening the
 * file picker, and an optional second action under the sentence ("or select a folder"). Pass
 * the `active` and `handlers` from `useFileDrop`. Active is `.dropzone-active` - the success tint
 * and a static dashed ring, meaning "a valid place to drop".
 */
export function DropArea({ active, handlers, label, hint, onPick, secondaryAction, icon: Icon = Upload, disabled, className }: {
  active: boolean
  handlers: FileDropHandlers
  label: ReactNode
  hint?: ReactNode
  /** Opens the file picker - the box is a button. */
  onPick?: () => void
  secondaryAction?: ReactNode
  icon?: ComponentType<{ size?: number | string; className?: string }>
  disabled?: boolean
  className?: string
}) {
  return (
    <div
      {...(disabled ? {} : handlers)}
      role={onPick ? 'button' : undefined}
      tabIndex={onPick && !disabled ? 0 : undefined}
      onClick={disabled ? undefined : onPick}
      onKeyDown={e => {
        // Only the box's own keys: Enter on "or select a folder" bubbles here and must stay that link's.
        if (e.target !== e.currentTarget) return
        if (onPick && !disabled && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onPick() }
      }}
      className={cn(
        'relative w-full min-h-36 flex flex-col items-center justify-center gap-2 px-4 py-6 rounded-lg border-2 border-dashed text-center transition-colors focus-ring',
        active ? 'border-transparent dropzone-active' : 'border-line/60 mouse:hover:border-line',
        onPick && !disabled && 'cursor-pointer',
        disabled && 'opacity-50 cursor-not-allowed',
        className,
      )}
    >
      <Icon size={24} className={active ? 'text-success' : 'text-ink-caption'} aria-hidden="true" />
      <span className={cn('text-sm', active ? 'text-success' : 'text-ink-secondary')}>{label}</span>
      {hint && <span className="text-xs text-ink-caption">{hint}</span>}
      {secondaryAction && <span onClick={e => e.stopPropagation()}>{secondaryAction}</span>}
    </div>
  )
}

/**
 * THE FULL-WINDOW DROP LAYER - "Drop files to attach" over chat, "Drop to add" over Sources. The
 * glyph is in the brand ink while it waits (it was green, though nothing had succeeded yet) and the
 * whole layer takes `.dropzone-active`.
 */
export function DropOverlay({ active, label, icon: Icon = Upload, portal = true }: {
  active: boolean
  label: ReactNode
  icon?: ComponentType<{ size?: number | string; className?: string }>
  /** Off for an overlay confined to its own positioned box. */
  portal?: boolean
}) {
  if (!active) return null
  const layer = (
    <div className={cn(portal ? 'fixed' : 'absolute', 'inset-0 z-dropzone pointer-events-none flex items-center justify-center dropzone-active rounded-none')}>
      <div className="flex flex-col items-center gap-3 surface-float border border-line/45 rounded-lg shadow-card px-6 py-5">
        <Icon size={32} className="text-brand-legible" aria-hidden="true" />
        <span className="text-sm font-medium text-ink-secondary">{label}</span>
      </div>
    </div>
  )
  return portal ? createPortal(layer, document.body) : layer
}

/**
 * THE FILE PICKER WITHOUT ITS CONTROL - for anything that opens it: a menu row ("Attach files"), a
 * hyperlink in a drop area, a button. Returns `open()` and the hidden `<input type="file">` to put
 * anywhere in the tree (`input`); the input is emptied after each pick so the same file can be
 * chosen twice. `directory` picks a whole folder (`webkitdirectory`). Five places wrote the hidden
 * input and its click bridge by hand; `FilePickerButton` is this behind the kit Button.
 */
export function useFilePicker({ onFiles, accept, multiple, directory }: {
  onFiles: (files: File[]) => void
  accept?: string
  multiple?: boolean
  directory?: boolean
}): { open: () => void; input: ReactNode } {
  const inputRef = useRef<HTMLInputElement>(null)
  // `webkitdirectory` is not in React's attribute types; spread so the string attribute reaches the DOM.
  const folder = directory ? { webkitdirectory: '', directory: '' } : {}
  const input = (
    <input
      ref={inputRef}
      type="file"
      accept={accept}
      multiple={multiple || directory}
      className="hidden"
      tabIndex={-1}
      aria-hidden="true"
      {...folder}
      onChange={e => {
        const files = Array.from(e.target.files ?? [])
        e.target.value = ''
        if (files.length > 0) onFiles(files)
      }}
    />
  )
  return { open: () => inputRef.current?.click(), input }
}

/**
 * A BUTTON THAT OPENS THE FILE PICKER - a policy file, a configuration to import. The native
 * `<input type="file">` is hidden behind the kit `Button` (its own control cannot be styled and
 * names nothing), and is emptied after each pick so the same file can be chosen twice.
 */
export function FilePickerButton({ onFiles, accept, multiple, directory, children, variant = 'secondary', size = 'sm', disabled, loading, className }: {
  onFiles: (files: File[]) => void
  /** The input's `accept` - ".json,application/json". */
  accept?: string
  multiple?: boolean
  /** A whole folder rather than files. */
  directory?: boolean
  children: ReactNode
  variant?: ButtonVariant
  size?: ButtonSize
  disabled?: boolean
  loading?: boolean
  className?: string
}) {
  const picker = useFilePicker({ onFiles, accept, multiple, directory })
  return (
    <>
      <Button type="button" variant={variant} size={size} disabled={disabled} loading={loading} className={className} onClick={picker.open}>
        {children}
      </Button>
      {picker.input}
    </>
  )
}
