import { useEffect, useMemo, useRef, useState } from 'react'
import { prefersReducedMotion } from '@/components/ui/kit'
import type { GraphEdge, GraphNode } from '../types/indexing.types'
import {
  buildLayout, hopsFrom, rotatePoint, tick,
  type Layout, type Node3D, type SpreadOptions,
} from './graph3d'

// A knowledge graph as a rotatable 3D star field, on a <canvas>.
//
// CANVAS, NOT SVG, AND NOT A CHART LIBRARY. recharts is already here and is the right tool for
// the admin charts, which draw tens of marks from a fixed schema; this draws up to 1500 nodes and
// 3000 edges that move sixty times a second while the layout settles and again on every frame of
// a rotation. As SVG that is 4500 DOM nodes being re-attributed per frame, which stalls the whole
// modal on a mid-range laptop. Canvas draws the same picture in one pass with no DOM at all.
//
// THE LAYOUT IS IN graph3d.ts and is written rather than imported - see that file for why
// d3-force could not come with us into a third dimension, and for the Barnes-Hut octree that
// replaces the one thing it was genuinely worth having.
//
// MONOCHROME, AND IT READS AS A STARFIELD. Colour used to carry TYPE, through a six-step muted
// palette plus a neutral for the long tail. That was information nobody was reading: a reader
// looking at a knowledge graph is asking which things MATTER and what connects to what, and the
// answer to the first is DEGREE - how many relationships a node has - which the picture spent its
// only strong visual channel not saying. Type is still there, on the node you select.
//
// So one hue, and three channels all carrying the same thing: a node with more connections is
// BIGGER, BRIGHTER, and glows further. That is legible pre-attentively, at any zoom, without a
// legend - the hubs are the bright stars and the leaves are the dust around them.
//
// -- WHAT CHANGED WITH THE THIRD DIMENSION, and why each one is a behaviour change rather than a
//    rendering one ----------------------------------------------------------------------------
//
// NOTHING IS DIMMED ANY MORE. Hovering used to drop every unrelated node to 12% alpha and every
// unrelated edge to 6%, which turns a picture of a graph into a picture of one node's
// neighbourhood - the surrounding structure, which is the reason to draw a graph at all, simply
// went away while you were pointing at something. Every node now sits at 80% and the related ones
// rise to 100%. The contrast between those two is enough to follow, and the graph stays on screen
// while you follow it.
//
// EDGES KEEP THEIR WEIGHT. A related edge used to jump to the accent colour at 0.9 alpha against
// neighbours at 0.06, so the eye read a bright asterisk rather than a highlighted path. A related
// edge is now the same line, slightly brighter. Thickness never changes - it means corroboration
// count, and a line that thickens on hover is telling you something false.
//
// SELECTION IS STICKY. Hover is a preview; a CLICK holds. That is what makes this navigable:
// select a node, see what it connects to, click one of those, and walk the graph a hop at a time.
// Previously the panel followed the pointer and let go the moment you moved, so there was no way
// to read a node's detail and then act on it.
//
// NO RING ON THE SELECTED NODE. The crisp circle was the selection affordance back when hover was
// the only state and everything else was dimmed to nothing. With a stable selection, brightness,
// the detail panel and the depth filter all say which node it is, and a hard geometric edge on one
// star in a field of soft ones reads as a rendering artefact.

const STAR_DARK = '198 232 245'
const STAR_LIGHT = '30 74 92'

/**
 * The sky the stars sit in, dark mode only.
 *
 * <p>NOT A TOKEN, and deliberately so - the same exception STAR_DARK above already is. The
 * semantic surfaces describe a UI made of panels stacked on a page; `--canvas`, the darkest of
 * them, is rgb(17 24 26), which is a dark INTERFACE rather than a night sky. What makes a
 * photograph of a star field read as depth is that the space between the stars is essentially
 * black, and there is no token for that because nothing else in the product wants one.
 */
const SKY_DARK = 'rgb(7 10 13)'

/**
 * The floor every node sits at, and the reason there is no dimmed state.
 *
 * <p>0.8 is the right number for a reason beyond being asked for: the step from 0.8 to 1.0 is
 * small in absolute terms but it is the ONLY thing changing, so it reads clearly, while 0.8 is
 * still bright enough that the rest of the graph is genuinely legible rather than merely present.
 */
const BASE_BRIGHTNESS = 0.8

/**
 * Below this brightness a node is drawn as a bare point, with no spikes.
 *
 * <p>That is what the reference photographs do, and it is not a stylistic choice in them: a
 * diffraction spike is the telescope's own optics smeared across a bright source, so only sources
 * bright enough to saturate produce one. Giving every node spikes would turn a starfield into a
 * field of asterisks.
 */
const SPIKE_THRESHOLD = 0.5

/** How much of the gap to a target an eased value closes each frame. */
const EASE = 0.14
// The layout's cooling: each frame's heat is the last one's times ALPHA_DECAY, and it stops moving
// below ALPHA_MIN - about two hundred frames from a cold start.
const ALPHA_DECAY = 0.976
const ALPHA_MIN = 0.008

function readToken(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return raw ? `rgb(${raw})` : fallback
}

function readTokenRgb(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return raw || fallback
}

function isDarkMode(): boolean {
  return document.documentElement.getAttribute('data-mode') !== 'light'
}

interface Theme {
  dark: boolean
  ink: string
  caption: string
  line: string
  accent: string
  canvas: string
  accentRgb: string
  star: string
}

function readTheme(): Theme {
  const dark = isDarkMode()
  return {
    dark,
    ink: readToken('--ink-primary', dark ? 'rgb(204 232 242)' : 'rgb(22 78 99)'),
    caption: readToken('--ink-caption', dark ? 'rgb(177 187 190)' : 'rgb(76 90 95)'),
    line: readToken('--line', dark ? 'rgb(49 59 63)' : 'rgb(216 221 223)'),
    accent: readToken('--brand-accent', dark ? 'rgb(120 190 217)' : 'rgb(32 111 141)'),
    canvas: SKY_DARK,
    accentRgb: readTokenRgb('--brand-accent', dark ? '120 190 217' : '32 111 141'),
    star: dark ? STAR_DARK : STAR_LIGHT,
  }
}

/** Re-reads the tokens whenever the theme attribute flips, so the graph does not stay dark on a
 *  page that has gone light. Cheap: one getComputedStyle per toggle, never per frame. */
function useGraphTheme(): Theme {
  const [theme, setTheme] = useState<Theme>(() => readTheme())
  useEffect(() => {
    const observer = new MutationObserver(() => setTheme(readTheme()))
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-mode', 'data-shade'] })
    return () => observer.disconnect()
  }, [])
  return theme
}

/**
 * How big a star is, in WORLD units, from its DEGREE - with mentions as a tie-breaker.
 *
 * <p>Degree first because that is what the picture is FOR. Mentions count how often the extractor
 * saw a name; degree counts how much of the graph runs through it, and a hub with three
 * relationships matters more to a reader than a name repeated ten times in one paragraph.
 *
 * <p>sqrt on both, so a node with a hundred connections is ten times the AREA of one with a single
 * connection rather than a hundred times the radius - which is how a circle is actually read.
 */
function baseRadius(node: Node3D): number {
  return 1.3 + Math.sqrt(node.degree * 3 + Math.max(node.mentions, 1)) * 0.95
}

/** 0..1, saturating: past about a dozen connections a node is simply "a hub" and more of them
 *  should not keep making it whiter than the label beside it. */
function brightnessOf(degree: number): number {
  return 0.3 + 0.7 * Math.min(1, Math.sqrt(degree) / 3.5)
}

export interface GraphView {
  /** How many hops from the SELECTED node stay visible. 0 means "no limit". */
  maxDepth: number
  spread: SpreadOptions
  autoRotate: boolean
  showLabels: boolean
  /**
   * The most stars to draw - the most connected ones, chosen on the server.
   *
   * <p>THE ONE CONTROL HERE THAT CHANGES WHAT IS FETCHED rather than only what is drawn. A cap is
   * what makes a very large source usable at all: past a few hundred stars the picture stops
   * gaining meaning and starts costing frames, and which few hundred matters far more than how
   * many. A reader who wants the long tail can ask for it.
   */
  maxNodes: number
}

export const DEFAULT_VIEW: GraphView = {
  maxDepth: 0,
  spread: { groupSpread: 1, nodeSpread: 1 },
  autoRotate: false,
  showLabels: true,
  maxNodes: 500,
}

export interface GraphCanvasHandle {
  resetView: () => void
  centerView: () => void
}

export interface GraphCanvasProps {
  /** The picture's accessible name - what it shows, in words ("12 entities, 30 relationships"). */
  label: string
  nodes: GraphNode[]
  edges: GraphEdge[]
  view: GraphView
  /** The node the reader has CLICKED, held until they click another or the background. */
  selected: GraphNode | null
  onSelect?: (node: GraphNode | null) => void
  /** What the pointer is over right now - a preview, cleared when it leaves. */
  onHover?: (node: GraphNode | null) => void
  /** Handed a controller for the toolbar's Reset and Center buttons. */
  onReady?: (handle: GraphCanvasHandle) => void
}

/** One node as it was drawn this frame - the only thing a hit test can honestly work from. */
/**
 * ONE STAR, DRAWN ONCE, REUSED FOR EVERY STAR ON THE SCREEN.
 *
 * <p>The draw loop used to call `createRadialGradient` per star per frame - 1,500 gradient objects
 * sixty times a second, each parsed, allocated and thrown away. That single line was most of the
 * frame time on a large graph, and it is why the view crawled once a source had a real number of
 * entities in it.
 *
 * <p>A gradient whose five stops never change and which differs only in SIZE, COLOUR and ALPHA is a
 * sprite: rendered once into an offscreen canvas at full brightness, then stamped with `drawImage`
 * scaled to the halo, with `globalAlpha` carrying the brightness. Same picture, one allocation per
 * theme colour instead of one per star per frame.
 */
const SPRITE_RADIUS = 64

function starSprite(cache: Map<string, HTMLCanvasElement>, rgb: string): HTMLCanvasElement {
  const hit = cache.get(rgb)
  if (hit) return hit
  const size = SPRITE_RADIUS * 2
  const off = document.createElement('canvas')
  off.width = size
  off.height = size
  const c = off.getContext('2d')!
  const g = c.createRadialGradient(SPRITE_RADIUS, SPRITE_RADIUS, 0, SPRITE_RADIUS, SPRITE_RADIUS, SPRITE_RADIUS)
  // The same five stops the per-star gradient had: most of the brightness in the first fifth of
  // the radius, then a long thin tail. Two stops read as a washed-out blob - see the star pass.
  g.addColorStop(0, `rgb(${rgb} / 1)`)
  g.addColorStop(0.10, `rgb(${rgb} / 0.92)`)
  g.addColorStop(0.35, `rgb(${rgb} / 0.34)`)
  g.addColorStop(0.62, `rgb(${rgb} / 0.10)`)
  g.addColorStop(1, `rgb(${rgb} / 0)`)
  c.fillStyle = g
  c.fillRect(0, 0, size, size)
  cache.set(rgb, off)
  return off
}

interface Projected {
  node: Node3D
  sx: number
  sy: number
  /** Rotated z, for the painter's-algorithm sort. Larger is further away. */
  z: number
  /** Screen radius, after perspective. NOT after zoom - zoom moves stars closer, it does not
   *  grow them. Shared by the renderer and the hit test. */
  r: number
  /** Perspective factor, ~1 at the centre of the scene. */
  persp: number
}

const HOME_CAMERA = { yaw: 0.6, pitch: -0.25, zoom: 1, panX: 0, panY: 0 }

export function GraphCanvas({
  label, nodes, edges, view, selected, onSelect, onHover, onReady,
}: GraphCanvasProps) {
  const theme = useGraphTheme()
  const containerRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)

  // Everything the render loop reads lives in a ref, not in state. A rotation is sixty setState
  // calls a second otherwise, and React re-rendering a component whose only output is one <canvas>
  // element is pure overhead - the pixels are drawn by hand either way.
  const camRef = useRef({ ...HOME_CAMERA })
  // Rotation INERTIA. A drag released while still moving keeps turning and eases to a stop, which
  // is most of what makes a 3D scene feel like an object rather than a diagram.
  const spinRef = useRef({ yaw: 0, pitch: 0 })
  const layoutRef = useRef<Layout | null>(null)
  const alphaRef = useRef(1)
  const hoverRef = useRef<Node3D | null>(null)
  const selectedIdRef = useRef<string | null>(null)
  // Eased 0..1 per node: `glow` is the 80%-to-100% highlight, `shown` is the depth filter's fade.
  const glowRef = useRef(new Map<string, number>())
  const shownRef = useRef(new Map<string, number>())
  // THE REVEAL, since 2026-09-24: stars APPEAR rather than arrive. Every node a layout adds joins
  // `queue` in rank order - most connected first - and each frame releases a few of them into
  // `released`, where `born` eases them in from nothing. A page of 250 used to land in one frame,
  // so however the data was paged the reader saw the graph jump in blocks; released a few per
  // frame, the first page streams in as it is drawn and every page after it keeps the field
  // filling. The layout, the fit and the hit test all know every node from the start - only what
  // is DRAWN waits - so the camera frames the whole graph while it is still appearing.
  const revealRef = useRef({ queue: [] as string[], released: new Set<string>(), born: new Map<string, number>() })
  const frameRef = useRef(0)
  const sizeRef = useRef({ w: 0, h: 0 })

  // DECLARED BEFORE THE EFFECTS THAT READ THEM. The render loop and the pointer handlers both
  // close over these, and both are mounted once with an empty dependency list - so every value
  // they need from a render has to reach them through a ref that already exists.
  const projectedRef = useRef<Projected[]>([])
  // Reused between frames, like the pool above: see the sort in the draw loop.
  const orderRef = useRef<number[]>([])
  // One offscreen star per theme colour - see starSprite.
  const spriteCacheRef = useRef<Map<string, HTMLCanvasElement>>(new Map())
  // The eased camera framing - the centre of the graph and how far it reaches. See the projection
  // block for why this is smoothed rather than read straight off the nodes each frame.
  const frameRefValue = useRef({ x: 0, y: 0, z: 0, extent: 1, fitAdjust: 1, offX: 0, offY: 0 })
  const byId = useMemo(() => new Map(nodes.map(n => [n.id, n])), [nodes])
  const byIdRef = useRef(byId)
  byIdRef.current = byId
  const themeRef = useRef(theme)
  themeRef.current = theme
  const viewRef = useRef(view)
  viewRef.current = view
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect
  const onHoverRef = useRef(onHover)
  onHoverRef.current = onHover
  const onReadyRef = useRef(onReady)
  onReadyRef.current = onReady

  selectedIdRef.current = selected?.id ?? null

  /**
   * REDUCED MOTION: the picture as it ends up, with nothing moving to get there. The simulation is
   * run out here, before the next frame is drawn, rather than ticked one step a frame while the
   * reader watches; the draw loop then releases every star at once and eases nothing. A large
   * graph costs a moment's pause where it would have cost a few seconds of movement.
   */
  const settleIfStill = () => {
    const layout = layoutRef.current
    if (!layout || !prefersReducedMotion()) return
    while (alphaRef.current > ALPHA_MIN) {
      tick(layout, alphaRef.current, viewRef.current.spread)
      alphaRef.current *= ALPHA_DECAY
    }
  }

  // ---------------------------------------------------------------- layout
  // Rebuilt when the DATA changes, or when the reader changes how far apart things should sit -
  // spacing is a property of the layout, not of the camera, so it cannot be applied by zooming.
  useEffect(() => {
    const previous = layoutRef.current
    // A PAGE ARRIVING IS NOT A NEW GRAPH. The data is fetched most-connected-first and appended,
    // so a growing node set that still contains everything the last one held is the SAME picture
    // with more of it - the stars keep their places and the camera stays where the reader put it.
    // Anything else - a different source, a smaller cap, a spacing change - is a new picture and
    // gets a fresh frame.
    const ids = new Set(nodes.map(n => n.id))
    const grew = previous !== null
      && nodes.length > previous.nodes.length
      && previous.nodes.every(n => ids.has(n.id))

    layoutRef.current = buildLayout(nodes, edges, view.spread, previous ?? undefined)
    const reveal = revealRef.current
    if (grew) {
      // A NUDGE, NOT A RESTART. Re-heating to 1 on every page pulled every star already placed
      // back into motion each time more arrived, so the picture never held still while it loaded.
      // Enough heat for the newcomers to find their places; the settled ones barely move.
      alphaRef.current = Math.max(alphaRef.current, 0.35)
      const queued = new Set(reveal.queue)
      for (const n of nodes) {
        if (!reveal.released.has(n.id) && !queued.has(n.id)) reveal.queue.push(n.id)
      }
      settleIfStill()
      return
    }
    alphaRef.current = 1
    // A spacing change re-lays the SAME stars: nothing to reveal again. Anything else is a new
    // picture and appears from nothing.
    const same = previous !== null && nodes.length === previous.nodes.length
      && previous.nodes.every(n => ids.has(n.id))
    if (!same) revealRef.current = { queue: nodes.map(n => n.id), released: new Set(), born: new Map() }

    glowRef.current = new Map()
    shownRef.current = new Map()
    // Snapped, not eased, on a NEW layout: easing from the previous graph's framing would open
    // the modal with the camera flying in from wherever the last one happened to sit.
    frameRefValue.current = { x: 0, y: 0, z: 0, extent: layoutRef.current?.radius ?? 1, fitAdjust: 1, offX: 0, offY: 0 }
    camRef.current.zoom = HOME_CAMERA.zoom
    camRef.current.panX = 0
    camRef.current.panY = 0
    settleIfStill()
  }, [nodes, edges, view.spread])

  // ---------------------------------------------------------------- controller
  // Handed up once, on mount. Through a ref rather than depending on `onReady`, so a parent that
  // passes an inline arrow does not re-register a controller sixty times a second.
  useEffect(() => {
    onReadyRef.current?.({
      resetView: () => {
        camRef.current = { ...HOME_CAMERA }
        spinRef.current = { yaw: 0, pitch: 0 }
        // Reheats the layout too. "Reset view" after the graph has settled oddly should give back
        // the picture the data actually produces, not the camera's opinion of where things ended
        // up - otherwise a reader who has pulled the view about has no way back to it.
        alphaRef.current = 1
        settleIfStill()
      },
      centerView: () => {
        camRef.current.panX = 0
        camRef.current.panY = 0
        camRef.current.zoom = HOME_CAMERA.zoom
        spinRef.current = { yaw: 0, pitch: 0 }
      },
    })
  }, [])

  // ---------------------------------------------------------------- render loop
  useEffect(() => {
    const canvas = canvasRef.current
    const container = containerRef.current
    if (!canvas || !container) return

    // clientWidth/clientHeight, NOT getBoundingClientRect. The two disagree whenever a CSS
    // transform is in play, and this canvas mounts inside a modal that scales in: the rect
    // reports the TRANSFORMED size, so the first measurement caught the panel mid-animation at
    // about 0.98 and sized the canvas to 1002x663 inside a 1022x677 container. ResizeObserver
    // never corrected it, because a transform changes nothing about layout and so fires no
    // callback - the canvas simply stayed 20px narrow for the life of the modal.
    // The canvas fills its container by its classes (w-full h-full); only its backing store - the
    // pixels drawn into, scaled for the screen - is sized here.
    const resize = () => {
      const w = container.clientWidth
      const h = container.clientHeight
      const dpr = window.devicePixelRatio || 1
      sizeRef.current = { w, h }
      canvas.width = Math.max(1, Math.round(w * dpr))
      canvas.height = Math.max(1, Math.round(h * dpr))
    }
    resize()
    const observer = new ResizeObserver(resize)
    observer.observe(container)

    const uiFont = getComputedStyle(document.body).fontFamily || 'system-ui, sans-serif'

    // Read once per frame: whether anything may move (the reader's reduced-motion setting).
    let still = prefersReducedMotion()

    /** Eases one map entry toward its target and returns the new value - at once, under reduced motion. */
    const ease = (m: Map<string, number>, id: string, target: number, initial: number) => {
      const current = m.get(id) ?? initial
      const next = still ? target : current + (target - current) * EASE
      const settled = Math.abs(next - target) < 0.002 ? target : next
      m.set(id, settled)
      return settled
    }

    const labelOf = (id: string) => {
      const raw = byIdRef.current.get(id)?.name ?? ''
      return raw.length > 28 ? `${raw.slice(0, 25)}...` : raw
    }

    const draw = () => {
      frameRef.current = requestAnimationFrame(draw)
      const ctx = canvas.getContext('2d')
      const layout = layoutRef.current
      const spriteCache = spriteCacheRef.current
      // How far past the canvas edge something may sit and still be drawn. A star's halo reaches
      // several radii past its centre, and an edge crossing the view has both ends outside it.
      const cull = 240
      if (!ctx || !layout) return

      // -- reveal --------------------------------------------------------
      // A share of what is waiting, never fewer than three: a long queue drains in about half a
      // second (30 frames), a short one still streams in visibly rather than blinking on.
      still = prefersReducedMotion()
      const reveal = revealRef.current
      if (reveal.queue.length > 0) {
        // Under reduced motion every star is there at once.
        const take = still ? reveal.queue.length : Math.max(3, Math.ceil(reveal.queue.length / 30))
        for (const id of reveal.queue.splice(0, take)) reveal.released.add(id)
      }

      const t = themeRef.current
      const v = viewRef.current
      const { w, h } = sizeRef.current
      const dpr = window.devicePixelRatio || 1
      const cam = camRef.current

      // -- simulation, while it is still settling --------------------------
      // Ticked inside the render loop rather than run to completion up front: the reader watches
      // the graph arrange itself, which both looks right and means a large graph never blocks the
      // frame that opens the modal. Once alpha decays this costs nothing and only the camera moves.
      if (alphaRef.current > ALPHA_MIN) {
        tick(layout, alphaRef.current, v.spread)
        alphaRef.current *= ALPHA_DECAY
      }

      // -- camera ----------------------------------------------------------
      if (v.autoRotate) cam.yaw += 0.0016
      cam.yaw += spinRef.current.yaw
      cam.pitch += spinRef.current.pitch
      // Friction on the flick, so a release coasts and stops rather than spinning forever - and
      // under reduced motion it stops where it was let go.
      spinRef.current.yaw *= still ? 0 : 0.94
      spinRef.current.pitch *= still ? 0 : 0.94
      if (Math.abs(spinRef.current.yaw) < 1e-5) spinRef.current.yaw = 0
      if (Math.abs(spinRef.current.pitch) < 1e-5) spinRef.current.pitch = 0
      // Pitch stops just short of straight up. At exactly +/-90 degrees the scene has been rotated
      // onto its own axis and further yaw does nothing visible, which reads as the drag breaking.
      const limit = Math.PI / 2 - 0.05
      cam.pitch = Math.max(-limit, Math.min(limit, cam.pitch))

      ctx.save()
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.clearRect(0, 0, w, h)
      // A DEEP FIELD TO LOOK INTO, in dark mode only. In light mode the stars are drawn dark on
      // light (see STAR_LIGHT) and a dark fill would hide every one of them.
      if (t.dark) {
        ctx.fillStyle = t.canvas
        ctx.fillRect(0, 0, w, h)
      }

      // -- projection ------------------------------------------------------
      // PERSPECTIVE, NOT ORTHOGRAPHIC, and it is the whole reason this reads as a volume: with no
      // convergence a rotating 3D scatter looks like a 2D scatter being sheared.
      //
      // `camDist` is how far the eye sits from the centre of the scene, in world units, scaled to
      // the scene so the amount of convergence is the same whether the graph is twelve nodes or
      // twelve hundred. `persp` is the near/far factor every screen position and every radius is
      // multiplied by; it is clamped below so a node that rotates behind the eye is squashed
      // toward the centre rather than flipped through infinity.
      // FRAMED ON WHERE THE NODES ACTUALLY ARE, not on the radius they were seeded inside.
      //
      // `layout.radius` is the sphere the COMMUNITY CENTRES were placed on, which is an input to
      // the simulation and not a description of its output: repulsion pushes outward, the springs
      // pull in, and where it settles is neither. Framing on the input left the graph sitting in
      // one corner with half the canvas empty - the picture was correct and most of the screen was
      // not being used, which is the whole complaint this view started from.
      //
      // Both the centroid and the extent are EASED rather than applied outright, because they move
      // every frame while the layout is settling and a camera that tracked them exactly would
      // swim. By the time alpha decays they have stopped, and this costs one pass over the nodes.
      // THE CENTRE OF THE BOX, NOT THE CENTRE OF MASS - the maintainer's "always take the center
      // of the sphere as the base point". A knowledge graph is lopsided by nature: one community
      // holds most of the nodes, so the centre of mass sits inside that clump, and rotating about
      // it swings the whole picture around a point well off the middle of what you can see. The
      // midpoint of the extents is the centre of the sphere the reader is actually looking at, and
      // it is what both the pivot and the framing use.
      let wMinX = Infinity, wMinY = Infinity, wMinZ = Infinity
      let wMaxX = -Infinity, wMaxY = -Infinity, wMaxZ = -Infinity
      for (const n of layout.nodes) {
        if (n.x < wMinX) wMinX = n.x
        if (n.x > wMaxX) wMaxX = n.x
        if (n.y < wMinY) wMinY = n.y
        if (n.y > wMaxY) wMaxY = n.y
        if (n.z < wMinZ) wMinZ = n.z
        if (n.z > wMaxZ) wMaxZ = n.z
      }
      if (!layout.nodes.length) { wMinX = wMinY = wMinZ = wMaxX = wMaxY = wMaxZ = 0 }
      const midX = (wMinX + wMaxX) / 2, midY = (wMinY + wMaxY) / 2, midZ = (wMinZ + wMaxZ) / 2
      let extent = 1
      for (const n of layout.nodes) {
        const dx = n.x - midX, dy = n.y - midY, dz = n.z - midZ
        extent = Math.max(extent, Math.sqrt(dx * dx + dy * dy + dz * dz))
      }
      const framed = frameRefValue.current
      // Snapped rather than eased under reduced motion: nothing is settling to swim after.
      const follow = still ? 1 : 0.08
      framed.x += (midX - framed.x) * follow
      framed.y += (midY - framed.y) * follow
      framed.z += (midZ - framed.z) * follow
      framed.extent += (extent - framed.extent) * follow

      const radius = framed.extent
      const camDist = radius * 2.4 + 260
      // The starting guess, corrected below against what was actually drawn. A sphere's widest
      // point is its radius, but a graph's nodes do not reach the edge of the sphere they were
      // seeded in and the projection foreshortens what is left - so a formula over the radius is
      // always an underestimate, and it was leaving two fifths of a wide canvas empty.
      const fit = ((Math.min(w, h) * 0.42) / radius) * framed.fitAdjust
      const worldToScreen = fit * cam.zoom
      // framed.offX/offY are the SCREEN-space centring correction - see the auto-fit block below.
      // The world centroid is not the projected centre: perspective spreads what is near the eye
      // more than what is far from it, so a scene centred on its centre of mass still sits
      // off-frame, which is what was clipping the top cluster.
      const cx = w / 2 + cam.panX + framed.offX
      const cy = h / 2 + cam.panY + framed.offY

      // POOLED AND INDEX-ALIGNED with layout.nodes, never rebuilt. A graph of 1,500 nodes was
      // allocating 1,500 objects per frame - 90,000 short-lived objects a second for the collector
      // to sweep while the reader drags the scene around.
      const projected = projectedRef.current
      if (projected.length !== layout.nodes.length) {
        projected.length = 0
        for (const n of layout.nodes) projected.push({ node: n, sx: 0, sy: 0, z: 0, r: 0, persp: 1 })
      }
      for (let i = 0; i < layout.nodes.length; i++) {
        const n = layout.nodes[i]
        // Centred before rotating, so the scene turns about the middle of the graph rather than
        // about wherever the origin happens to have ended up.
        const r3 = rotatePoint(
          { x: n.x - framed.x, y: n.y - framed.y, z: n.z - framed.z }, cam.yaw, cam.pitch,
        )
        const persp = camDist / Math.max(camDist * 0.25, camDist + r3.z)
        const slot = projected[i]
        slot.node = n
        slot.sx = cx + r3.x * worldToScreen * persp
        slot.sy = cy + r3.y * worldToScreen * persp
        slot.z = r3.z
        // ZOOM MOVES THE STARS, IT DOES NOT GROW THEM (maintainer, 2026-09-23). Only `persp` is
        // here: a star nearer the camera is genuinely larger, and zooming in brings the field
        // closer - through `worldToScreen` above - without inflating each source. It used to
        // carry sqrt(zoom) as well, which made leaning in feel like the dots were swelling in
        // place rather than the viewer moving through them.
        slot.r = Math.max(0.6, baseRadius(n) * persp)
        slot.persp = persp
      }
      // Two array reads instead of two hash lookups: Node3D carries its own position in
      // layout.nodes, so an edge reaches its endpoints without a string-keyed Map - one that used
      // to be allocated and filled from scratch on every frame.
      const posAt = (n: Node3D) => projected[n.index]

      // -- what is highlighted, and what the depth filter leaves visible ---
      const focusId = selectedIdRef.current ?? hoverRef.current?.id ?? null
      const related = focusId ? layout.neighbours.get(focusId) : undefined
      // The depth filter applies to the SELECTION only, never to a hover. A hover is a preview and
      // must not restructure the picture under the pointer - moving the mouse across a graph that
      // hid two thirds of itself each time would be unusable.
      const withinDepth = (selectedIdRef.current && v.maxDepth > 0)
        ? hopsFrom(selectedIdRef.current, layout.neighbours, v.maxDepth)
        : null

      // Ease every node's two animated values once, before anything is drawn, so the edge pass and
      // the star pass agree about what is visible this frame.
      const glow = glowRef.current
      const shown = shownRef.current
      const born = reveal.born
      for (const p of projected) {
        const id = p.node.id
        const isActive = id === focusId || !!related?.has(id)
        ease(glow, id, isActive ? 1 : BASE_BRIGHTNESS, BASE_BRIGHTNESS)
        ease(shown, id, !withinDepth || withinDepth.has(id) ? 1 : 0, 1)
        ease(born, id, reveal.released.has(id) ? 1 : 0, 0)
      }
      // What is DRAWN: the depth filter's fade times the reveal. The fit below reads `shown`
      // alone on purpose - see the reveal note at revealRef.
      const drawnOf = (id: string) => (shown.get(id) ?? 1) * (born.get(id) ?? 0)

      // -- auto-fit, measured from what is actually VISIBLE ----------------
      //
      // A CORRECTION ON THE NEXT FRAME rather than a second projection pass on this one. The
      // honest quantity is "how much of the canvas did the graph actually cover", which cannot be
      // known until it has been projected - so it is measured here, nudged, and applied next time.
      // At 60fps the convergence is invisible and it costs one pass over an array already in cache.
      //
      // Measured with the reader's ZOOM DIVIDED OUT. Without that, zooming in grows the bounds,
      // the fit shrinks to compensate, and the two fight each other to a standstill - the control
      // appears broken rather than slow.
      // AFTER the visibility pass, not before it, and that is the difference between the depth
      // control filtering the graph and the depth control filtering the graph OFF-SCREEN. Framing
      // on every node including the faded ones left a reader who asked for depth 1 looking at
      // three stars in the corner of an empty canvas, with the camera still framed on the whole
      // graph it had just been told to stop showing.
      let visible = 0
      let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
      for (const p of projected) {
        if ((shown.get(p.node.id) ?? 1) < 0.5) continue
        visible++
        if (p.sx < minX) minX = p.sx
        if (p.sx > maxX) maxX = p.sx
        if (p.sy < minY) minY = p.sy
        if (p.sy > maxY) maxY = p.sy
      }
      if (visible > 1) {
        const spanX = Math.max(1, (maxX - minX) / cam.zoom)
        const spanY = Math.max(1, (maxY - minY) / cam.zoom)
        // 0.82 of each axis. The remainder is not padding for its own sake: a label hangs BELOW
        // its star and the halo of a bright node reaches several radii past it, so a graph fitted
        // to exactly the node positions has both clipped at the edges.
        const want = Math.min((w * 0.82) / spanX, (h * 0.82) / spanY)
        // Clamped, because a single outlier can otherwise drive the whole graph down to a speck
        // in the middle of the canvas while one node sits alone at the edge.
        const target = framed.fitAdjust * Math.max(0.4, Math.min(2.5, want))
        const correct = still ? 1 : 0.07
        framed.fitAdjust += (Math.max(0.15, Math.min(12, target)) - framed.fitAdjust) * correct

        // Centred on what was DRAWN, eased the same way and for the same reason. Measured against
        // the canvas centre plus the reader's own pan, so this corrects the projection without
        // ever undoing a deliberate pan - the two are different quantities that happen to add.
        framed.offX += ((w / 2 + cam.panX) - (minX + maxX) / 2) * correct
        framed.offY += ((h / 2 + cam.panY) - (minY + maxY) / 2) * correct
      }

      // Far to near, so a near star is drawn over the one behind it.
      // THE ORDER IS SORTED, NOT THE POOL. Painter's algorithm needs far-to-near, but the pool has
      // to stay index-aligned with layout.nodes for posAt above - so the indices are what gets
      // reordered, in an array that is also reused between frames.
      const order = orderRef.current
      if (order.length !== projected.length) {
        order.length = 0
        for (let i = 0; i < projected.length; i++) order.push(i)
      }
      order.sort((a, b) => projected[b].z - projected[a].z)

      // -- edges -----------------------------------------------------------
      // Drawn first so a node always sits on top of the lines that reach it, and drawn at ONE
      // weight: the thickness means corroboration count, so a line that thickens because the
      // pointer is near it is asserting something untrue.
      ctx.lineCap = 'round'
      for (const l of layout.links) {
        const s = posAt(l.source)
        const e = posAt(l.target)
        if (!s || !e) continue
        // Both ends off-screen: nothing to draw, and on a large graph most edges are. Checked
        // before any style is set, because setting strokeStyle and lineWidth is itself work.
        if ((s.sx < -cull && e.sx < -cull) || (s.sx > w + cull && e.sx > w + cull)
            || (s.sy < -cull && e.sy < -cull) || (s.sy > h + cull && e.sy > h + cull)) continue
        const vis = Math.min(drawnOf(l.source.id), drawnOf(l.target.id))
        if (vis < 0.02) continue
        const touchesFocus = !!focusId && (l.source.id === focusId || l.target.id === focusId)
        // 0.40 is what an unfocused edge has always been. A focused one goes to 0.62 - visible, and
        // nowhere near the 0.9 accent stroke that used to read as a highlighted asterisk.
        ctx.globalAlpha = (touchesFocus ? 0.62 : 0.4) * vis
        ctx.strokeStyle = touchesFocus ? t.accent : t.line
        ctx.lineWidth = Math.min(0.6 + Math.log2(l.weight + 1) * 0.5, 3)
        ctx.beginPath()
        ctx.moveTo(s.sx, s.sy)
        ctx.lineTo(e.sx, e.sy)
        ctx.stroke()
      }

      // -- stars -----------------------------------------------------------
      for (const oi of order) {
        const p = projected[oi]
        const n = p.node
        const vis = drawnOf(n.id)
        if (vis < 0.02) continue
        // Off-screen stars cost nothing. The halo reaches several radii past the centre, so the
        // margin is generous rather than exact - a star clipped at the edge of the canvas is a
        // visible mistake, a star drawn just off it is only wasted work.
        if (p.sx < -cull || p.sx > w + cull || p.sy < -cull || p.sy > h + cull) continue
        const isFocus = n.id === focusId
        const bright = brightnessOf(n.degree)
        // EVERY NODE AT 80%, THE FOCUSED ONES AT 100%, and nothing below 80%. See the header note:
        // the old behaviour dropped everything else to 12% and deleted the graph from the picture
        // while you were looking at it.
        const peak = bright * (glow.get(n.id) ?? BASE_BRIGHTNESS) * vis

        // ONE GRADIENT, CENTRE TO NOTHING - no solid core disc behind it. The disc was what gave
        // every node a hard circular edge: however soft the halo around it, a filled `arc` ends on
        // an aliased boundary and the eye reads that boundary, not the glow. A real star has no
        // edge at all, it just stops being bright.
        //
        // FIVE STOPS RATHER THAN TWO. A linear ramp from opaque to transparent reads as a flat
        // washed-out blob; the eye wants most of the brightness spent in the first fifth of the
        // radius and a long thin tail after it.
        const halo = Math.max(1, p.r * (isFocus ? 6 : 4.6))
        const hue = isFocus ? t.accentRgb : t.star
        // The sprite carries the shape; alpha carries the brightness; drawImage carries the size.
        const sprite = starSprite(spriteCache, hue)
        ctx.globalAlpha = Math.max(0, Math.min(1, peak))
        ctx.drawImage(sprite, p.sx - halo, p.sy - halo, halo * 2, halo * 2)
        ctx.globalAlpha = 1

        // DIFFRACTION SPIKES - the four-pointed flare on the bright stars in any Hubble frame, and
        // the single detail that makes a field of dots read as a photograph of the sky rather than
        // as a scatter plot. Two crossed strokes, each a gradient transparent at both tips and
        // brightest where they meet, which is what gives the taper without drawing a polygon.
        //
        // SIZED IN SCREEN PIXELS, scaled only by perspective. Real spikes come from the instrument,
        // not from the star, so they do not grow when you zoom - but a star genuinely further away
        // is a fainter source, and its spikes shorten with it.
        if (bright > SPIKE_THRESHOLD) {
          const t01 = (bright - SPIKE_THRESHOLD) / (1 - SPIKE_THRESHOLD)
          const len = (9 + 30 * t01) * p.persp
          ctx.lineWidth = 1
          for (const [dx, dy] of [[1, 0], [0, 1]] as const) {
            const x0 = p.sx - dx * len, y0 = p.sy - dy * len
            const x1 = p.sx + dx * len, y1 = p.sy + dy * len
            const spike = ctx.createLinearGradient(x0, y0, x1, y1)
            spike.addColorStop(0, `rgb(${hue} / 0)`)
            spike.addColorStop(0.5, `rgb(${hue} / ${0.5 * peak * t01})`)
            spike.addColorStop(1, `rgb(${hue} / 0)`)
            ctx.strokeStyle = spike
            ctx.beginPath()
            ctx.moveTo(x0, y0)
            ctx.lineTo(x1, y1)
            ctx.stroke()
          }
        }

        // NO RING ON THE SELECTED NODE - see the header note. Deliberately nothing here.
      }

      // -- labels ----------------------------------------------------------
      // LAST AND SPARINGLY. Every node labelled is an unreadable mat of text at any zoom that fits
      // the whole graph, so a label appears when the node is big enough to carry one at the current
      // scale - which means zooming in reveals more of them, the way a map does. The focused node
      // and its neighbours are always labelled, because those are the ones being read.
      if (v.showLabels) {
        ctx.textAlign = 'center'
        ctx.textBaseline = 'top'
        // The UI face, not the system one. A canvas cannot read a CSS variable, so the body's
        // resolved family is read once per effect and used here - the labels used to be the only
        // text in the product not set in it.
        ctx.font = `12px ${uiFont}`
        for (const p of projected) {
          const n = p.node
          const vis = drawnOf(n.id)
          if (vis < 0.25) continue
          const isFocus = n.id === focusId
          const isRelated = !!related?.has(n.id)
          // Against the ZOOM-SCALED radius, not the drawn one: the star no longer grows when you
          // lean in (see the projection above), and without this the set of labelled nodes could
          // never change - which is the whole behaviour this gate exists for.
          if (!isFocus && !isRelated && p.r * Math.sqrt(cam.zoom) < 5) continue
          const label = labelOf(n.id)
          if (!label) continue
          ctx.globalAlpha = (isFocus ? 1 : 0.85) * vis
          ctx.fillStyle = isFocus ? t.ink : t.caption
          ctx.fillText(label, p.sx, p.sy + p.r + 4)
        }
      }
      ctx.restore()
    }

    frameRef.current = requestAnimationFrame(draw)
    return () => {
      cancelAnimationFrame(frameRef.current)
      observer.disconnect()
    }
  }, [])

  // ---------------------------------------------------------------- pointer handling
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    /**
     * The node under a screen point.
     *
     * <p>Hit tested in SCREEN space against the last projected frame, not in world space. In 3D
     * there is no inverse of the projection that answers "which node did I click" - several sit on
     * the same ray - so the only honest answer is the nearest one to the cursor among those
     * actually drawn, which is what the reader is pointing at.
     *
     * <p>It reads `p.r`, the same screen radius the renderer drew with, so the pickable area can
     * never drift from the visible one as perspective and zoom change it.
     */
    const nodeAt = (clientX: number, clientY: number): Node3D | null => {
      const rect = canvas.getBoundingClientRect()
      const px = clientX - rect.left
      const py = clientY - rect.top
      let best: Node3D | null = null
      let bestDist = Infinity
      for (const p of projectedRef.current) {
        // Not yet revealed - nothing is drawn there, so nothing can be pointed at.
        if (!revealRef.current.released.has(p.node.id)) continue
        // +7, like the 2D version: the pad absorbs the small radius so the click target does not
        // shrink with the dot. A star you cannot hit is a worse picture.
        const r = p.r + 7
        const d = (p.sx - px) ** 2 + (p.sy - py) ** 2
        if (d <= r * r && d < bestDist) { best = p.node; bestDist = d }
      }
      return best
    }

    let rotating = false
    let panning = false
    let moved = false
    let last = { x: 0, y: 0 }

    const onPointerDown = (e: PointerEvent) => {
      canvas.setPointerCapture(e.pointerId)
      moved = false
      last = { x: e.clientX, y: e.clientY }
      // Middle button or shift PANS; a plain drag ROTATES.
      //
      // DRAGGING A NODE TO MOVE IT IS GONE. In three dimensions a screen-space drag has no single
      // world-space answer - the pointer names a ray, not a point - and the 2D version released
      // rather than pinned the node anyway ("a node left fixed where it was dropped turns the
      // layout into a scrapbook of wherever anyone has ever poked it"). So the gesture was buying
      // a momentary nudge at the price of the rotation this whole view exists for.
      panning = e.button === 1 || e.shiftKey
      rotating = !panning
      spinRef.current = { yaw: 0, pitch: 0 }
    }

    const onPointerMove = (e: PointerEvent) => {
      const dx = e.clientX - last.x
      const dy = e.clientY - last.y
      if (rotating || panning) {
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) moved = true
        last = { x: e.clientX, y: e.clientY }
        if (panning) {
          camRef.current.panX += dx
          camRef.current.panY += dy
        } else {
          const yaw = dx * 0.006
          const pitch = dy * 0.006
          camRef.current.yaw += yaw
          camRef.current.pitch += pitch
          // Carried into the flick, so releasing mid-drag coasts instead of stopping dead.
          spinRef.current = prefersReducedMotion() ? { yaw: 0, pitch: 0 } : { yaw: yaw * 0.55, pitch: pitch * 0.55 }
        }
        return
      }
      const node = nodeAt(e.clientX, e.clientY)
      if (node?.id !== hoverRef.current?.id) {
        hoverRef.current = node
        onHoverRef.current?.(node ? byIdRef.current.get(node.id) ?? null : null)
      }
      // The cursor says whether there is a star to click, by class (no inline style).
      canvas.classList.toggle('cursor-pointer', !!node)
      canvas.classList.toggle('cursor-grab', !node)
    }

    const onPointerUp = (e: PointerEvent) => {
      if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId)
      // A CLICK IS A DRAG THAT DID NOT MOVE. Without the distance test every rotation that happened
      // to start on a star would also select it, and every rotation that started on empty space
      // would clear the selection the reader was working from.
      if (!moved) {
        const node = nodeAt(e.clientX, e.clientY)
        onSelectRef.current?.(node ? byIdRef.current.get(node.id) ?? null : null)
      }
      rotating = false
      panning = false
    }

    const onPointerLeave = () => {
      if (hoverRef.current) {
        hoverRef.current = null
        onHoverRef.current?.(null)
      }
    }

    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const cam = camRef.current
      cam.zoom = Math.max(0.2, Math.min(8, cam.zoom * Math.exp(-e.deltaY * 0.0015)))
    }

    canvas.addEventListener('pointerdown', onPointerDown)
    canvas.addEventListener('pointermove', onPointerMove)
    canvas.addEventListener('pointerup', onPointerUp)
    canvas.addEventListener('pointercancel', onPointerUp)
    canvas.addEventListener('pointerleave', onPointerLeave)
    canvas.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      canvas.removeEventListener('pointerdown', onPointerDown)
      canvas.removeEventListener('pointermove', onPointerMove)
      canvas.removeEventListener('pointerup', onPointerUp)
      canvas.removeEventListener('pointercancel', onPointerUp)
      canvas.removeEventListener('pointerleave', onPointerLeave)
      canvas.removeEventListener('wheel', onWheel)
    }
  }, [])

  return (
    <div ref={containerRef} className="absolute inset-0">
      {/* A picture with a name: the counts in words. Its stars are reached by pointer only -
          there is no keyboard path through them. */}
      <canvas ref={canvasRef} role="img" aria-label={label} className="block w-full h-full touch-none cursor-grab" />
    </div>
  )
}
