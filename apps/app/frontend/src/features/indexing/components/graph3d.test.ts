import assert from 'node:assert/strict'
import { test } from 'node:test'

import { adjacency, buildLayout, communities, communityCentres, fibonacciSphere, hopsFrom, rotatePoint, tick } from './graph3d.ts'
import type { GraphEdge, GraphNode } from '../types/indexing.types.ts'

/**
 * The graph layout's pure parts, under `node --test` with no test runner.
 *
 * <p>Same arrangement as libs/ui-kit/i18n: plain `.ts` with no JSX, so Node strips the types
 * natively and there is nothing to configure. That is exactly why the layout lives in its own
 * module rather than inside GraphCanvas - the canvas needs a DOM and a rendering context and could
 * not be reached this way, while the maths is where every off-by-one actually lives.
 *
 * <p><b>This is the first frontend test in the repository</b>, which is why `tools/ci/gate.bat`
 * names this file alongside the two i18n ones. Note the explicit `.ts` extensions on the imports:
 * type stripping does not resolve extensionless specifiers, which is the same reason the i18n
 * runtime imports look that way.
 */

const node = (id: string, mentions = 1): GraphNode =>
  ({ id, name: id, type: null, mentions, degree: 0 })

const edge = (source: string, target: string, weight = 1): GraphEdge =>
  ({ source, target, predicate: 'relates to', description: null, weight, documentId: null })

// -- adjacency ---------------------------------------------------------------

test('adjacency is symmetric and ignores an edge naming a node outside the view', () => {
  const known = new Set(['a', 'b'])
  const adj = adjacency([edge('a', 'b'), edge('b', 'ghost')], known)
  assert.deepEqual([...adj.get('a')!], ['b'])
  assert.deepEqual([...adj.get('b')!], ['a'])
  assert.equal(adj.has('ghost'), false)
})

test('a self-edge does not make a node its own neighbour', () => {
  const adj = adjacency([edge('a', 'a')], new Set(['a']))
  assert.equal(adj.get('a')?.size ?? 0, 0)
})

// -- hop counting, which is what the depth control means ---------------------

test('hopsFrom counts the origin as zero and stops at the requested depth', () => {
  // a - b - c - d, a chain, so each hop is unambiguous.
  const adj = adjacency([edge('a', 'b'), edge('b', 'c'), edge('c', 'd')], new Set(['a', 'b', 'c', 'd']))
  const two = hopsFrom('a', adj, 2)
  assert.equal(two.get('a'), 0)
  assert.equal(two.get('b'), 1)
  assert.equal(two.get('c'), 2)
  assert.equal(two.has('d'), false, 'depth 2 must not reach three hops away')
})

/**
 * The shortest path wins, which is the whole reason this is breadth-first rather than a walk. A
 * depth-first count would report the long way round for a node that is also adjacent, and the
 * depth filter would then hide something one relationship away.
 */
test('hopsFrom reports the shortest path when there are two', () => {
  const adj = adjacency(
    [edge('a', 'b'), edge('b', 'c'), edge('a', 'c')],
    new Set(['a', 'b', 'c']),
  )
  assert.equal(hopsFrom('a', adj, 3).get('c'), 1)
})

test('hopsFrom on an isolated node returns only itself', () => {
  const adj = adjacency([], new Set(['a']))
  assert.deepEqual([...hopsFrom('a', adj, 3).keys()], ['a'])
})

// -- communities -------------------------------------------------------------

/**
 * Two triangles joined by a single edge is the standard smallest case with real community
 * structure: every node is far better connected inside its own triangle than across the bridge.
 */
test('label propagation separates two clusters joined by one edge', () => {
  const ids = ['a1', 'a2', 'a3', 'b1', 'b2', 'b3']
  const adj = adjacency([
    edge('a1', 'a2'), edge('a2', 'a3'), edge('a3', 'a1'),
    edge('b1', 'b2'), edge('b2', 'b3'), edge('b3', 'b1'),
    edge('a1', 'b1'),
  ], new Set(ids))
  const g = communities(ids, adj)
  assert.equal(g.get('a2'), g.get('a3'), 'a2 and a3 are in the same triangle')
  assert.equal(g.get('b2'), g.get('b3'), 'b2 and b3 are in the same triangle')
  assert.notEqual(g.get('a2'), g.get('b2'), 'the two triangles are different communities')
})

/**
 * DETERMINISM IS A REQUIREMENT, NOT AN ACCIDENT. The sweep order is shuffled - label propagation
 * with a fixed order oscillates and never converges - but the shuffle is seeded from the node
 * count, so the same graph lays out the same way every time. A reader who closes and reopens this
 * modal must not be shown a different picture of one source.
 */
test('the same graph produces the same communities twice', () => {
  const ids = ['a', 'b', 'c', 'd', 'e', 'f']
  const adj = adjacency([
    edge('a', 'b'), edge('b', 'c'), edge('c', 'a'),
    edge('d', 'e'), edge('e', 'f'), edge('f', 'd'),
  ], new Set(ids))
  assert.deepEqual([...communities(ids, adj)], [...communities(ids, adj)])
})

test('an isolated node is its own community rather than being absorbed', () => {
  const ids = ['a', 'b', 'lonely']
  const adj = adjacency([edge('a', 'b')], new Set(ids))
  const g = communities(ids, adj)
  assert.notEqual(g.get('lonely'), g.get('a'))
})

test('community indices are dense and ordered biggest first', () => {
  const ids = ['a', 'b', 'c', 'solo']
  const adj = adjacency([edge('a', 'b'), edge('b', 'c'), edge('c', 'a')], new Set(ids))
  const g = communities(ids, adj)
  assert.equal(g.get('a'), 0, 'the three-node cluster is the biggest, so it is group 0')
  assert.equal(g.get('solo'), 1)
})

// -- the sphere the communities sit on ---------------------------------------

test('one community sits at the origin rather than orbiting nothing', () => {
  assert.deepEqual(fibonacciSphere(1, 500), [{ x: 0, y: 0, z: 0 }])
})

test('every community centre lands on the sphere of the requested radius', () => {
  for (const p of fibonacciSphere(12, 400)) {
    const r = Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
    assert.ok(Math.abs(r - 400) < 1e-6, `expected radius 400, got ${r}`)
  }
})

/**
 * The point of the golden-angle spiral over a lat/long grid. With a handful of communities, a grid
 * bunches them at the poles and the galaxies end up in a ring instead of a field.
 */
test('the centres are spread rather than bunched', () => {
  const pts = fibonacciSphere(16, 100)
  let closest = Infinity
  for (let i = 0; i < pts.length; i++) {
    for (let j = i + 1; j < pts.length; j++) {
      const dx = pts[i].x - pts[j].x, dy = pts[i].y - pts[j].y, dz = pts[i].z - pts[j].z
      closest = Math.min(closest, Math.sqrt(dx * dx + dy * dy + dz * dz))
    }
  }
  // On a radius-100 sphere, 16 evenly spread points are all well over 30 apart. A polar-bunched
  // arrangement puts several within a few units of each other.
  assert.ok(closest > 30, `nearest pair was ${closest.toFixed(1)} apart`)
})

// -- the camera --------------------------------------------------------------

test('rotating by nothing changes nothing', () => {
  const p = { x: 3, y: -4, z: 5 }
  const r = rotatePoint(p, 0, 0)
  assert.ok(Math.abs(r.x - 3) < 1e-9 && Math.abs(r.y + 4) < 1e-9 && Math.abs(r.z - 5) < 1e-9)
})

test('rotation preserves length, so nothing drifts as the scene turns', () => {
  const p = { x: 3, y: -4, z: 12 }
  const before = Math.sqrt(3 * 3 + 4 * 4 + 12 * 12)
  const r = rotatePoint(p, 0.7, -0.4)
  const after = Math.sqrt(r.x * r.x + r.y * r.y + r.z * r.z)
  assert.ok(Math.abs(before - after) < 1e-9, `${before} became ${after}`)
})

/**
 * YAW ALONE MUST NOT CHANGE HEIGHT. This is what "two angles, never a trackball" buys: the
 * vertical axis stays vertical, so dragging sideways turns the scene like a globe instead of
 * rolling it. A star field that has rolled reads as tilted rather than as turned, and there is no
 * horizon to correct it by.
 */
test('yaw leaves the vertical axis alone', () => {
  const r = rotatePoint({ x: 10, y: 7, z: 0 }, 1.1, 0)
  assert.ok(Math.abs(r.y - 7) < 1e-9)
})

// -- the simulation ----------------------------------------------------------

const smallGraph = () => {
  const nodes = ['a', 'b', 'c', 'd'].map(id => node(id))
  const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'a'), edge('c', 'd')]
  return { nodes, edges }
}

test('buildLayout counts degree from the edges actually in the view', () => {
  const { nodes, edges } = smallGraph()
  const layout = buildLayout(nodes, edges, { groupSpread: 1, nodeSpread: 1 })
  const byId = new Map(layout.nodes.map(n => [n.id, n]))
  assert.equal(byId.get('c')!.degree, 3)
  assert.equal(byId.get('d')!.degree, 1)
})

test('buildLayout drops a self-edge rather than creating a zero-length spring', () => {
  const layout = buildLayout([node('a')], [edge('a', 'a')], { groupSpread: 1, nodeSpread: 1 })
  assert.equal(layout.links.length, 0)
})

test('the same graph lays out identically twice - no Math.random anywhere', () => {
  const { nodes, edges } = smallGraph()
  const spread = { groupSpread: 1, nodeSpread: 1 }
  const one = buildLayout(nodes, edges, spread)
  const two = buildLayout(nodes, edges, spread)
  for (let i = 0; i < 40; i++) { tick(one, 0.5, spread); tick(two, 0.5, spread) }
  assert.deepEqual(
    one.nodes.map(n => [n.x, n.y, n.z]),
    two.nodes.map(n => [n.x, n.y, n.z]),
  )
})

/**
 * The one thing a force layout must never do. Repulsion falls off with the square of the distance,
 * so a node thrown out of the frame by one hot frame never comes back - which is what the
 * per-step cap in `tick` exists to prevent, and what a NaN anywhere in the maths would look like.
 */
test('nothing escapes to infinity or becomes NaN while settling', () => {
  const nodes = Array.from({ length: 60 }, (_, i) => node(`n${i}`, 1 + (i % 5)))
  const edges = nodes.slice(1).map((n, i) => edge(nodes[i].id, n.id))
  const spread = { groupSpread: 1, nodeSpread: 1 }
  const layout = buildLayout(nodes, edges, spread)
  let alpha = 1
  for (let i = 0; i < 300; i++) { tick(layout, alpha, spread); alpha *= 0.976 }
  for (const n of layout.nodes) {
    assert.ok(Number.isFinite(n.x) && Number.isFinite(n.y) && Number.isFinite(n.z),
      `${n.id} went to ${n.x},${n.y},${n.z}`)
    const d = Math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
    assert.ok(d < layout.radius * 40, `${n.id} is ${d.toFixed(0)} from the origin`)
  }
})

/**
 * Two nodes at the identical position is the case the octree has a depth cap for - without it the
 * tree subdivides forever looking for a cell that separates them, and the tab locks up.
 */
test('coincident nodes are pushed apart rather than hanging the octree', () => {
  const spread = { groupSpread: 1, nodeSpread: 1 }
  const layout = buildLayout([node('a'), node('b')], [], spread)
  layout.nodes[0].x = layout.nodes[0].y = layout.nodes[0].z = 0
  layout.nodes[1].x = layout.nodes[1].y = layout.nodes[1].z = 0
  for (let i = 0; i < 30; i++) tick(layout, 1, spread)
  const [a, b] = layout.nodes
  const d = Math.sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2)
  assert.ok(Number.isFinite(d), 'the layout produced NaN from a zero-distance pair')
})

test('an empty graph is laid out without throwing', () => {
  const spread = { groupSpread: 1, nodeSpread: 1 }
  const layout = buildLayout([], [], spread)
  tick(layout, 1, spread)
  assert.equal(layout.nodes.length, 0)
})

/**
 * The spacing controls have to reach the LAYOUT, not the camera - zooming cannot separate two
 * nodes that were placed on top of each other. A bigger group spread must produce a bigger sphere.
 */
test('group spread widens the sphere the communities sit on', () => {
  const { nodes, edges } = smallGraph()
  const tight = buildLayout(nodes, edges, { groupSpread: 1, nodeSpread: 1 })
  const loose = buildLayout(nodes, edges, { groupSpread: 2, nodeSpread: 1 })
  assert.ok(loose.radius > tight.radius * 1.5, `${tight.radius} -> ${loose.radius}`)
})

// -- where the stars go ------------------------------------------------------
//
// Reported from a real graph: "most of the stars are placed at the bottom of the sphere, not the
// centre". Communities arrive ranked biggest-first and the old placement put index 0 on a POLE of
// a shell, so the one community holding most of a real graph landed at one end of an empty sphere.

test('the biggest community is the core, not a point on the surface', () => {
  const centres = communityCentres(12, 500)
  const first = centres[0]
  assert.equal(Math.hypot(first.x, first.y, first.z), 0,
    'the largest community must sit at the centre of the sphere')
})

test('communities fill the volume rather than sitting on one shell', () => {
  const radius = 500
  const centres = communityCentres(40, radius)
  const distances = centres.slice(1).map(c => Math.hypot(c.x, c.y, c.z))
  const near = distances.filter(d => d < radius * 0.6).length
  const far = distances.filter(d => d >= radius * 0.6).length
  // A shell put every one of them at the same distance; a ball has them at many.
  assert.ok(near > 0 && far > 0, `expected a spread of radii, got ${distances.join(', ')}`)
  assert.ok(Math.max(...distances) <= radius + 1e-6, 'nothing may sit outside the sphere')
})

// COUNTING HEMISPHERES IS NOT ENOUGH, and a released bug proved it. The first version of this
// test counted how many centres sat above and below the middle and passed with exactly half on
// each side - while the cloud reached 497 units DOWN and 187 UP, because the radius grew with the
// same index the direction swept. Half the points were high and CLOSE, half were low and FAR. So
// these two assert on REACH and on the centre of mass, which are the things the eye actually
// reads.
test('the cloud reaches as far one way as the other on every axis', () => {
  const radius = 500
  const centres = communityCentres(200, radius)
  for (const axis of ['x', 'y', 'z'] as const) {
    const values = centres.map(c => c[axis])
    const up = Math.max(...values)
    const down = -Math.min(...values)
    assert.ok(Math.min(up, down) > Math.max(up, down) * 0.6,
      `${axis} is lopsided: ${down.toFixed(0)} one way, ${up.toFixed(0)} the other`)
  }
})

test('the communities are centred on the origin, where the core sits', () => {
  const radius = 500
  const centres = communityCentres(200, radius)
  const mean = (axis: 'x' | 'y' | 'z') =>
    centres.reduce((a, c) => a + c[axis], 0) / centres.length
  const drift = Math.hypot(mean('x'), mean('y'), mean('z'))
  // The core is placed at the origin, so a cloud whose centre of mass is somewhere else drags the
  // camera's framing off the very stars it is meant to be built around.
  assert.ok(drift < radius * 0.05,
    `centre of mass is ${drift.toFixed(0)} from the origin, on a radius of ${radius}`)
})

test('a lopsided graph does not pile its nodes at one end of the sphere', () => {
  // One community of forty, three of two - the shape label propagation actually produces.
  const nodes: GraphNode[] = []
  const edges: GraphEdge[] = []
  for (let i = 0; i < 40; i++) {
    nodes.push({ id: `big${i}`, name: `big ${i}`, type: 'thing', mentions: 1, degree: 0 })
    if (i > 0) edges.push({ source: 'big0', target: `big${i}`, predicate: 'has', weight: 1 })
  }
  for (let g = 0; g < 3; g++) {
    nodes.push({ id: `s${g}a`, name: `s${g}a`, type: 'thing', mentions: 1, degree: 0 })
    nodes.push({ id: `s${g}b`, name: `s${g}b`, type: 'thing', mentions: 1, degree: 0 })
    edges.push({ source: `s${g}a`, target: `s${g}b`, predicate: 'has', weight: 1 })
  }

  const layout = buildLayout(nodes, edges, { groupSpread: 1, nodeSpread: 1 })
  const ys = layout.nodes.map(n => n.y)
  const mid = (Math.min(...ys) + Math.max(...ys)) / 2
  const below = ys.filter(y => y < mid).length
  // Before this, the forty-node community was seeded at a pole and everything sat in one half.
  assert.ok(below > layout.nodes.length * 0.2 && below < layout.nodes.length * 0.8,
    `${below} of ${layout.nodes.length} nodes below the midpoint`)
})

// -- a page arriving is not a new graph ---------------------------------------
//
// The view is fetched most-connected-first and appended, so buildLayout runs again on every page.
// If it re-seeded everything, the picture would jump each time - stars the reader is looking at
// would be thrown somewhere else while they watched.

test('stars already placed keep their positions when a page is appended', () => {
  const first: GraphNode[] = []
  const edges: GraphEdge[] = []
  for (let i = 0; i < 10; i++) {
    first.push({ id: `n${i}`, name: `n ${i}`, type: 'thing', mentions: 1, degree: 0 })
    if (i > 0) edges.push({ source: 'n0', target: `n${i}`, predicate: 'has', weight: 1 })
  }
  const spread = { groupSpread: 1, nodeSpread: 1 }
  const before = buildLayout(first, edges, spread)
  // Settled a little, so the carried positions are ones the physics produced rather than the
  // deterministic seed - which a re-seed would reproduce exactly and the test would not notice.
  for (let i = 0; i < 20; i++) tick(before, 0.5, spread)
  const held = new Map(before.nodes.map(n => [n.id, { x: n.x, y: n.y, z: n.z }]))

  const second = [...first]
  for (let i = 10; i < 15; i++) {
    second.push({ id: `n${i}`, name: `n ${i}`, type: 'thing', mentions: 1, degree: 0 })
    edges.push({ source: 'n0', target: `n${i}`, predicate: 'has', weight: 1 })
  }
  const after = buildLayout(second, edges, spread, before)

  for (const n of after.nodes) {
    const was = held.get(n.id)
    if (!was) continue
    assert.deepEqual({ x: n.x, y: n.y, z: n.z }, was, `${n.id} moved when a page was appended`)
  }
  assert.equal(after.nodes.length, 15)
  for (const n of after.nodes) {
    assert.ok(Number.isFinite(n.x) && Number.isFinite(n.y) && Number.isFinite(n.z),
      `${n.id} has no position`)
  }
})

test('a layout built without a previous one is unchanged', () => {
  const nodes: GraphNode[] = [
    { id: 'a', name: 'a', type: 'thing', mentions: 1, degree: 0 },
    { id: 'b', name: 'b', type: 'thing', mentions: 1, degree: 0 },
  ]
  const edges: GraphEdge[] = [{ source: 'a', target: 'b', predicate: 'has', weight: 1 }]
  const spread = { groupSpread: 1, nodeSpread: 1 }
  // Seeded from the node id, so two builds of the same graph have to agree - a reader who closes
  // and reopens a source must not be shown a different picture of it.
  const one = buildLayout(nodes, edges, spread)
  const two = buildLayout(nodes, edges, spread)
  assert.deepEqual(one.nodes.map(n => [n.x, n.y, n.z]), two.nodes.map(n => [n.x, n.y, n.z]))
})

test('the connected stars sit in the middle, not off to one side', () => {
  // The shape a real source produces, and the one that was reported: a connected core plus a wide
  // field of isolated entities nothing linked. Label propagation makes each of those its own
  // community, so they are what decides where the sphere's middle ends up.
  const nodes: GraphNode[] = []
  const edges: GraphEdge[] = []
  for (let i = 0; i < 60; i++) {
    nodes.push({ id: `hub${i}`, name: `hub ${i}`, type: 'thing', mentions: 3, degree: 0 })
    if (i > 0) edges.push({ source: `hub${i - 1}`, target: `hub${i}`, predicate: 'has', weight: 1 })
  }
  for (let i = 0; i < 140; i++) {
    nodes.push({ id: `dust${i}`, name: `dust ${i}`, type: 'thing', mentions: 1, degree: 0 })
  }

  const layout = buildLayout(nodes, edges, { groupSpread: 1, nodeSpread: 1 })
  const all = layout.nodes
  const mid = (axis: 'x' | 'y' | 'z') => {
    const values = all.map(n => n[axis])
    return (Math.min(...values) + Math.max(...values)) / 2
  }
  const span = Math.max(...(['x', 'y', 'z'] as const).map(axis => {
    const values = all.map(n => n[axis])
    return Math.max(...values) - Math.min(...values)
  }))

  const linked = layout.nodes.filter(n => n.degree > 0)
  const centre = (axis: 'x' | 'y' | 'z') =>
    linked.reduce((a, n) => a + n[axis], 0) / linked.length
  const off = Math.hypot(centre('x') - mid('x'), centre('y') - mid('y'), centre('z') - mid('z'))

  // Reported from a real graph: the bright, connected stars drew low and to one side while the
  // dust filled everything above them. The camera frames the whole field, so what this asserts is
  // that the part worth looking at is in the middle of that frame.
  assert.ok(off < span * 0.15,
    `the connected stars sit ${off.toFixed(0)} from the middle of a field ${span.toFixed(0)} across`)
})
