// Run: node --test libs/ui-kit/react/modalStack.test.ts
//
// Plain `.ts` with no JSX, so Node's own type stripping runs it. The React binding in
// useModalLayer.ts needs a DOM and is not reachable this way; the decisions are, and they are
// where "Escape closed both dialogs" lived.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { createModalStack, keyAction, tabTarget, type KeyInput } from './modalStack.ts'

describe('createModalStack', () => {
  test('the last dialog opened is the top one', () => {
    const stack = createModalStack<string>()
    assert.equal(stack.top(), undefined)
    stack.push('settings')
    stack.push('discard')
    assert.equal(stack.top(), 'discard')
    assert.equal(stack.size(), 2)
  })

  test('closing the top dialog hands keys back to the one beneath it', () => {
    const stack = createModalStack<string>()
    stack.push('settings')
    stack.push('discard')
    stack.remove('discard')
    assert.equal(stack.top(), 'settings')
  })

  test('a dialog beneath the top can unmount without disturbing the top', () => {
    const stack = createModalStack<string>()
    stack.push('a')
    stack.push('b')
    stack.push('c')
    stack.remove('b')
    assert.equal(stack.top(), 'c')
    stack.remove('c')
    assert.equal(stack.top(), 'a')
  })

  test('pushing a layer twice lists it once, on top', () => {
    const stack = createModalStack<string>()
    stack.push('a')
    stack.push('b')
    stack.push('a')
    assert.equal(stack.size(), 2)
    assert.equal(stack.top(), 'a')
    stack.remove('a')
    assert.equal(stack.top(), 'b')
  })

  test('a higher level stays on top of a dialog opened after it', () => {
    const levels: Record<string, number> = { tour: 1, settings: 0, discard: 0, restart: 2 }
    const stack = createModalStack<string>(l => levels[l])
    stack.push('tour')
    stack.push('settings')
    assert.equal(stack.top(), 'tour')
    stack.push('discard')
    assert.equal(stack.top(), 'tour')
    stack.remove('tour')
    assert.equal(stack.top(), 'discard')
    stack.push('restart')
    stack.push('tour')
    assert.equal(stack.top(), 'restart')
  })

  test('removing something never pushed is harmless', () => {
    const stack = createModalStack<string>()
    stack.push('a')
    stack.remove('zzz')
    assert.equal(stack.top(), 'a')
  })
})

const base: KeyInput = {
  key: 'Escape',
  defaultPrevented: false,
  isComposing: false,
  shiftKey: false,
  ctrlKey: false,
  altKey: false,
  metaKey: false,
  repeat: false,
  closable: true,
  hasSubmit: false,
  target: 'other',
}

describe('keyAction - Escape', () => {
  test('closes a closable dialog', () => {
    assert.equal(keyAction(base), 'close')
  })

  test('is swallowed by a dialog that must not close', () => {
    assert.equal(keyAction({ ...base, closable: false }), 'swallow')
  })

  test('is left alone once a menu or Select inside the dialog consumed it', () => {
    assert.equal(keyAction({ ...base, defaultPrevented: true }), 'none')
  })

  test('is left alone while an IME is composing', () => {
    assert.equal(keyAction({ ...base, isComposing: true }), 'none')
  })

  test('with a modifier is not a close', () => {
    assert.equal(keyAction({ ...base, shiftKey: true }), 'none')
  })
})

describe('keyAction - Enter', () => {
  const enter: KeyInput = { ...base, key: 'Enter', hasSubmit: true }

  test('submits from a single-line field or the panel', () => {
    assert.equal(keyAction({ ...enter, target: 'field' }), 'submit')
    assert.equal(keyAction({ ...enter, target: 'other' }), 'submit')
  })

  test('never submits a multi-line editor', () => {
    assert.equal(keyAction({ ...enter, target: 'multiline' }), 'none')
  })

  test('does not double a button or a form the browser already activates', () => {
    assert.equal(keyAction({ ...enter, target: 'activator' }), 'none')
    assert.equal(keyAction({ ...enter, target: 'form' }), 'none')
    assert.equal(keyAction({ ...enter, target: 'choice' }), 'none')
  })

  test('does nothing for a dialog that did not ask for it', () => {
    assert.equal(keyAction({ ...enter, hasSubmit: false }), 'none')
  })

  test('Shift+Enter and Ctrl+Enter are not a submit', () => {
    assert.equal(keyAction({ ...enter, shiftKey: true }), 'none')
    assert.equal(keyAction({ ...enter, ctrlKey: true }), 'none')
  })

  test('is left alone while an IME is composing', () => {
    assert.equal(keyAction({ ...enter, isComposing: true }), 'none')
  })

  test('a held Enter does not run on into a confirmation it just opened', () => {
    assert.equal(keyAction({ ...enter, repeat: true }), 'none')
  })
})

describe('tabTarget', () => {
  test('wraps forward from the last element to the first', () => {
    assert.equal(tabTarget(3, 2, false), 0)
  })

  test('wraps backward from the first element to the last', () => {
    assert.equal(tabTarget(3, 0, true), 2)
  })

  test('lets the browser move focus between the ends', () => {
    assert.equal(tabTarget(3, 1, false), null)
    assert.equal(tabTarget(3, 1, true), null)
    assert.equal(tabTarget(3, 0, false), null)
    assert.equal(tabTarget(3, 2, true), null)
  })

  test('from the panel itself, Tab goes to the first and Shift+Tab to the last', () => {
    assert.equal(tabTarget(3, -1, false), 0)
    assert.equal(tabTarget(3, -1, true), 2)
  })

  test('a dialog with nothing focusable keeps focus on its panel', () => {
    assert.equal(tabTarget(0, -1, false), 'panel')
    assert.equal(tabTarget(0, -1, true), 'panel')
  })

  test('a single element wraps onto itself in both directions', () => {
    assert.equal(tabTarget(1, 0, false), 0)
    assert.equal(tabTarget(1, 0, true), 0)
  })
})
