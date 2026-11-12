// FLOWCHARTS, DRAWN WHERE THE AUTHOR PUT EACH BOX (2026-11-11, the maintainer's style for the YouTube assistant's
// flows: "In each diagram, I always want to see a happy path from top to bottom, and one rule is that for decision
// block, true is going downward, false is sideward").
//
// A diagram is diagrams/<n>.flow.json:
//
//   { "w": 880, "h": 1560,
//     "nodes": [[id, kind, x, y, w, h, who, text], ...],   x, y the CENTER; kind term | proc | dec | db;
//                                                          who code | model | you | store | term
//     "edges": [[from, side, to, side, label?, waypoints?], ...] }   side t | b | l | r
//
// and renders to an inline SVG colored by the page's theme tokens, like the PlantUML figures (plantuml.mjs). The
// build refuses a flow that breaks its rules: an edge naming no node, a decision's Yes leaving anywhere but its
// bottom, its No anywhere but a side, a label on an edge that leaves no decision, or a box whose text cannot fit.

import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { basename, join } from 'node:path'

const FONT = 13
const LINE = 16
// Space Grotesk at 13px: about 0.56em a character, a little more for capitals and wide letters.
const charWidth = c => (/[A-Z@MW]/.test(c) ? 0.72 : /[il.,;:'|!\s]/.test(c) ? 0.3 : 0.56) * FONT

const FILL = {
  code: 'rgb(var(--surface))',
  model: 'rgb(var(--brand-accent) / 0.14)',
  you: 'rgb(var(--success) / 0.14)',
  store: 'rgb(var(--surface-alt))',
  term: 'rgb(var(--surface-alt))',
}
const STROKE = {
  code: 'rgb(var(--ink-caption))',
  model: 'rgb(var(--brand-accent))',
  you: 'rgb(var(--success))',
  store: 'rgb(var(--ink-caption))',
  term: 'rgb(var(--ink-secondary))',
}

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/** A label's lines within `width`, words kept whole. */
export function wrap(text, width) {
  const lines = []
  let line = ''
  let w = 0
  for (const word of String(text).split(/\s+/).filter(Boolean)) {
    const ww = [...word].reduce((a, c) => a + charWidth(c), 0)
    const space = line ? charWidth(' ') : 0
    if (line && w + space + ww > width) {
      lines.push(line)
      line = word
      w = ww
    } else {
      line = line ? line + ' ' + word : word
      w += space + ww
    }
  }
  if (line) lines.push(line)
  return lines
}

const port = (n, s) => s === 't' ? [n.x, n.y - n.h / 2] : s === 'b' ? [n.x, n.y + n.h / 2]
  : s === 'l' ? [n.x - n.w / 2, n.y] : [n.x + n.w / 2, n.y]
const vertical = s => s === 't' || s === 'b'

function route(a, fs, b, ts, via) {
  if (via) return [a, ...via, b]
  if (a[0] === b[0] || a[1] === b[1]) return [a, b]
  if (vertical(fs) && vertical(ts)) { const m = (a[1] + b[1]) / 2; return [a, [a[0], m], [b[0], m], b] }
  if (!vertical(fs) && !vertical(ts)) { const m = (a[0] + b[0]) / 2; return [a, [m, a[1]], [m, b[1]], b] }
  return vertical(fs) ? [a, [a[0], b[1]], b] : [a, [b[0], a[1]], b]
}

const r1 = n => Math.round(n * 10) / 10

function rounded(pts, r = 9) {
  let d = `M${pts[0].map(r1)}`
  for (let i = 1; i < pts.length - 1; i++) {
    const p = pts[i - 1], c = pts[i], n = pts[i + 1]
    const d1 = Math.hypot(c[0] - p[0], c[1] - p[1]), d2 = Math.hypot(n[0] - c[0], n[1] - c[1])
    if (!d1 || !d2) continue
    const rr = Math.min(r, d1 / 2, d2 / 2)
    const a = [c[0] + (p[0] - c[0]) * rr / d1, c[1] + (p[1] - c[1]) * rr / d1]
    const b = [c[0] + (n[0] - c[0]) * rr / d2, c[1] + (n[1] - c[1]) * rr / d2]
    d += ` L${a.map(r1)} Q${c.map(r1)} ${b.map(r1)}`
  }
  return d + ` L${pts[pts.length - 1].map(r1)}`
}

/** One flow as SVG, or throws with every problem it has. */
export function renderFlow(name, flow, title) {
  const problems = []
  const nodes = new Map()
  for (const [id, kind, x, y, w, h, who, text] of flow.nodes) {
    if (nodes.has(id)) problems.push(`node ${id} twice`)
    if (!['term', 'proc', 'dec', 'db'].includes(kind)) problems.push(`node ${id}: kind ${kind}`)
    if (!(who in FILL)) problems.push(`node ${id}: who ${who}`)
    nodes.set(id, { id, kind, x, y, w, h, who, text })
  }
  const parts = []
  const key = name.replace(/[^a-z0-9-]/gi, '')
  parts.push(`<defs><marker id="${key}-ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">`
    + `<path d="M0,0 L10,5 L0,10 z" style="fill:${STROKE.code}"/></marker></defs>`)
  const labels = []
  for (const [from, fs, to, ts, label, via] of flow.edges) {
    const a = nodes.get(from), b = nodes.get(to)
    if (!a || !b) { problems.push(`edge ${from} -> ${to}: no such node`); continue }
    if (a.kind === 'dec') {
      if (label === 'Yes' && fs !== 'b') problems.push(`${from}: Yes must go down`)
      if (label === 'No' && fs === 'b') problems.push(`${from}: No must go to a side`)
      if (label !== 'Yes' && label !== 'No') problems.push(`${from} -> ${to}: a decision's edge is Yes or No`)
    } else if (label) {
      problems.push(`${from} -> ${to}: only a decision's edges carry a label`)
    }
    const p = port(a, fs), q = port(b, ts)
    parts.push(`<path d="${rounded(route(p, fs, q, ts, via))}" style="fill:none;stroke:${STROKE.code};stroke-width:1.5" marker-end="url(#${key}-ah)"/>`)
    if (label) {
      const at = fs === 'b' ? [p[0] + 7, p[1] + 15, 'start'] : fs === 'r' ? [p[0] + 6, p[1] - 7, 'start'] : [p[0] - 6, p[1] - 7, 'end']
      labels.push(`<text x="${r1(at[0])}" y="${r1(at[1])}" text-anchor="${at[2]}" style="font-size:12.5px;font-weight:600;fill:rgb(var(--ink-secondary));paint-order:stroke;stroke:rgb(var(--surface));stroke-width:4px">${esc(label)}</text>`)
    }
  }
  for (const n of nodes.values()) {
    const style = `fill:${FILL[n.who]};stroke:${STROKE[n.who]};stroke-width:${n.who === 'model' || n.who === 'term' ? 2 : 1.5}`
    const x0 = n.x - n.w / 2, y0 = n.y - n.h / 2
    if (n.kind === 'term') parts.push(`<rect x="${x0}" y="${y0}" width="${n.w}" height="${n.h}" rx="${n.h / 2}" style="${style}"/>`)
    else if (n.kind === 'proc') parts.push(`<rect x="${x0}" y="${y0}" width="${n.w}" height="${n.h}" rx="8" style="${style}"/>`)
    else if (n.kind === 'dec') parts.push(`<polygon points="${n.x},${y0} ${x0 + n.w},${n.y} ${n.x},${y0 + n.h} ${x0},${n.y}" style="${style}"/>`)
    else {
      const ry = 9, rx = n.w / 2
      parts.push(`<path d="M${x0},${y0 + ry} A${rx} ${ry} 0 0 1 ${x0 + n.w},${y0 + ry} V${y0 + n.h - ry} A${rx} ${ry} 0 0 1 ${x0},${y0 + n.h - ry} Z" style="${style}"/>`)
      parts.push(`<ellipse cx="${n.x}" cy="${y0 + ry}" rx="${rx}" ry="${ry}" style="${style}"/>`)
    }
    // The text area: a diamond's middle, a cylinder's body below its lid, a box less its padding.
    const tw = n.kind === 'dec' ? n.w * 0.62 : n.w - 16
    const th = n.kind === 'dec' ? n.h * 0.66 : n.h - 6
    const lines = wrap(n.text, tw)
    if (lines.length * LINE > th + 2) problems.push(`${n.id}: "${n.text}" needs ${lines.length} lines; make it ${lines.length * LINE + 8} high or shorter`)
    const cy = n.y + (n.kind === 'db' ? 5 : 0)
    const top = cy - (lines.length * LINE) / 2 + LINE * 0.75
    const weight = n.kind === 'term' ? ';font-weight:600' : ''
    parts.push(`<text text-anchor="middle" style="font-size:${FONT}px;fill:rgb(var(--ink-primary))${weight}">`
      + lines.map((l, i) => `<tspan x="${n.x}" y="${r1(top + i * LINE)}">${esc(l)}</tspan>`).join('') + '</text>')
  }
  if (problems.length) throw new Error(`${name}.flow.json: ${problems.join('; ')}`)
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${flow.w} ${flow.h}" class="uml flow" role="img" aria-label="${esc(title)}"`
    + ` style="max-width:${flow.w}px;min-width:${Math.round(flow.w * 0.8)}px;font-family:var(--font-ui)" preserveAspectRatio="xMidYMin meet">`
    + parts.join('') + labels.join('') + '</svg>'
}

/** Every diagrams/<n>.flow.json rendered, by name; throws listing every flow that breaks a rule. */
export function renderFlows(dir, { titles = {} } = {}) {
  const out = new Map()
  if (!existsSync(dir)) return out
  const problems = []
  for (const f of readdirSync(dir).filter(f => f.endsWith('.flow.json')).sort()) {
    const name = basename(f, '.flow.json')
    try {
      out.set(name, renderFlow(name, JSON.parse(readFileSync(join(dir, f), 'utf8')), titles[name] || name))
    } catch (e) {
      problems.push(e.message)
    }
  }
  if (problems.length) throw new Error(problems.join('\n'))
  return out
}
