import { useState } from 'react'
import { CitationList, CitationRow, CitationsFold } from '@/components/ui/kit'
import { SourceTypeMark } from '@/features/indexing/utils/sourceMarks'
import { catalogTypeName } from '@/features/indexing/utils/catalogRegistry'
import { useConnectorCatalog } from '@/features/indexing/hooks/useConnectorCatalog'
import { useTranslation } from '@/i18n'

interface Source {
  source: string
  score: number
  type: string
}

interface Props {
  sources: Source[]
}

// Where an answer came from: the kit's fold ("4 sources >" over a hairline), then the kit's
// CitationList of CitationRows (CHAT-35) - its type's mark (the one map, sourceMarks.tsx), its
// name, the type in words for a screen reader, and how well it matched. The site's scenes draw the
// same rows.
export function SourceCitations({ sources }: Props) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  // Subscribed, so the type names arrive with the catalog.
  useConnectorCatalog()
  if (!sources.length) return null
  // A citation names its type by the enum (WEB_PAGE); the catalog by its wire name (webpage). The
  // catalog's English name, translated - the enum itself only for a type the catalog does not list.
  const typeName = (type: string) => {
    const name = catalogTypeName(type) ?? catalogTypeName(type.toLowerCase().replace(/_/g, ''))
    return name ? t(name) : type
  }
  return (
    <CitationsFold
      label={t('{count} source|{count} sources', { count: sources.length })}
      open={open}
      onToggle={() => setOpen(o => !o)}
    >
      <CitationList>
        {sources.map((s, i) => (
          <CitationRow
            key={i}
            mark={<SourceTypeMark type={s.type.toLowerCase()} size={14} />}
            typeLabel={typeName(s.type)}
            name={s.source}
            score={`${(s.score * 100).toFixed(0)}%`}
          />
        ))}
      </CitationList>
    </CitationsFold>
  )
}
