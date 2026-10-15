'use client'

import type { ComponentType } from 'react'
import { useEffect, useRef, useState } from 'react'
import { Check, Copy } from 'lucide-react'
import { cn } from './cn'
import { iconButton, type IconButtonSize } from './iconButton'
import { Tooltip } from './Tooltip'
import { textLink } from './typeScale'

/**
 * Puts text on the clipboard, falling back to a hidden textarea where the async API is refused
 * (an http:// LAN address of a home server, an older WebView). Resolves false when both failed.
 * Three copies of this fallback existed - the token dialog, the AI assistants tab, the source
 * dialog - and one copy button had no fallback at all.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // fall through to the textarea
  }
  try {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    return ok
  } catch {
    return false
  }
}

/** How long the tick stays. One number for every copy button. */
export const COPIED_MS = 2000

/**
 * Copy, then a tick in the place of the icon for two seconds - in the brand ink, the colour of
 * "it worked" (it was green in four places and brand in two). `getText` may be async, for a
 * value fetched on demand. `variant="button"` is the labelled form, a secondary-weight text action
 * whose words change to `copiedLabel`.
 *
 * For a value that is MADE on demand and can fail (a share link the server mints): `getText` may
 * throw, which reaches `onError` (the app's toast) and copies nothing; the button is disabled while
 * it runs; `icon` replaces the copy glyph; `tooltip` says more than the name on hover; `status`
 * also SHOWS `copiedLabel` as a caption beside the icon, for as long as `copiedMs`.
 */
export function CopyButton({ getText, label, copiedLabel, size = 'sm', variant = 'icon', onCopied, onError, icon: Icon = Copy, tooltip, status, copiedMs = COPIED_MS, className }: {
  getText: () => string | Promise<string>
  /** The accessible name, and the visible label of `variant="button"` ("Copy command"). */
  label: string
  /** What the tooltip (or the button) says once copied ("Copied"). */
  copiedLabel: string
  size?: IconButtonSize
  variant?: 'icon' | 'button'
  /** For a toast, where the app wants one. */
  onCopied?: () => void
  /** `getText` threw - nothing was copied. */
  onError?: (error: unknown) => void
  icon?: ComponentType<{ size?: number | string; className?: string }>
  /** The hover text while not copied, when it says more than `label`. */
  tooltip?: string
  /** Show `copiedLabel` beside the icon while copied, not only in the tooltip. */
  status?: boolean
  copiedMs?: number
  className?: string
}) {
  const [copied, setCopied] = useState(false)
  const [pending, setPending] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current) }, [])

  const copy = async () => {
    if (pending) return
    setPending(true)
    try {
      const ok = await copyText(await getText())
      if (!ok) return
      setCopied(true)
      onCopied?.()
      if (timer.current) clearTimeout(timer.current)
      timer.current = setTimeout(() => setCopied(false), copiedMs)
    } catch (e) {
      onError?.(e)
    } finally {
      setPending(false)
    }
  }

  if (variant === 'button') {
    return (
      <button
        type="button"
        onClick={copy}
        disabled={pending}
        // The kit's text link (quiet until it has copied, then the brand's ink of work done).
        className={cn(textLink(copied ? 'brand' : 'quiet'), 'text-xs', className)}
      >
        {copied ? <Check size={14} aria-hidden="true" /> : <Icon size={14} aria-hidden="true" />}
        <span aria-live="polite">{copied ? copiedLabel : label}</span>
      </button>
    )
  }

  const button = (
    <Tooltip label={copied ? copiedLabel : (tooltip ?? label)}>
      <button type="button" onClick={copy} disabled={pending} aria-label={label} className={cn(iconButton(size), className)}>
        {copied ? <Check size={16} className="text-brand-legible" aria-hidden="true" /> : <Icon size={16} aria-hidden="true" />}
      </button>
    </Tooltip>
  )
  if (!status) return button
  return (
    <>
      {button}
      {copied && <span role="status" className="text-xs text-ink-caption">{copiedLabel}</span>}
    </>
  )
}
