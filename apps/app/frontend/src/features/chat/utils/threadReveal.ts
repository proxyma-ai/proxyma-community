/**
 * WHERE THE THREAD GOES WHEN SOMETHING ELSE SENDS THE READER TO A PLACE IN IT - the agents map's
 * agent names (maintainer's request, 2026-10-14: "click on the agent title to go to the agent
 * section in the main chat interface instead of expand it").
 *
 * A sub-agent goes to its panel in the thread, which opens and lights up briefly
 * (SubAgentPanel); the main agent goes to the latest turn - its question, which is where the main
 * agent's latest work starts.
 *
 * Plain .ts with no imports, so `node --test` runs it.
 */
export type RevealTarget = { kind: 'main' } | { kind: 'agent'; agentId: string }

/** One request to reveal a target. A new object per request, so asking twice reveals twice. */
export interface RevealRequest {
  target: RevealTarget
}

/**
 * The scroller's new scrollTop that shows an element.
 *
 * - The latest turn lands at the top, `clearTop` below the scroller's edge - under the pinned
 *   question's bar and its fade, which would hide the question's first line.
 * - A sub-agent's panel is CENTERED, as the composer's robots put it before the map took over -
 *   but never so high that its header is under that bar, which a panel taller than the window
 *   would be.
 *
 * Clamped to what the scroller can actually reach.
 */
export function revealScrollTop({ kind, top, height, scrollTop, viewHeight, endTop, clearTop }: {
  kind: RevealTarget['kind']
  /** The element's top, relative to the scroller's visible top edge (a bounding-rect difference). */
  top: number
  height: number
  scrollTop: number
  /** The scroller's visible height. */
  viewHeight: number
  /** The largest scrollTop there is (scrollHeight - clientHeight). */
  endTop: number
  clearTop: number
}): number {
  const start = scrollTop + top - clearTop
  const wanted = kind === 'main'
    ? start
    : Math.min(scrollTop + top + height / 2 - viewHeight / 2, start)
  return Math.round(Math.max(0, Math.min(wanted, endTop)))
}

/** Whether a reveal names this sub-agent. */
export function revealsAgent(request: RevealRequest | null | undefined, agentId: string | undefined): boolean {
  return !!request && !!agentId && request.target.kind === 'agent' && request.target.agentId === agentId
}
