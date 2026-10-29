/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/MarketingClient.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { Pause, Play, RotateCcw } from 'lucide-react'
import { cn } from './cn'
import { Collapse, DisclosureChevron } from './Disclosure'

/**
 * Whether a scene may start: true once its element has come into view, and never under reduced
 * motion or without an IntersectionObserver - so the scene keeps drawing its finished state there.
 * The marketing site's rule is that every scene plays ONCE, when a reader can see it, then holds.
 */
export function useInViewOnce<T extends HTMLElement>(threshold = 0.35) {
  const ref = useRef<T>(null)
  const [go, setGo] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const io = new IntersectionObserver(entries => {
      if (entries.some(e => e.isIntersecting)) { io.disconnect(); setGo(true) }
    }, { threshold })
    io.observe(el)
    return () => io.disconnect()
  }, [threshold])
  return { ref, go }
}

export type SceneState = 'idle' | 'playing' | 'paused' | 'done'

/**
 * A SCENE'S CLOCK: beats that each hold for their own time, played once from when `go` turns true,
 * with Pause, Play and Replay. `step` is the beat on screen; it starts at `durations.length` - the
 * FINISHED state - so the server, a reader with reduced motion and a reader with no script all draw
 * the ending, and only a scene that has come into view steps back to the start.
 */
export function useScenePlayer(durations: readonly number[], go: boolean) {
  const end = durations.length
  const [step, setStep] = useState(end)
  const [state, setState] = useState<SceneState>('idle')
  useEffect(() => {
    if (!go) return
    setStep(0)
    setState('playing')
  }, [go])
  useEffect(() => {
    if (state !== 'playing') return
    if (step >= end) { setState('done'); return }
    const id = setTimeout(() => setStep(s => s + 1), durations[step])
    return () => clearTimeout(id)
  }, [state, step, end, durations])
  return {
    step,
    state,
    pause: () => setState(s => (s === 'playing' ? 'paused' : s)),
    play: () => setState(s => (s === 'paused' ? 'playing' : s)),
    replay: () => { setStep(0); setState('playing') },
  }
}

/**
 * A scene's one control: Pause while it plays, Play while paused, Replay once it has finished. A
 * quiet text button with its glyph, so a scene that moves can always be stopped (WCAG 2.2.2) and
 * one that has ended can be watched again. Hidden until the scene has started - a reader with
 * reduced motion, or with no script, sees the finished picture and no control at all.
 */
export function SceneControl({ state, onPause, onPlay, onReplay, labels, className }: {
  state: SceneState
  onPause: () => void
  onPlay: () => void
  onReplay: () => void
  labels: { pause: string; play: string; replay: string }
  className?: string
}) {
  if (state === 'idle') return null
  const [Icon, label, act] = state === 'playing'
    ? [Pause, labels.pause, onPause] as const
    : state === 'paused'
      ? [Play, labels.play, onPlay] as const
      : [RotateCcw, labels.replay, onReplay] as const
  return (
    <button
      type="button"
      onClick={act}
      className={cn('inline-flex items-center gap-2 text-xs text-ink-caption mouse:hover:text-ink-primary transition-colors focus-ring rounded-sm animate-fade-in', className)}
    >
      <Icon size={13} aria-hidden="true" />
      {label}
    </button>
  )
}

/**
 * One question of a FAQ: the question as the disclosure's line (16/600, colour-only hover), the
 * answer under it. The id is from the ENGLISH question so an anchor means the same thing in every
 * language.
 */
export function FaqItem({ id, question, answer }: { id: string; question: ReactNode; answer: ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="border-b border-line last:border-0">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        aria-controls={`faq-answer-${id}`}
        className="w-full text-left py-6 text-base font-semibold text-ink-primary flex items-center justify-between gap-4 mouse:hover:text-brand-legible transition-colors focus-ring rounded-sm"
      >
        <span>{question}</span>
        <DisclosureChevron open={open} className="text-ink-caption" />
      </button>
      <Collapse open={open} id={`faq-answer-${id}`} className="pb-6 -mt-1 max-w-2xl text-base text-ink-secondary">{answer}</Collapse>
    </div>
  )
}

/**
 * THE FRAME A PRODUCT PICTURE SITS IN on the marketing site: a bordered box on the canvas (no
 * shadow), an optional browser-chrome strip (`chrome`, the site's own picture of a browser - not
 * product UI, so it is passed in), and a body drawn at the APP'S REAL SIZE from kit components and
 * scaled ONCE here with CSS `zoom` - so no component needs a "scene" size, which is how the
 * replicas drifted. The body is `inert`: a picture, not a control.
 */
export function SceneFrame({ chrome, header, children, scale = 'md', height, bodyClassName, caption, label, controls, lift = 'card', className }: {
  /**
   * What the picture shows, in one sentence - its text alternative. The frame is then an image to
   * a screen reader (`role="img"`), since its body is inert and cannot be explored anyway.
   */
  label?: string
  /** Beside the caption, at the right: the scene's `SceneControl`. */
  controls?: ReactNode
  chrome?: ReactNode
  /** The product's header bar, drawn from the kit's `AppHeader`. */
  header?: ReactNode
  children: ReactNode
  /** `sm` 0.75, `md` 0.85 from `sm` and 0.75 below, `none` 1. */
  scale?: 'sm' | 'md' | 'none'
  /** A fixed height class for the body, so a scene that animates does not move the page. */
  height?: string
  /** The body's own layout (`flex flex-col`, so a header and a scene share its height). */
  bodyClassName?: string
  caption?: ReactNode
  /**
   * How far the picture stands off the page: `card` (the default) as a card does, `float` as what
   * floats does - for the one picture a page leads with.
   */
  lift?: 'card' | 'float'
  className?: string
}) {
  return (
    <figure className={cn('m-0', className)}>
      <div
        // The picture of an app window: the app's own page plane inside, lifted off the site's
        // page as a card is (since 2026-10-29).
        className={cn('rounded-lg border border-line bg-canvas overflow-hidden', lift === 'float' ? 'shadow-float' : 'shadow-card')}
        {...(label ? { role: 'img', 'aria-label': label } : {})}
      >
        {chrome}
        <div
          // React 18 has no `inert` prop; the attribute is set on the node itself.
          ref={el => { el?.setAttribute('inert', '') }}
          className={cn(
            'overflow-hidden select-none',
            scale === 'sm' && '[zoom:0.75]',
            scale === 'md' && '[zoom:0.75] sm:[zoom:0.85]',
            height,
            bodyClassName,
          )}
        >
          {header}
          {children}
        </div>
      </div>
      {(caption || controls) && (
        <figcaption className="mt-3 flex items-start justify-between gap-4 text-xs text-ink-caption">
          <span>{caption}</span>
          {controls && <span className="shrink-0">{controls}</span>}
        </figcaption>
      )}
    </figure>
  )
}
