'use client'

import { useRef, useState } from 'react'
import { Pause, Play } from 'lucide-react'
import { DialogShell, MODAL_SCRIM_DARK } from './Dialog'
import { ProgressBar } from './Meter'
import { Readout } from './Text'

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

/**
 * A SOUND, WITH ITS NAME - a filled round play button, the title, a thin progress bar and the
 * length. The browser's bare `<audio controls>` bar shows no title, so nobody could tell what an
 * audio result was without playing it. A two-line card, so `rounded-lg` like every card (it was a
 * pill). The round button is the one round control: a play button is round everywhere.
 */
export function AudioPlayer({ src, title, crossOrigin, labels }: {
  src: string
  title: string
  crossOrigin?: 'anonymous' | 'use-credentials'
  labels: { play: string; pause: string }
}) {
  const audioRef = useRef<HTMLAudioElement>(null)
  const [playing, setPlaying] = useState(false)
  const [progress, setProgress] = useState(0)
  const [duration, setDuration] = useState(0)

  const toggle = () => {
    const el = audioRef.current
    if (!el) return
    if (playing) el.pause()
    else void el.play()
  }

  return (
    <div className="flex items-center gap-2.5 max-w-full my-2 px-3 py-2 rounded-lg border border-line/40 bg-surface/40">
      <button
        type="button"
        onClick={toggle}
        aria-label={playing ? labels.pause : labels.play}
        className="shrink-0 flex items-center justify-center w-8 h-8 touch:w-11 touch:h-11 rounded-full bg-brand-solid text-brand-on-solid mouse:hover:opacity-90 transition-opacity focus-ring"
      >
        {playing ? <Pause size={15} fill="currentColor" aria-hidden="true" /> : <Play size={15} fill="currentColor" className="ml-0.5" aria-hidden="true" />}
      </button>
      <div className="min-w-0 flex-1">
        <p className="text-sm text-ink-primary truncate">{title}</p>
        <div className="mt-1.5 flex items-center gap-2">
          <ProgressBar value={Math.min(1, progress) * 100} size="xs" className="flex-1" />
          <Readout tone="caption" className="text-xs shrink-0">{formatTime(duration)}</Readout>
        </div>
      </div>
      <audio
        ref={audioRef}
        src={src}
        crossOrigin={crossOrigin}
        className="hidden"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        onLoadedMetadata={e => setDuration(e.currentTarget.duration)}
        onTimeUpdate={e => {
          const el = e.currentTarget
          if (el.duration) setProgress(el.currentTime / el.duration)
        }}
      />
    </div>
  )
}

/**
 * ONE IMAGE AT THE SIZE OF THE WINDOW, over the darker scrim. No panel and no close button (no
 * dialog has one): a click anywhere round the image, or Escape, closes it, through the kit's one
 * dialog layer. The panel is `contents` so the layer adds no box; focus goes to the image.
 */
export function Lightbox({ src, alt, onClose, ariaLabel, crossOrigin }: {
  src: string
  alt: string
  onClose: () => void
  /** The dialog's name when the image has no alt text ("Image"). */
  ariaLabel?: string
  crossOrigin?: 'anonymous' | 'use-credentials'
}) {
  return (
    <DialogShell
      onClose={onClose}
      ariaLabel={alt || ariaLabel}
      overlayClassName="fixed inset-0 z-dialog flex items-center justify-center p-2 sm:p-6"
      scrimClassName={MODAL_SCRIM_DARK}
      panelClassName="contents"
    >
      <img
        src={src}
        alt={alt}
        crossOrigin={crossOrigin}
        onClick={e => e.stopPropagation()}
        tabIndex={-1}
        data-autofocus
        // The panel is `contents`, so the image is what arrives and leaves (useExitGhost).
        data-motion-panel=""
        className="relative max-w-full max-h-full shadow-card-lg object-contain animate-slide-up"
      />
    </DialogShell>
  )
}
