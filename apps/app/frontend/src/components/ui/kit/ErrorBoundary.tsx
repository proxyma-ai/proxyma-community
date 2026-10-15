/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/ErrorBoundary.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { Component, type ErrorInfo, type ReactNode } from 'react'
import { claimChunkReload, isChunkLoadError } from './errorRecovery'

/**
 * A RENDER ERROR IS A SCREEN, NOT A BLANK PAGE (2026-10-14, the cross-app review's X14).
 *
 * Without one, React unmounts the whole root on any error thrown while rendering - a malformed chat
 * message, a chart fed a value it did not expect, or, after every deploy, a lazily loaded route whose
 * chunk no longer exists - and the reader is left looking at the canvas color. Each app wraps its
 * root (a whole-screen fallback) and each route (a fallback inside the page, so the header and the
 * way out stay) in one of these.
 *
 * A STALE CHUNK RELOADS ONCE BY ITSELF. It is not a fault in the page: the tab holds the previous
 * build's index. `errorRecovery.ts` decides, and a second failure inside its window is shown
 * rather than reloaded again, so a real fault cannot loop.
 *
 * THE ONE CLASS COMPONENT IN THE KIT, because React has no hook for catching a render error -
 * `getDerivedStateFromError` and `componentDidCatch` exist only on a class. The fallback is a render
 * prop: the kit cannot call `t()`, so each app words its own screen from the kit's parts.
 */

export interface ErrorBoundaryFallbackProps {
  error: unknown
  /** True while the page reloads itself for a stale chunk - say that, not that something broke. */
  reloading: boolean
  /** Clears the error and renders the children again (a retry that needs no reload). */
  reset: () => void
}

export interface ErrorBoundaryProps {
  children: ReactNode
  fallback: (props: ErrorBoundaryFallbackProps) => ReactNode
  /** When this changes the error is cleared - a new route, a different message. */
  resetKey?: unknown
  /** For a caller that reports errors somewhere. React already logs them to the console. */
  onError?: (error: unknown, info: ErrorInfo) => void
  /** Reload once, by itself, when the error is a lazily loaded chunk that is gone. Default true. */
  reloadOnStaleChunk?: boolean
}

interface State {
  caught: boolean
  error: unknown
  reloading: boolean
  resetKey: unknown
}

function sessionStore(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage
  } catch {
    return null
  }
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, State> {
  state: State = { caught: false, error: null, reloading: false, resetKey: this.props.resetKey }

  static getDerivedStateFromError(error: unknown): Partial<State> {
    return { caught: true, error }
  }

  static getDerivedStateFromProps(props: ErrorBoundaryProps, state: State): Partial<State> | null {
    if (Object.is(props.resetKey, state.resetKey)) return null
    return { resetKey: props.resetKey, caught: false, error: null, reloading: false }
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    this.props.onError?.(error, info)
    if (this.props.reloadOnStaleChunk !== false && isChunkLoadError(error) && claimChunkReload(sessionStore(), Date.now())) {
      this.setState({ reloading: true })
      window.location.reload()
    }
  }

  private reset = () => {
    this.setState({ caught: false, error: null, reloading: false })
  }

  render() {
    if (!this.state.caught) return this.props.children
    return this.props.fallback({ error: this.state.error, reloading: this.state.reloading, reset: this.reset })
  }
}
