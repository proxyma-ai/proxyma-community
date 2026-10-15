import { File as FileIcon } from 'lucide-react'
import type { GlyphComponent } from '@/components/ui/kit'
import { sourceTypeIcon } from '@/features/indexing/utils/sourceMarks'
import { catalogTypeCategory } from '@/features/indexing/utils/catalogRegistry'

// A mention chip's GLYPH, shared by the composer's chips (MentionRichInput) and the sent message's
// (MessageBubble), so a source keeps its look once the message is sent (maintainer's feedback,
// 2026-10-03 - the sent copy had lost it).
//
// THE GLYPH IS THE TYPE'S MARK (sourceMarks.tsx), the same one its card and tab show; the CHIP is
// the kit's `solid` chip in the brand fill (5.7:1 under its white words in every appearance), one
// look for every mention and every skill. Until 2026-10-14 each catalog category had a raw
// Tailwind hue of its own - amber for files, cyan for docs, a dozen more - which no token covered,
// which no appearance re-mapped, and five of which measured under 4.5:1 under the chip's 12px
// white text (amber-600 for files, the commonest mention, 3.2:1). The mark says what kind of thing
// a mention names; the colour said it a second time, illegibly. In the @ list the mark is in the
// row's caption ink, as every mark in a menu is.
//
// A name whose type was never seen (a rewind right after a fresh load, a source since removed)
// takes the plain file glyph rather than a guess.
export function mentionIcon(type: string | undefined): GlyphComponent {
  return type && catalogTypeCategory(type) ? sourceTypeIcon(type) : FileIcon
}
