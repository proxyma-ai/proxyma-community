import { useState } from 'react'
import { CitationList, CitationRow, CitationsFold } from '@/components/ui/kit'
import { SourceTypeMark } from '@/features/indexing/utils/sourceMarks'
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
  if (!sources.length) return null
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
            typeLabel={s.type}
            name={s.source}
            score={`${(s.score * 100).toFixed(0)}%`}
          />
        ))}
      </CitationList>
    </CitationsFold>
  )
}
