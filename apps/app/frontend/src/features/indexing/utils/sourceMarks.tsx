import { BrandMark, isMarkId, markIcon, type GlyphComponent, type MarkId } from '@/components/ui/kit'
import { useConnectorCatalog } from '../hooks/useConnectorCatalog'
import { catalogProtocolMark, catalogTypeMark } from './catalogRegistry'

// THE ONE PLACE a source type becomes a picture. Every surface that shows a type - the Sources
// page's tabs, group headings and Connect menu, a source card, the source dialog's header, the
// composer's @-mentions - reads it from here, so a type cannot have one glyph on a card and another
// in a tab.
//
// WHICH mark is the descriptor's (the connector catalog's `mark`) - a connector names its own, and
// nothing here lists the types. The marks themselves are the kit's (BrandMark.tsx), the same ones
// the landing site draws, and what may be drawn - which vendor marks are licensed, why no Microsoft
// product icon appears - is recorded there. A mark the kit does not have falls back to a generic
// glyph, never to an empty path. The rule carried over with them: every type gets a mark, one size
// and one ink per context, and the type's NAME is always written beside it (the mark is aria-hidden).

/** A source type's mark; a folder for one the catalog has not named (it has not answered yet, or an older server). */
function sourceMarkId(type: string): MarkId {
  // A citation names the type by its enum (WEB_PAGE, TEAMS_CHAT); the catalog by its wire name.
  const mark = catalogTypeMark(type) ?? catalogTypeMark(type.toLowerCase().replace(/_/g, ''))
  return isMarkId(mark) ? mark : 'files'
}

/** A connection's service draws the mark its descriptor names; the generic plug for one it has not. */
export function protocolMarkId(protocol: string): MarkId {
  const mark = catalogProtocolMark(protocol)
  return isMarkId(mark) ? mark : 'plug'
}

// An AI PROVIDER's mark, by the protocol it speaks - a provider row (Settings > AI Providers) leads
// with one the way a connection row does (if one row shape in Settings has a mark, every row of that
// shape has one). Anthropic's is the Claude mark it is published under; OpenAI and Google have no
// mark the kit may draw, so OpenAI, Azure OpenAI and Gemini take the kit's generic glyphs, and every
// other protocol - the gateways and OpenAI-compatible servers included - the API glyph.
const PROVIDER_MARK: Record<string, MarkId> = {
  OPENAI: 'chatgpt',
  ANTHROPIC: 'claude',
  AZURE_OPENAI: 'chatgpt',
  GEMINI: 'gemini',
}

/** A provider protocol with no mark of its own draws the API glyph. */
export function providerMarkId(protocol: string): MarkId {
  return PROVIDER_MARK[protocol] ?? 'api'
}

/** The mark as an icon component, for slots typed to take one (a tab's `icon`). */
export function sourceTypeIcon(type: string): GlyphComponent {
  return markIcon(sourceMarkId(type))
}

/** The mark itself. Ink comes from the caller; the default is the current text color. */
export function SourceTypeMark({ type, size = 16, className }: { type: string; size?: number; className?: string }) {
  // Subscribed, so a mark drawn before the catalog answered is redrawn when it does.
  useConnectorCatalog()
  return <BrandMark id={sourceMarkId(type)} size={size} className={className} />
}

/** A service's mark, subscribed the same way. */
export function ProtocolMark({ protocol, size = 16, className }: { protocol: string; size?: number; className?: string }) {
  useConnectorCatalog()
  return <BrandMark id={protocolMarkId(protocol)} size={size} className={className} />
}
