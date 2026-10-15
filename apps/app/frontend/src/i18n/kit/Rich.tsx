/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/i18n/Rich.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { Fragment, type ReactNode } from 'react'
import { richParts } from './richParts.ts'

/**
 * A TRANSLATED SENTENCE WITH MARKUP IN IT - the one-key form of a sentence that holds a link or a
 * bold word. Translate the WHOLE sentence with its slots and hand the markup in by slot name:
 *
 *     <Rich text={t('Write to {mail}, or read the {manual} first.')} parts={{ mail: <a ...>, manual: <a ...> }} />
 *
 * The catalog keeps one flat entry per sentence, and a language that orders it differently moves
 * the slots with it. See richParts.ts.
 */
export function Rich({ text, parts }: { text: string; parts: Record<string, ReactNode> }) {
  return (
    <>
      {richParts(text, Object.keys(parts)).map((p, i) => (
        <Fragment key={i}>{'slot' in p ? parts[p.slot] : p.text}</Fragment>
      ))}
    </>
  )
}
