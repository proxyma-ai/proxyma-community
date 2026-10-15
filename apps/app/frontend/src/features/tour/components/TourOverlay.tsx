import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { Button, Caption, FadeSwap, Logo, MODAL_SCRIM, TEXT, cn } from '@/components/ui/kit'
import { configApi } from '@/services/configApi'
import { findKeyAccountIdByName, useKeyAccountsAllowed } from '@edition'
import { SANDBOX_KEY_ACCOUNT_NAME } from '../data/tourSteps'
import { useTourStore } from '@/store/tourStore'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useAuth } from '@/features/auth/hooks/useAuth'
import type { AuthUser } from '@/store/authStore'
import { FEATURES } from '@/config/features'
import { useOnboardingTour } from '../hooks/useOnboardingTour'
import { getTourSteps, type TourStep } from '../data/tourSteps'
import { ModalShell } from '@/components/ui/ModalShell'
import { useTranslation } from '@/i18n'

// How long a step waits for its target before it is skipped. Long enough for a route change, a
// lazily loaded Settings tab and a resolved query to paint - the ways a real target arrives late.
const ELEMENT_WAIT_CAP_MS = 3000
// Space between the card and the highlighted control, and between the card and the window edge.
const POPOVER_GAP = 12
const POPOVER_MARGIN = 16
const DEFAULT_POPOVER_SIZE = { width: 380, height: 220 }
// The visible highlight box drawn around a target element is rect inflated by this many px on
// every side (see the highlight overlay's `top: rect.top - HIGHLIGHT_INFLATE` etc. below) - the
// arc arrow must target that same inflated edge, not the raw element rect, or the dotted line
// visibly terminates inside the highlight box's border instead of touching it - and the card is
// placed clear of that same inflated box.
const HIGHLIGHT_INFLATE = 8

// A clip-path covering the whole screen EXCEPT the inflated highlight: the outer rectangle is
// traced clockwise and the inner one anticlockwise, so under the default nonzero fill the inner one
// is a hole. The two are joined by a zero-width seam from the corner, which paints nothing.
function highlightHole(r: DOMRect): string {
  const x1 = r.left - HIGHLIGHT_INFLATE
  const y1 = r.top - HIGHLIGHT_INFLATE
  const x2 = r.left + r.width + HIGHLIGHT_INFLATE
  const y2 = r.top + r.height + HIGHLIGHT_INFLATE
  return `polygon(0 0, 100% 0, 100% 100%, 0 100%, 0 0, ${x1}px ${y1}px, ${x1}px ${y2}px, ${x2}px ${y2}px, ${x2}px ${y1}px, ${x1}px ${y1}px)`
}

// Clamps a measured rect to the viewport before it's used for placement/arrow math - a target
// element taller or wider than the viewport (e.g. a long scrollable tab body) would otherwise
// produce a target point far off past the visible screen, drawing the arc arrow all the way to
// the bottom/edge of the page instead of at the actually-visible highlighted area.
function clampRectToViewport(r: DOMRect): DOMRect {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const top = Math.max(0, r.top)
  const left = Math.max(0, r.left)
  const bottom = Math.min(vh, r.bottom)
  const right = Math.min(vw, r.right)
  return new DOMRect(left, top, Math.max(0, right - left), Math.max(0, bottom - top))
}

/** What a stop points at: its tour id, a selector for a kit part with none, or nothing (centred). */
function findTarget(step: TourStep): Element | null {
  if (step.targetId) return document.querySelector(`[data-tour-id="${step.targetId}"]`)
  if (step.targetSelector) return document.querySelector(step.targetSelector)
  return null
}

const hasTarget = (step: TourStep) => !!(step.targetId || step.targetSelector)

function shouldSkipStep(step: TourStep, user: AuthUser | null | undefined, keyAccounts: boolean | undefined): boolean {
  if (step.requiresFeature && !FEATURES[step.requiresFeature]) return true
  // A stop about a licensed page is shown only where the page is: Enterprise Server, signed in, with
  // a license that says so. Still loading counts as no - by the fourth stop the status has long
  // arrived, and a stop that cannot be shown must never be waited for.
  if (step.requiresLicense === 'keyAccounts' && keyAccounts !== true) return true
  // Desktop mode has no auth and no roles: there is one user, they reach every page and every
  // settings tab, so every requiresRole bar is satisfied by definition. Checking `!user` there
  // instead would skip the Connectors and Settings steps - the surfaces a desktop user most needs
  // walked through - because nobody is ever "logged in" in that build.
  if (!FEATURES.auth) return false
  if (step.requiresRole === 'admin') {
    const isAdmin = !!user && (user.role === 'SUPER_ADMIN' || user.role === 'ADMIN')
    if (!isAdmin) return true
  }
  if (step.requiresRole === 'auth' && !user) return true
  return false
}

type Placement = 'right' | 'left' | 'below' | 'above' | 'corner' | 'center'

interface PopoverPos { top: number; left: number; placement: Placement }

function clamp(n: number, lo: number, hi: number): number {
  return Math.min(Math.max(n, lo), Math.max(lo, hi))
}

function overlapArea(a: { left: number; top: number; right: number; bottom: number }, p: PopoverPos, w: number, h: number): number {
  const x = Math.max(0, Math.min(a.right, p.left + w) - Math.max(a.left, p.left))
  const y = Math.max(0, Math.min(a.bottom, p.top + h) - Math.max(a.top, p.top))
  return x * y
}

/**
 * BESIDE THE TARGET, NEVER OVER IT (app UI review, F20). The card used to go above or below and
 * then clamp into the window, which on a tall or central target - the composer, a Settings list -
 * put it squarely on top of the control the step was pointing at.
 *
 * The sides are tried in order - right, left, below, above - and the first where the whole card
 * fits in the window, clear of the highlight, wins. When none does (a target filling most of the
 * window), every side and every window corner is scored by how much of the highlight it would
 * cover and the least-covering one is used, so the card sits over the edge of a large target
 * rather than its middle.
 */
function computePlacement(rect: DOMRect | null, width: number, height: number): PopoverPos {
  const vw = window.innerWidth
  const vh = window.innerHeight
  if (!rect) {
    return { top: vh / 2 - height / 2, left: vw / 2 - width / 2, placement: 'center' }
  }
  const box = {
    left: rect.left - HIGHLIGHT_INFLATE,
    top: rect.top - HIGHLIGHT_INFLATE,
    right: rect.right + HIGHLIGHT_INFLATE,
    bottom: rect.bottom + HIGHLIGHT_INFLATE,
  }
  const midY = clamp(rect.top + rect.height / 2 - height / 2, POPOVER_MARGIN, vh - height - POPOVER_MARGIN)
  const midX = clamp(rect.left + rect.width / 2 - width / 2, POPOVER_MARGIN, vw - width - POPOVER_MARGIN)
  const sides: PopoverPos[] = [
    { placement: 'right', left: box.right + POPOVER_GAP, top: midY },
    { placement: 'left', left: box.left - POPOVER_GAP - width, top: midY },
    { placement: 'below', left: midX, top: box.bottom + POPOVER_GAP },
    { placement: 'above', left: midX, top: box.top - POPOVER_GAP - height },
  ]
  const inWindow = (p: PopoverPos) =>
    p.left >= POPOVER_MARGIN && p.top >= POPOVER_MARGIN
    && p.left + width <= vw - POPOVER_MARGIN && p.top + height <= vh - POPOVER_MARGIN
  const fits = sides.find(p => inWindow(p))
  if (fits) return fits

  const right = vw - width - POPOVER_MARGIN
  const bottom = vh - height - POPOVER_MARGIN
  const fallbacks: PopoverPos[] = [
    ...sides.map(p => ({ ...p, left: clamp(p.left, POPOVER_MARGIN, right), top: clamp(p.top, POPOVER_MARGIN, bottom) })),
    { placement: 'corner', left: right, top: bottom },
    { placement: 'corner', left: POPOVER_MARGIN, top: bottom },
    { placement: 'corner', left: right, top: POPOVER_MARGIN },
    { placement: 'corner', left: POPOVER_MARGIN, top: POPOVER_MARGIN },
  ]
  return fallbacks.reduce((best, p) => (overlapArea(box, p, width, height) < overlapArea(box, best, width, height) ? p : best))
}

export function TourOverlay() {
  const { t } = useTranslation()
  const active = useTourStore(s => s.active)
  const stepIndex = useTourStore(s => s.stepIndex)
  const next = useTourStore(s => s.next)
  const skip = useTourStore(s => s.skip)
  const finish = useTourStore(s => s.finish)

  const { user } = useAuth()
  const { completeTour } = useOnboardingTour()
  const navigate = useNavigate()
  const location = useLocation()
  // Held in a ref so the step effect, whose dependency list deliberately leaves it out, can still
  // record completion when the last reachable step is skipped for a missing target.
  const completeRef = useRef(completeTour)
  completeRef.current = completeTour

  const [rect, setRect] = useState<DOMRect | null>(null)
  const [settled, setSettled] = useState(false)
  const [pos, setPos] = useState<PopoverPos | null>(null)
  const popoverRef = useRef<HTMLDivElement>(null)

  // Per-step bookkeeping, all of it there for the same reason: the step effect below re-runs for
  // things that are NOT a step change.
  //
  // Its dependency list includes the route (it navigates), and it opens the Settings modal, and
  // it reads `user` - so it is re-entered when the navigation it just asked for lands, when the
  // modal mounts, and again on any refetch that hands react-query a fresh identity for the user.
  // Everything it did on entry then happened a second and third time: the measured rect was
  // thrown away, the wait for the target element restarted from zero, and the navigation was
  // reissued. A step whose re-runs arrived faster than its own timeout could therefore never
  // reach that timeout - which is a walkthrough that stops dead part-way through, dimmed screen,
  // no card, no way on but a page reload. Keyed on stepIndex, each of these happens exactly once
  // per step no matter how often the effect runs.
  const enteredStep = useRef<number | null>(null)
  const navigatedForStep = useRef<number | null>(null)
  const deadlineForStep = useRef<number | null>(null)
  const stepDeadline = useRef(0)

  const steps = useMemo(() => getTourSteps(), [])
  const step: TourStep | undefined = steps[stepIndex]

  // "Step 4 of 14" counted against the raw array told a plain VIEWER they were on step 4 of 14
  // and then jumped them from 3 to 7, because every admin-only step in between is skipped on
  // sight. Counting against the steps this particular user can actually reach makes both numbers
  // true for them. A step skipped for a missing target can still leave the total one optimistic -
  // whether that happens isn't knowable until the page is on screen - but the position never jumps
  // backwards, which was the visible part of the problem.
  const keyAccountsAllowed = useKeyAccountsAllowed()
  const visibleSteps = useMemo(() => steps.filter(s => !shouldSkipStep(s, user, keyAccountsAllowed)),
    [steps, user, keyAccountsAllowed])
  const visibleIndex = step ? visibleSteps.indexOf(step) : -1
  // The welcome is not a stop: "1 of 4" counts the stops after it.
  const stops = visibleSteps.filter(s => s.kind !== 'welcome')
  const stopIndex = step ? stops.indexOf(step) : -1

  // The sandbox is where the demo key account is seeded (SandboxScenarioSeeder); only there does
  // its stop offer it. The account is found by its name among the caller's accounts - the seeder
  // makes all three sandbox accounts members - and the list page is the fallback when a visitor
  // has deleted or renamed it, so the button never leads to "not found".
  const { data: config } = useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false })
  const isSandbox = config?.sandbox === true
  const { data: demoAccountId } = useQuery({
    queryKey: ['tour', 'sandbox-key-account'],
    queryFn: () => findKeyAccountIdByName(SANDBOX_KEY_ACCOUNT_NAME),
    enabled: isSandbox && keyAccountsAllowed === true,
    staleTime: Infinity,
    retry: false,
  })

  // Skip/finish end the tour without necessarily changing stepIndex (so the main step effect
  // below never re-runs to close it) - closes the settings modal if the tour opened it. No-op if
  // it wasn't open.
  useEffect(() => {
    if (!active) {
      useSettingsModalStore.getState().close()
      // Cleared so a replay from Settings > About starts from a blank slate. Without this, a tour
      // that ended on step 0 and is restarted at step 0 would find enteredStep already 0 and skip
      // its own reset, reusing the previous run's rect.
      enteredStep.current = null
      navigatedForStep.current = null
      deadlineForStep.current = null
    }
  }, [active])

  // Skip-check, cross-page navigation, and bounded element-wait polling, all in one effect so a
  // step that's about to be role-skipped never wastes a navigation or a poll cycle first.
  useEffect(() => {
    if (!active) return
    // Ran off the end - the last reachable step was skipped for a missing target. That is a tour
    // the user went through, so it is recorded as complete like one ended with Done.
    if (stepIndex >= steps.length) { completeRef.current(); finish(); return }
    const current = steps[stepIndex]
    if (shouldSkipStep(current, user, keyAccountsAllowed)) { next(); return }
    const cap = ELEMENT_WAIT_CAP_MS

    // ONCE per step, not once per run of this effect. The reset is still immediate on a genuine
    // step change - any stale rect/popover from the previous step must disappear right away
    // instead of lingering, briefly pointing at the wrong page's element - but a re-run for the
    // same step leaves the settled step alone rather than blanking it and starting over.
    if (enteredStep.current !== stepIndex) {
      enteredStep.current = stepIndex
      setRect(null)
      setSettled(false)
      setPos(null)
    }

    if (current.route) {
      const here = location.pathname + location.search
      // At most one navigation per step. If the destination bounces - a guard redirecting, some
      // other effect navigating back - then `here` never becomes the step's route, and reissuing
      // the navigation on every re-run is a loop with no exit. One attempt; if it did not take,
      // fall through and let the element wait below reach its deadline and show the step
      // unanchored, which is a walkthrough that carries on rather than one that is stuck.
      if (here !== current.route && navigatedForStep.current !== stepIndex) {
        navigatedForStep.current = stepIndex
        navigate(current.route)
        return
      }
    }

    // Settings is a global modal, not a route - open/close it directly instead of navigating.
    // Closing on every non-settings step (not just once) keeps this correct even if the user
    // somehow lands here with it already open for an unrelated reason.
    if (current.openSettingsTab) {
      useSettingsModalStore.getState().open(current.openSettingsTab)
    } else {
      useSettingsModalStore.getState().close()
    }

    // Stamped where the element wait actually BEGINS - after any navigation this step asked for
    // has landed, and after the Settings modal has been told to open - so neither of those eats
    // into the budget the element itself gets. Once per step, so a re-run part-way through the
    // wait cannot hand it a fresh three seconds.
    if (deadlineForStep.current !== stepIndex) {
      deadlineForStep.current = stepIndex
      stepDeadline.current = performance.now() + cap
    }

    // A centred stop - the welcome, or a stop about the product as a whole - has nothing to wait for.
    if (!hasTarget(current)) {
      setRect(null)
      setSettled(true)
      return
    }

    let cancelled = false
    let rafId = 0

    function tick() {
      if (cancelled) return
      const el = findTarget(current)
      if (el) {
        el.scrollIntoView({ block: 'center', behavior: 'smooth' })
        // Let the smooth scroll settle before measuring final position.
        window.setTimeout(() => {
          if (cancelled) return
          setRect(clampRectToViewport(el.getBoundingClientRect()))
          setSettled(true)
        }, 350)
        return
      }
      // Against the deadline stamped when this step was ENTERED, so a re-run mid-wait does not
      // hand the step a fresh three seconds. This comparison is the only thing that ends a step
      // whose target never arrives, and a clock it can restart is a clock that never strikes.
      if (performance.now() > stepDeadline.current) {
        // No target, no step. The sentence is about THIS control, and shown with nothing to point
        // at it would describe a button the reader cannot see - so the tour moves on instead.
        next()
        return
      }
      rafId = requestAnimationFrame(tick)
    }
    rafId = requestAnimationFrame(tick)
    return () => { cancelled = true; cancelAnimationFrame(rafId) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, stepIndex, user, keyAccountsAllowed, location.pathname, location.search, steps])

  // Keep the highlight/popover glued to the target if the user scrolls or resizes mid-step.
  useEffect(() => {
    if (!settled || !step || !hasTarget(step)) return
    function update() {
      const el = findTarget(step!)
      if (el) setRect(clampRectToViewport(el.getBoundingClientRect()))
    }
    window.addEventListener('scroll', update, true)
    window.addEventListener('resize', update)
    return () => {
      window.removeEventListener('scroll', update, true)
      window.removeEventListener('resize', update)
    }
  }, [settled, step])

  // Measure the popover's real size once it's settled and mounted, then compute its on-screen
  // position from that (rather than a guessed size) to avoid a visible resize jump.
  useLayoutEffect(() => {
    if (!active || !settled) return
    const el = popoverRef.current
    const width = el?.offsetWidth ?? DEFAULT_POPOVER_SIZE.width
    const height = el?.offsetHeight ?? DEFAULT_POPOVER_SIZE.height
    setPos(computePlacement(rect, width, height))
  }, [active, settled, rect, stepIndex])

  if (!active || !step) return null

  const handleSkip = () => {
    completeTour()
    skip()
  }

  // The last step THIS user can reach, not the last in the file - a member never sees the Sources
  // step, and the desktop and server feedback steps are one entry each.
  const isLast = visibleIndex === visibleSteps.length - 1

  const handleNext = () => {
    if (isLast) {
      completeTour()
      finish()
      navigate('/')
      return
    }
    next()
  }

  // Leaves the tour for the place a stop offers (the sandbox's demo account). The tour counts as
  // seen - it was, up to here - and the visitor lands where they asked to go.
  const handleAction = (route: string) => {
    completeTour()
    finish()
    navigate(route)
  }

  const welcome = step.kind === 'welcome'
  const label = t(step.title)

  return (
    // The kit's named `z-tour` layer (not `z-dialog`, every other modal's): the settings-credentials
    // step opens the Settings modal (see the effect above), which is ALSO a document.body portal
    // on the dialog layer, mounted later in AppShell's tree than this one - equal layers tie on
    // DOM order, so the modal painted over this whole overlay whenever that step ran. The tour
    // layer is above dialogs, toasts, floating panels and tooltips, so none of them can obscure a
    // step, and below `z-status` (the maintenance and restart screens), which must win over
    // everything.
    //
    // level 1 for the same reason on the keyboard side: the Settings modal that step opens mounts
    // AFTER this, so by order alone it would take Escape and the Tab trap while this sits on top of
    // it. Escape here is Skip.
    <ModalShell
      onClose={handleSkip}
      level={1}
      ariaLabel={label}
      overlayClassName="fixed inset-0 z-tour"
      scrimClassName={null}
      // `contents`: the highlight and the card each place themselves against the
      // viewport, so the layer element must not add a box of its own around them.
      panelClassName="contents"
    >
      {rect ? (
        <>
        {/* The blur half of the scrim every dialog has, with a HOLE where the highlighted control
            is - the step points at that control, so it is the one thing that must stay sharp. The
            darkening is the highlight's own box-shadow below; a shadow cannot blur, so this layer
            does, clipped by a polygon that traces the screen and then the highlight the other way
            round (opposite winding leaves the inner rectangle out). Inline style for the same
            reason the highlight uses one: the geometry is measured at runtime. */}
        {/* `data-scrim` on both halves: each fades on its own when the tour ends (the kit's exit
            ghost), and in when it starts - a fading parent would cut the blur off at once. */}
        <div
          aria-hidden="true"
          data-scrim=""
          className="fixed inset-0 backdrop-blur-sm pointer-events-none transition-[clip-path] duration-base ease-in-out animate-fade-in"
          style={{ clipPath: highlightHole(rect) }}
        />
        <div
          data-scrim=""
          className="fixed rounded-lg pointer-events-auto transition-all duration-base ease-in-out animate-fade-in"
          style={{
            top: rect.top - HIGHLIGHT_INFLATE,
            left: rect.left - HIGHLIGHT_INFLATE,
            width: rect.width + HIGHLIGHT_INFLATE * 2,
            height: rect.height + HIGHLIGHT_INFLATE * 2,
            boxShadow: '0 0 0 9999px rgba(0,0,0,0.55), 0 0 0 2px rgba(255,255,255,0.35)',
          }}
          onClick={handleSkip}
        />
        </>
      ) : (
        <div data-scrim="" className={MODAL_SCRIM} onClick={handleSkip} />
      )}

      {/* No arc arrow any more: the card sits BESIDE the highlighted control (computePlacement),
          so the hole in the scrim is the pointer. */}
      {settled && (
        <div
          ref={popoverRef}
          role="document"
          data-motion-panel=""
          className={cn(
            // Beside its control, or centred when it has none. A title, two or three sentences and
            // at most three points - never the old 640px deck that sat on top of what it described.
            // Raised inside the tour's own layer, over its scrim halves.
            'fixed z-raised max-w-[calc(100vw-32px)] surface-float border border-line/45 rounded-lg shadow-card',
            welcome ? 'w-[420px] px-6 py-6' : 'w-[380px] px-5 py-4',
            pos ? 'animate-slide-up' : 'opacity-0 pointer-events-none',
          )}
          style={{ top: pos?.top ?? 0, left: pos?.left ?? 0 }}
        >
          {/* Each step's words fade in over the last; the card itself moves to its new control. */}
          <FadeSwap swapKey={stopIndex}>
            {welcome ? (
              <div className="mb-4"><Logo size={28} /></div>
            ) : (
              // A count rather than the kit Stepper: a card 380px wide cannot carry a mark per stop
              // of a tour that runs to ten, and the count is the one fact the reader needs.
              <p className={cn(TEXT.caption, 'mb-1.5 data-readout')}>
                {t('{current} of {total}', { current: Math.max(stopIndex, 0) + 1, total: stops.length })}
              </p>
            )}
            <h2 className={welcome ? TEXT.title : TEXT.section}>{label}</h2>
            <p className={cn(TEXT.body, 'mt-2')}>{t(step.text)}</p>
            {step.points && step.points.length > 0 && (
              <ul className="mt-3 space-y-1.5">
                {step.points.map(point => (
                  <li key={point} className={cn(TEXT.caption, 'flex items-start gap-2 leading-relaxed text-ink-secondary')}>
                    <Check size={14} className="shrink-0 mt-0.5 text-brand-legible" aria-hidden="true" />
                    <span>{t(point)}</span>
                  </li>
                ))}
              </ul>
            )}
            {isSandbox && step.sandboxNote && <Caption className="mt-3">{t(step.sandboxNote)}</Caption>}
          </FadeSwap>
          <div className="mt-5 flex items-center justify-between gap-3">
            <Button variant="quiet" onClick={handleSkip}>{welcome ? t('Not now') : t('Skip tour')}</Button>
            <div className="flex items-center gap-3">
              {isSandbox && step.sandboxAction && (
                <Button variant="secondary" onClick={() => handleAction(demoAccountId ? `/key-accounts/${demoAccountId}` : step.sandboxAction!.route)}>{t(step.sandboxAction.label)}</Button>
              )}
              <Button onClick={handleNext} data-autofocus>
                {welcome ? t('Show me around') : isLast ? t('Done') : t('Next')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </ModalShell>
  )
}
