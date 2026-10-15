import type { GraphEdge, GraphNode } from '../types/indexing.types'

/**
 * The 3D layout behind GraphCanvas: communities, a sphere of them, and a force simulation in
 * three dimensions.
 *
 * <h2>Why this is written rather than imported</h2>
 *
 * <p>d3-force is 2D and has no third axis to offer - `forceSimulation` carries x/y and vx/vy, and
 * every force it ships operates on those. The layout is the one part of the old 2D canvas that was
 * genuinely worth not writing, specifically because its many-body force is quadtree-approximated;
 * a hand-rolled O(n^2) repulsion is what makes a graph this size crawl. So the thing to carry
 * across into 3D is that approximation, not the library.
 *
 * <p>{@link Octree} below is the Barnes-Hut equivalent in three dimensions: subdivide space, treat
 * a distant cluster of nodes as one body at its centre of mass, and only descend into a cell when
 * it is close enough to matter. At the 1500-node cap this is ~16k node-cell interactions a tick
 * against 2.25M pair interactions, which is the difference between a graph that settles while you
 * watch and one that locks the tab.
 *
 * <h2>Groups of stars, groups of galaxies</h2>
 *
 * <p>A knowledge graph is usually ONE connected component - everything is related to everything
 * eventually - so connected components alone would produce a single ball and no galaxies. The
 * structure that is actually there is COMMUNITY structure: clumps that talk to each other far more
 * than they talk outward.
 *
 * <p>{@link communities} finds them by label propagation, which is the cheapest algorithm that
 * finds real ones: every node takes the label most common among its neighbours, repeatedly, until
 * it stops changing. No parameter to tune, no target count to guess wrong, near-linear, and it
 * falls out to one label per connected component when a graph genuinely has no internal structure.
 *
 * <p>Those communities are then placed on a FIBONACCI SPHERE - the spiral that distributes points
 * on a sphere as evenly as anything can without solving a packing problem - and each one's nodes
 * start in a small cloud around its own point. The forces below keep them there: a community pulls
 * on its own members and on nothing else, so the clumps stay separated while the springs inside
 * each one arrange it.
 */

export interface Node3D {
  id: string
  /**
   * Its position in {@link Layout.nodes}, so the renderer can reach a node's projected screen
   * position by index instead of by a string-keyed Map rebuilt every frame. Set by buildLayout and
   * never changed afterwards.
   */
  index: number
  x: number; y: number; z: number
  vx: number; vy: number; vz: number
  /** Which community it landed in - the index into `centres`. */
  group: number
  /** Edges in this view touching it. Drives size, brightness and spike length. */
  degree: number
  mentions: number
}

export interface Link3D {
  source: Node3D
  target: Node3D
  weight: number
  predicate: string
}

export interface Layout {
  nodes: Node3D[]
  links: Link3D[]
  /** One per community, in community-index order. Only the simulation reads these. */
  centres: { x: number; y: number; z: number }[]
  /** Who is adjacent to whom, for hop counting and highlighting. */
  neighbours: Map<string, Set<string>>
  /** Radius of the sphere everything was laid out inside - the camera frames itself from this. */
  radius: number
}

/** How far apart things sit. 1 is the default; the view options let a reader open it up. */
export interface SpreadOptions {
  /** Multiplies the distance between COMMUNITY centres - the gaps between galaxies. */
  groupSpread: number
  /** Multiplies the natural length of every edge - the gaps between stars within one. */
  nodeSpread: number
}

// -- adjacency ---------------------------------------------------------------

export function adjacency(edges: GraphEdge[], known: Set<string>): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>()
  for (const e of edges) {
    if (!known.has(e.source) || !known.has(e.target)) continue
    if (!out.has(e.source)) out.set(e.source, new Set())
    if (!out.has(e.target)) out.set(e.target, new Set())
    if (e.source === e.target) continue
    out.get(e.source)!.add(e.target)
    out.get(e.target)!.add(e.source)
  }
  return out
}

/**
 * How many hops each node is from `from`, breadth-first, stopping at `maxDepth`.
 *
 * <p>Backs the depth control: a reader who selects a node and asks for depth 2 is asking "what is
 * within two relationships of this". Returns the SOURCE at 0, so a caller can treat "in the map"
 * as "visible" without a special case for the node itself.
 */
export function hopsFrom(
  from: string,
  neighbours: Map<string, Set<string>>,
  maxDepth: number,
): Map<string, number> {
  const depth = new Map<string, number>([[from, 0]])
  let frontier = [from]
  for (let d = 1; d <= maxDepth; d++) {
    const next: string[] = []
    for (const id of frontier) {
      for (const n of neighbours.get(id) ?? []) {
        if (depth.has(n)) continue
        depth.set(n, d)
        next.push(n)
      }
    }
    if (next.length === 0) break
    frontier = next
  }
  return depth
}

// -- communities -------------------------------------------------------------

/**
 * Label propagation. Every node adopts the label most common among its neighbours; repeat until
 * nothing moves or the budget runs out.
 *
 * <p>Nodes are visited in a SHUFFLED order each pass, which is not decoration: label propagation
 * with a fixed order on a symmetric graph oscillates between two labellings and never converges,
 * and the standard answer is to randomise the sweep. The shuffle is seeded from the node count so
 * the same graph lays out the same way twice - a reader who closes and reopens this modal should
 * not be shown a different picture of the same source.
 *
 * <p>An isolated node keeps its own label and becomes a community of one, which is correct: it has
 * nothing to be clustered with, and putting it in someone else's clump would assert a relationship
 * the graph does not contain.
 */
export function communities(ids: string[], neighbours: Map<string, Set<string>>): Map<string, number> {
  const label = new Map<string, string>(ids.map(id => [id, id]))
  const order = [...ids]
  let seed = ids.length * 2654435761 + 1

  const rand = () => {
    // xorshift32 - deterministic, and nothing here needs a better generator than "not the same
    // order every pass".
    seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5
    return (seed >>> 0) / 4294967296
  }

  for (let pass = 0; pass < 12; pass++) {
    for (let i = order.length - 1; i > 0; i--) {
      const j = Math.floor(rand() * (i + 1))
      ;[order[i], order[j]] = [order[j], order[i]]
    }
    let moved = 0
    for (const id of order) {
      const ns = neighbours.get(id)
      if (!ns || ns.size === 0) continue
      const tally = new Map<string, number>()
      for (const n of ns) {
        const l = label.get(n)!
        tally.set(l, (tally.get(l) ?? 0) + 1)
      }
      // A STRICT MAJORITY IS REQUIRED TO SWITCH - the incumbent label wins every tie.
      //
      // Textbook label propagation flips on a tie, and on a small dense graph that is enough to
      // destroy the structure it is meant to find: two triangles joined by a single edge collapsed
      // into ONE community, because a single flip at the bridge cascaded through both. A node with
      // two neighbours inside its own cluster and one outside it must not be talked out of its
      // cluster by the one, and "strictly more" is exactly that rule.
      //
      // It also removes most of the run-to-run instability the shuffle would otherwise cause,
      // since the only decisions left are the ones with a real winner.
      const own = label.get(id)!
      const ownCount = tally.get(own) ?? 0
      let best = own
      let bestCount = ownCount
      for (const [l, c] of tally) {
        if (l === own) continue
        // The second clause only ever compares labels that have already BEATEN the incumbent, so
        // a lexical tie-break here cannot override the majority rule above - it only decides
        // between two challengers, and decides it the same way on every run rather than by Map
        // insertion order.
        if (c > bestCount || (c === bestCount && c > ownCount && l < best)) { best = l; bestCount = c }
      }
      if (best !== label.get(id)) { label.set(id, best); moved++ }
    }
    if (moved === 0) break
  }

  // Dense indices, ordered by size descending, so the biggest community is group 0 and a caller
  // colouring or ordering by group gets a stable, meaningful sequence.
  const size = new Map<string, number>()
  for (const id of ids) size.set(label.get(id)!, (size.get(label.get(id)!) ?? 0) + 1)
  const ranked = [...size.entries()].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))
  const index = new Map(ranked.map(([l], i) => [l, i]))
  return new Map(ids.map(id => [id, index.get(label.get(id)!)!]))
}

/**
 * `count` points spread as evenly as possible over the SURFACE of a sphere of radius `radius`.
 *
 * <p>The golden-angle spiral, which is what makes a sunflower head pack the way it does. A naive
 * lat/long grid bunches its points at the poles, and for a handful of communities that is the
 * difference between galaxies in a ring and galaxies in a field.
 *
 * <p>Kept for the cases that genuinely want a shell. {@link communityCentres} is what the layout
 * uses, and it fills the VOLUME - see the note there for why a shell was the wrong shape.
 */
export function fibonacciSphere(count: number, radius: number) {
  if (count <= 1) return [{ x: 0, y: 0, z: 0 }]
  const points: { x: number; y: number; z: number }[] = []
  const golden = Math.PI * (3 - Math.sqrt(5))
  for (let i = 0; i < count; i++) {
    const y = 1 - (i / (count - 1)) * 2
    const r = Math.sqrt(Math.max(0, 1 - y * y))
    const theta = golden * i
    points.push({ x: Math.cos(theta) * r * radius, y: y * radius, z: Math.sin(theta) * r * radius })
  }
  return points
}

/**
 * Where each community starts: the biggest at the CENTRE, the rest filling the ball around it.
 *
 * <h2>What was wrong with a shell</h2>
 *
 * <p>Communities arrive ranked by size (see {@link communities}), and label propagation on a real
 * knowledge graph nearly always yields ONE community holding most of the nodes. Placing them on a
 * shell by index put that giant community on a POLE - index 0 of the spiral is the pole - so most
 * of the stars ended up bunched at one end of a sphere whose middle was empty. On screen, with the
 * y-axis pointing down, that is a pile at the bottom and nothing in the centre. Reported from a
 * real graph, and visible on any source with more than a few hundred entities.
 *
 * <p>Two things fix it, and both are about matching the picture to the data rather than tuning a
 * constant:
 *
 * <ul>
 *   <li><b>The biggest community is the core.</b> It holds the mass, so it belongs in the middle -
 *       which is also what makes rotation feel like it turns about the thing you are looking at.</li>
 *   <li><b>The rest fill the VOLUME, not a shell.</b> Radius grows with the CUBE ROOT of the
 *       index, because that is what distributes points evenly through a ball: equal counts per
 *       equal volume, rather than equal counts per equal radius, which crowds the middle and
 *       leaves the outside sparse. The golden angle still spreads them around.</li>
 * </ul>
 */
export function communityCentres(count: number, radius: number) {
  if (count <= 1) return [{ x: 0, y: 0, z: 0 }]
  const points: { x: number; y: number; z: number }[] = [{ x: 0, y: 0, z: 0 }]
  const golden = Math.PI * (3 - Math.sqrt(5))
  const outer = count - 1
  for (let i = 0; i < outer; i++) {
    // DIRECTION AND DISTANCE MUST NOT COME FROM THE SAME NUMBER, and this is the whole of it.
    //
    // The spiral used to sweep `y` from the top of the sphere to the bottom as the index grew,
    // while the radius grew with the cube root of that same index - so every NEAR community was a
    // high one and every FAR community a low one, a teardrop rather than a ball. Measured at 200
    // communities: the cloud reached 497 units DOWN and 187 UP, with its centre of mass 51 units
    // below the origin, while exactly half the points sat in each hemisphere. A hemisphere COUNT
    // therefore looked balanced and the picture was not - the core sits at the origin, the origin
    // was high inside that teardrop, and the brightest stars drew above the middle of the frame.
    // The DIRECTION comes from the radical inverse, which jumps about rather than climbing, so
    // where a community sits says nothing about how far out it is.
    const y = 1 - radicalInverse2(i + 1) * 2
    const ring = Math.sqrt(Math.max(0, 1 - y * y))
    const theta = golden * i
    // The DISTANCE comes from the rank, because the rank means connectedness: the most connected
    // group is the core and the unlinked dust ends up in the halo around it. Cube root for volume
    // (equal counts per equal volume); the 0.35 floor keeps the innermost ring clear of the
    // core's own cloud.
    const r = radius * Math.max(0.35, Math.cbrt((i + 0.5) / outer))
    points.push({ x: Math.cos(theta) * ring * r, y: y * r, z: Math.sin(theta) * ring * r })
  }
  return points
}

/**
 * The van der Corput sequence in base 2: the index's bits, reflected about the binary point.
 *
 * <p>1 -> 0.5, 2 -> 0.25, 3 -> 0.75, 4 -> 0.125. It never repeats a value, never returns 0, and
 * fills (0, 1) evenly at every prefix length - which is what makes it usable as a radius while
 * some other sequence walks the directions. Written out rather than imported: it is four lines,
 * and this module has to stay free of dependencies to remain testable under node --test.
 */
function radicalInverse2(index: number): number {
  let n = index
  let f = 1
  let out = 0
  while (n > 0) {
    f /= 2
    out += f * (n % 2)
    n = Math.floor(n / 2)
  }
  return out
}

// -- the octree --------------------------------------------------------------

/**
 * Barnes-Hut in three dimensions.
 *
 * <p>Each cell holds either a single body or eight children, plus the centre of mass and total
 * mass of everything beneath it. Repulsion from a whole cell is taken as one interaction when the
 * cell is far enough away relative to its size - the ratio {@link THETA} - and only descends when
 * it is not.
 */
const THETA = 0.8

class Cell {
  cx = 0; cy = 0; cz = 0     // centre of mass
  mass = 0
  body: Node3D | null = null
  children: (Cell | null)[] | null = null

  // EXPLICIT FIELDS, not constructor parameter properties. `constructor(readonly x: number)` is
  // TypeScript that EMITS code rather than only describing types, so Node's strip-only mode
  // refuses the whole module - which would take graph3d.test.ts with it. Everything in this file
  // has to stay strippable for that test to exist at all.
  readonly x: number
  readonly y: number
  readonly z: number
  readonly half: number

  constructor(x: number, y: number, z: number, half: number) {
    this.x = x
    this.y = y
    this.z = z
    this.half = half
  }

  insert(n: Node3D, depth = 0) {
    // A depth cap rather than an exact tree. Two nodes at the identical position would otherwise
    // subdivide forever; at depth 20 the cell is a millionth of the root and treating the pair as
    // one body is both correct enough and finite.
    if (this.body === null && this.children === null) {
      this.body = n
      this.cx = n.x; this.cy = n.y; this.cz = n.z; this.mass = 1
      return
    }
    if (depth >= 20) {
      this.cx = (this.cx * this.mass + n.x) / (this.mass + 1)
      this.cy = (this.cy * this.mass + n.y) / (this.mass + 1)
      this.cz = (this.cz * this.mass + n.z) / (this.mass + 1)
      this.mass += 1
      return
    }
    if (this.children === null) {
      this.children = [null, null, null, null, null, null, null, null]
      const existing = this.body!
      this.body = null
      this.place(existing, depth)
    }
    this.place(n, depth)
    this.cx = (this.cx * this.mass + n.x) / (this.mass + 1)
    this.cy = (this.cy * this.mass + n.y) / (this.mass + 1)
    this.cz = (this.cz * this.mass + n.z) / (this.mass + 1)
    this.mass += 1
  }

  private place(n: Node3D, depth: number) {
    const i = (n.x > this.x ? 1 : 0) | (n.y > this.y ? 2 : 0) | (n.z > this.z ? 4 : 0)
    const h = this.half / 2
    let child = this.children![i]
    if (!child) {
      child = new Cell(
        this.x + (i & 1 ? h : -h),
        this.y + (i & 2 ? h : -h),
        this.z + (i & 4 ? h : -h),
        h,
      )
      this.children![i] = child
    }
    child.insert(n, depth + 1)
  }

  /** Accumulates the repulsive acceleration `n` feels from everything in this cell. */
  repel(n: Node3D, strength: number, out: { x: number; y: number; z: number }) {
    if (this.mass === 0) return
    let dx = this.cx - n.x, dy = this.cy - n.y, dz = this.cz - n.z
    let d2 = dx * dx + dy * dy + dz * dz
    if (d2 < 1e-6) {
      // Coincident bodies get a deterministic nudge rather than a random one, so the layout stays
      // reproducible between opens.
      dx = ((n.x * 7919) % 1) - 0.5
      dy = ((n.y * 6271) % 1) - 0.5
      dz = ((n.z * 5387) % 1) - 0.5
      d2 = dx * dx + dy * dy + dz * dz + 1e-6
    }
    if (this.body === n && this.mass === 1) return

    if (this.body !== null || (this.half * 2) * (this.half * 2) < THETA * THETA * d2) {
      const d = Math.sqrt(d2)
      const f = (strength * this.mass) / (d2 * d)
      out.x -= dx * f; out.y -= dy * f; out.z -= dz * f
      return
    }
    if (this.children) for (const c of this.children) c?.repel(n, strength, out)
  }
}

// -- the simulation ----------------------------------------------------------

/**
 * Builds the layout and returns it unsettled - {@link tick} does the settling, called from the
 * render loop so the reader watches it arrange itself rather than waiting on a frozen frame.
 */
/**
 * @param previous the layout this one replaces, if any. A node it already holds KEEPS ITS
 *                 POSITION and its velocity instead of being scattered afresh, which is what lets
 *                 a graph arrive a page at a time: the stars already on screen stay where the
 *                 reader is looking at them and the new ones settle in around them. Without it
 *                 every page would re-seed the whole field and the picture would jump four times
 *                 while it loaded.
 */
export function buildLayout(
  nodes: GraphNode[], edges: GraphEdge[], spread: SpreadOptions, previous?: Layout,
): Layout {
  const known = new Set(nodes.map(n => n.id))
  const neighbours = adjacency(edges, known)
  const group = communities(nodes.map(n => n.id), neighbours)
  const groupCount = Math.max(1, ...[...group.values()].map(g => g + 1))

  // The sphere the galaxies sit on. Grows with the square root of the node count - area, not
  // radius, is what has to keep up with how many things are in it - and with the reader's own
  // spread setting on top.
  const radius = (60 + Math.sqrt(nodes.length) * 34) * spread.groupSpread
  const centres = communityCentres(groupCount, radius)

  // THE COMMUNITY INDEX IS A RANK, AND THE RANK IS A DISTANCE. `communities` returns dense
  // indices ordered by size descending, and `communityCentres` puts index 0 at the middle and
  // moves outward from there - so a community of one, which is what an entity nothing linked
  // comes back as, ends up in the halo while the linked clumps hold the centre. That ordering is
  // load-bearing for the picture, not a tidy-up inside `communities`.
  const groupSize = new Map<number, number>()
  for (const id of nodes.map(n => n.id)) {
    const g = group.get(id) ?? 0
    groupSize.set(g, (groupSize.get(g) ?? 0) + 1)
  }

  // Deterministic scatter around each centre. Seeded from the node id so the same graph opens the
  // same way; a Math.random() start makes every open a different picture of one source.
  const hash = (s: string) => {
    let h = 2166136261
    for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619) }
    return (h >>> 0) / 4294967296
  }

  const held = new Map<string, Node3D>()
  if (previous) for (const n of previous.nodes) held.set(n.id, n)

  const byId = new Map<string, Node3D>()
  const out: Node3D[] = nodes.map((n, i) => {
    const g = group.get(n.id) ?? 0
    const c = centres[g]
    // CUBE ROOT of the community's size: a cloud has to grow in volume with what goes into it, and
    // the alternative - one cloud size for every community - is a dense core beside empty space.
    const cloud = (radius * 0.16 + 24) * Math.cbrt(groupSize.get(g) ?? 1)
    // A star already on screen stays where it is - see `previous` above. Its community may have
    // been recomputed from the larger graph, and the forces will carry it there; teleporting it
    // would throw away the arrangement the reader is currently looking at.
    const was = held.get(n.id)
    const node: Node3D = {
      id: n.id,
      index: i,
      x: was ? was.x : c.x + (hash(n.id + 'x') - 0.5) * cloud,
      y: was ? was.y : c.y + (hash(n.id + 'y') - 0.5) * cloud,
      z: was ? was.z : c.z + (hash(n.id + 'z') - 0.5) * cloud,
      vx: was ? was.vx : 0, vy: was ? was.vy : 0, vz: was ? was.vz : 0,
      group: g,
      degree: 0,
      mentions: Math.max(1, n.mentions),
    }
    byId.set(n.id, node)
    return node
  })

  const links: Link3D[] = []
  for (const e of edges) {
    const s = byId.get(e.source)
    const t = byId.get(e.target)
    if (!s || !t || s === t) continue
    links.push({ source: s, target: t, weight: e.weight, predicate: e.predicate })
    s.degree++
    t.degree++
  }

  return { nodes: out, links, centres, neighbours, radius }
}

/**
 * One step of the simulation. `alpha` decays outside, in the render loop.
 *
 * <p>Four forces, and each one is answering a different question:
 *
 * <ul>
 *   <li><b>Repulsion</b> (octree) - nothing may sit on top of anything else. This is what makes the
 *       picture legible at all, and what the Barnes-Hut approximation is for.</li>
 *   <li><b>Springs</b> along edges - related things sit near each other, at a distance set by the
 *       reader's node spread.</li>
 *   <li><b>Community gravity</b> - each node is pulled gently toward ITS OWN community's centre and
 *       no other. This is what keeps the galaxies apart once repulsion starts pushing outward;
 *       without it the clumps drift into one another and the structure the layout found is thrown
 *       away by the physics that was meant to display it.</li>
 *   <li><b>Damping</b> - velocity is retained rather than replaced, so the whole thing eases to a
 *       stop instead of jittering at the end.</li>
 * </ul>
 */
export function tick(layout: Layout, alpha: number, spread: SpreadOptions) {
  const { nodes, links, centres, radius } = layout
  if (nodes.length === 0) return

  // Root cell sized to the current extent, doubled so nothing starts on a boundary.
  let extent = 1
  for (const n of nodes) {
    extent = Math.max(extent, Math.abs(n.x), Math.abs(n.y), Math.abs(n.z))
  }
  const root = new Cell(0, 0, 0, extent * 2)
  for (const n of nodes) root.insert(n)

  // Scaled down as the graph grows, or a thousand nodes push each other outward faster than the
  // community gravity can answer. Same reasoning the 2D version used, one dimension up.
  const repulsion = (2600 + 900 * spread.nodeSpread) * (1 - Math.min(0.55, nodes.length / 2800))
  const acc = { x: 0, y: 0, z: 0 }
  for (const n of nodes) {
    acc.x = 0; acc.y = 0; acc.z = 0
    root.repel(n, repulsion, acc)
    n.vx += acc.x * alpha
    n.vy += acc.y * alpha
    n.vz += acc.z * alpha
  }

  const rest = 52 * spread.nodeSpread
  for (const l of links) {
    const dx = l.target.x - l.source.x
    const dy = l.target.y - l.source.y
    const dz = l.target.z - l.source.z
    const d = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1e-3
    // A heavier edge is a more corroborated relation, so it pulls harder - capped, or one edge
    // stated forty times drags its endpoints into a knot.
    const k = 0.055 * Math.min(2.5, 1 + Math.log2(l.weight + 1) * 0.35)
    const f = ((d - rest) / d) * k * alpha
    const fx = dx * f, fy = dy * f, fz = dz * f
    l.source.vx += fx; l.source.vy += fy; l.source.vz += fz
    l.target.vx -= fx; l.target.vy -= fy; l.target.vz -= fz
  }

  for (const n of nodes) {
    const c = centres[n.group] ?? centres[0]
    // Stronger when there is more than one community, because that is when it has a job. On a
    // single-community graph this is just a weak pull to the origin, which stops isolated nodes
    // being flung to infinity by repulsion alone.
    const g = (centres.length > 1 ? 0.030 : 0.012) * alpha
    n.vx += (c.x - n.x) * g
    n.vy += (c.y - n.y) * g
    n.vz += (c.z - n.z) * g
  }

  const damp = 0.82
  const maxStep = radius * 0.08
  for (const n of nodes) {
    n.vx *= damp; n.vy *= damp; n.vz *= damp
    // A step cap, not a velocity cap: one frame of a very hot simulation can otherwise throw a
    // node clean out of the frame, and it never comes back because repulsion falls off with the
    // square of the distance.
    const step = Math.sqrt(n.vx * n.vx + n.vy * n.vy + n.vz * n.vz)
    if (step > maxStep) {
      const s = maxStep / step
      n.vx *= s; n.vy *= s; n.vz *= s
    }
    n.x += n.vx; n.y += n.vy; n.z += n.vz
  }
}

// -- camera ------------------------------------------------------------------

/**
 * Yaw then pitch, applied as two rotations rather than a quaternion.
 *
 * <p>A trackball would let the scene ROLL, and a star field that has rolled reads as tilted rather
 * than as turned - there is no horizon to tell you which way is up, so it just looks wrong and
 * cannot be corrected by eye. Two angles keep the vertical axis vertical, which is what makes
 * dragging feel like turning a globe.
 */
export function rotatePoint(
  p: { x: number; y: number; z: number }, yaw: number, pitch: number,
) {
  const cy = Math.cos(yaw), sy = Math.sin(yaw)
  const x1 = p.x * cy - p.z * sy
  const z1 = p.x * sy + p.z * cy
  const cp = Math.cos(pitch), sp = Math.sin(pitch)
  const y2 = p.y * cp - z1 * sp
  const z2 = p.y * sp + z1 * cp
  return { x: x1, y: y2, z: z2 }
}
