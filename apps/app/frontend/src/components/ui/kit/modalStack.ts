/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/modalStack.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * The decisions behind every dialog's keyboard behavior, with no React and no DOM in them.
 *
 * <p>ONE STACK FOR THE WHOLE PAGE. Before this, each dialog that handled Escape added its own
 * document listener, none of them stopped the event, and so two stacked dialogs both closed on a
 * single keypress - the one the user was looking at and the one beneath it, which usually held
 * the unsaved work. A stack answers the only question that matters ("which dialog is on top?")
 * in one place, and `useModalLayer` routes every Escape, Enter and Tab through it.
 *
 * <p>Kept in its own plain `.ts` so `node --test` can reach it with no runner and no DOM: see
 * `modalStack.test.ts` beside it. It must stay strippable - no enums, no parameter properties.
 */

/** Whatever a layer carries. The stack only orders them; it never looks inside. */
export interface ModalStack<T> {
  /** Adds a layer on top and returns it, so the caller can hand the same reference to `remove`. */
  push: (layer: T) => T
  /** Removes a layer wherever it is. A dialog beneath the top can unmount first. */
  remove: (layer: T) => void
  /** The layer that receives keys, or undefined when no dialog is open. */
  top: () => T | undefined
  size: () => number
}

/**
 * `levelOf` ranks a layer ABOVE every layer of a lower level, whatever order they opened in; within
 * one level the last opened is on top. Most dialogs are level 0. It exists for the few surfaces
 * that are drawn above everything and must also receive the keys above everything - a restart
 * screen, the maintenance gate, the onboarding tour - even when a dialog underneath them happens to
 * mount afterwards, which the tour itself causes by opening Settings.
 */
export function createModalStack<T>(levelOf: (layer: T) => number = () => 0): ModalStack<T> {
  const layers: T[] = []
  return {
    push(layer) {
      // Re-pushing a layer moves it to the top rather than listing it twice; a double
      // registration would otherwise survive its own removal.
      const at = layers.indexOf(layer)
      if (at !== -1) layers.splice(at, 1)
      layers.push(layer)
      return layer
    },
    remove(layer) {
      const at = layers.indexOf(layer)
      if (at !== -1) layers.splice(at, 1)
    },
    top: () => {
      let best: T | undefined
      let bestLevel = -Infinity
      // Walking forward and taking ">=" makes the LAST opened layer win a tie.
      for (const layer of layers) {
        const level = levelOf(layer)
        if (level >= bestLevel) {
          best = layer
          bestLevel = level
        }
      }
      return best
    },
    size: () => layers.length,
  }
}

/**
 * What the focused element is, as far as Enter is concerned.
 *
 * - `activator`: a button, link or summary. Enter already clicks it natively; submitting as
 *   well would run two actions for one key.
 * - `multiline`: a textarea or contenteditable region. Enter is a newline there.
 * - `form`: an input inside a real form, which the browser already submits on Enter.
 * - `choice`: a select, whose Enter belongs to the picker.
 * - `field`: a single-line input outside a form - Enter here means "done".
 * - `other`: the panel itself, the page, anything else.
 */
export type EnterTarget = 'activator' | 'multiline' | 'form' | 'choice' | 'field' | 'other'

export interface KeyInput {
  key: string
  defaultPrevented: boolean
  /** An IME is mid-composition: Enter commits the composition and Escape cancels it. */
  isComposing: boolean
  shiftKey: boolean
  ctrlKey: boolean
  altKey: boolean
  metaKey: boolean
  /**
   * The key is auto-repeating because it is being held. An Enter held down on the button that
   * OPENED a confirmation would otherwise go straight on to confirm it.
   */
  repeat: boolean
  /** False while the dialog must not close - a save in flight, a restart, a mandatory notice. */
  closable: boolean
  /** Whether the top dialog asked for Enter to submit it. */
  hasSubmit: boolean
  target: EnterTarget
}

/**
 * - `close`: ask the top dialog to close (which may itself ask for confirmation first).
 * - `swallow`: consume the key and do nothing - Escape over a dialog that cannot close must not
 *   fall through to anything beneath it.
 * - `submit`: run the top dialog's submit action.
 * - `none`: not ours; leave the event alone.
 */
export type KeyAction = 'close' | 'swallow' | 'submit' | 'none'

export function keyAction(input: KeyInput): KeyAction {
  // Something nearer the focus already handled it - an open Select, a menu, an inline rename.
  // That is the whole contract with them: a component that consumes Escape calls
  // preventDefault(), and the dialog beneath it then stays open.
  if (input.defaultPrevented || input.isComposing) return 'none'
  const modified = input.shiftKey || input.ctrlKey || input.altKey || input.metaKey

  if (input.key === 'Escape') {
    if (modified) return 'none'
    return input.closable ? 'close' : 'swallow'
  }

  if (input.key === 'Enter') {
    if (modified || input.repeat || !input.hasSubmit) return 'none'
    return input.target === 'field' || input.target === 'other' ? 'submit' : 'none'
  }

  return 'none'
}

/**
 * Where Tab should send focus inside the top dialog, given `count` focusable elements and the
 * index of the focused one (-1 when focus is on the panel itself or on nothing at all).
 *
 * Returns the index to focus, `'panel'` when there is nothing focusable and focus should rest on
 * the panel, or `null` to let the browser move focus on its own - which it does correctly
 * everywhere except at the two ends, and those two ends are the whole trap.
 */
export function tabTarget(count: number, index: number, backward: boolean): number | 'panel' | null {
  if (count <= 0) return 'panel'
  if (index < 0) return backward ? count - 1 : 0
  if (backward && index === 0) return count - 1
  if (!backward && index >= count - 1) return 0
  return null
}
