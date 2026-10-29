import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Caption, Chip, FieldLabel, FieldSkeleton, Input, Presence, Skeleton } from '@/components/ui/kit'
import { keys } from '@/services/queryKeys'
import { indexingApi } from '../services/indexingApi'
import { useTranslation } from '@/i18n'

// Display labels for the group keys the backend hands back (FilesConnector.EXTENSION_GROUPS).
// The grouping itself is the backend's - it has to stay married to the extension list - but the
// wording is UI copy and belongs here. An unrecognized key falls back to its own name rather than
// vanishing, so adding a group backend-side is never silently invisible.
const GROUP_LABELS: Record<string, string> = {
  office:      'Office documents',
  text:        'Text and data',
  mail:        'Email and e-books',
  code:        'Code and scripts',
  engineering: 'Engineering data',
}

interface Props {
  // Selected extensions, lowercase and dot-less. An empty array means "every supported type" -
  // the same thing a null fileTypes means on the backend - so a folder that never chose anything
  // keeps indexing everything.
  value: string[]
  onChange: (next: string[]) => void
  disabled?: boolean
}

// Shared by the Add Local Folder modal and the connector detail modal, so a folder's file types
// are chosen and later changed through the exact same control.
export function FileTypeFilter({ value, onChange, disabled }: Props) {
  const { t } = useTranslation()
  const { data: catalog, isLoading } = useQuery({
    queryKey: keys.fileTypeCatalog(),
    queryFn: indexingApi.getFileTypeCatalog,
    staleTime: Infinity,
  })

  const groups = useMemo(() => Object.entries(catalog?.groups ?? {}), [catalog])
  // Anything selected that no group offers is, by definition, something the user typed - that is
  // what the custom field shows and edits, so the two halves of this control never fight over the
  // same extension.
  const known = useMemo(() => new Set(groups.flatMap(([, exts]) => exts)), [groups])
  const custom = useMemo(() => value.filter(v => !known.has(v)), [value, known])

  const toggle = (ext: string) => {
    onChange(value.includes(ext) ? value.filter(v => v !== ext) : [...value, ext])
  }

  const toggleGroup = (exts: string[]) => {
    const allOn = exts.every(e => value.includes(e))
    onChange(allOn ? value.filter(v => !exts.includes(v)) : [...new Set([...value, ...exts])])
  }

  // The custom field owns only the extensions no group offers; the checkbox selections are
  // preserved around it rather than replaced, so typing here can never silently clear them.
  const setCustom = (raw: string) => {
    const typed = [...new Set(raw.split(',')
      .map(s => s.trim().toLowerCase().replace(/^\.+/, ''))
      .filter(Boolean))]
    // Unchanged is not a change: leaving the field without editing it must not reorder the
    // selection and read as an edit.
    if (typed.join(',') === custom.join(',')) return
    onChange([...value.filter(v => known.has(v)), ...typed])
  }

  // THE FIELD IS CONTROLLED: what is typed is held here until it is committed (on blur or Enter),
  // and follows the selection whenever that changes from outside. It showed the selection as it
  // was when the dialog opened, so after "Index every supported type" it still read "json, log",
  // and the next blur put those two back - the source then indexed only them, with nothing on
  // screen saying why.
  const customText = custom.join(', ')
  const [draft, setDraft] = useState(customText)
  useEffect(() => setDraft(customText), [customText])

  if (isLoading) return <FileTypeFilterSkeleton />

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <FieldLabel as="p" className="mb-0">{t('File types to index')}</FieldLabel>
        <Presence show={value.length > 0} collapse={false}>
          <Button variant="quiet" onClick={() => onChange([])} disabled={disabled}>
            {t('Index every supported type')}
          </Button>
        </Presence>
      </div>

      <div className="space-y-2 min-w-0">
        {groups.map(([key, exts]) => {
          const allOn = exts.every(e => value.includes(e))
          return (
            <div key={key}>
              <div className="flex items-baseline gap-2">
                <Caption as="span" className="font-medium text-ink-secondary">{t(GROUP_LABELS[key] ?? key)}</Caption>
                <Button variant="quiet" onClick={() => toggleGroup(exts)} disabled={disabled}>
                  {allOn ? t('None') : t('All')}
                </Button>
              </div>
              {/* Selection is carried by the chip's pressed fill alone: a check mark inside the
                  chip made the row reflow as chips were toggled, and the pressed state already
                  says "on" unambiguously. */}
              <div className="mt-2 flex flex-wrap gap-2">
                {exts.map(ext => (
                  <Chip
                    key={ext}
                    variant="filter"
                    label={ext}
                    pressed={value.includes(ext)}
                    onClick={() => toggle(ext)}
                    disabled={disabled}
                  />
                ))}
              </div>
            </div>
          )
        })}
      </div>

      <Input
        label={t('Other file types')}
        // A format example, not prose - the words are file extensions.
        placeholder="json, log, yaml"
        value={draft}
        onChange={e => setDraft(e.target.value)}
        onBlur={() => setCustom(draft)}
        onKeyDown={e => {
          // Enter commits, as leaving the field does - and is not a dialog's submit.
          if (e.key === 'Enter') { e.preventDefault(); setCustom(draft) }
        }}
        disabled={disabled}
        hint={t('Comma-separated. Anything with no dedicated converter is indexed as plain text.')}
      />

      <Presence show={value.length === 0}>
        <Caption>
          {t('Nothing selected - every supported file type will be indexed.')}
        </Caption>
      </Presence>
    </div>
  )
}

// The picker's shape while the catalog loads: its label, a group's name over a row of chips, twice,
// and the custom field - so nothing moves when it arrives.
function FileTypeFilterSkeleton() {
  return (
    <div className="space-y-3" aria-hidden="true">
      <Skeleton className="h-3.5 w-32" />
      {[0, 1].map(group => (
        <div key={group} className="space-y-2">
          <Skeleton className="h-3 w-28" />
          <div className="flex flex-wrap gap-2">
            {[0, 1, 2, 3, 4].map(chip => <Skeleton key={chip} className="h-6 w-12 rounded-md" />)}
          </div>
        </div>
      ))}
      <FieldSkeleton />
    </div>
  )
}
