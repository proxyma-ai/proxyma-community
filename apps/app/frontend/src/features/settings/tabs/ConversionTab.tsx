import { FadeSwap, RadioGroupSkeleton, SettingsSection, SettingsSections } from '@/components/ui/kit'
import { useSettings } from '../hooks/useSettings'
import { useResolved, useSaveSetting } from '../pages/shared'
import { useTranslation } from '@/i18n'
import type { TabProps } from '../pages/shared'
import { ChoiceField, ProviderModelRow } from '../components/settingsParts'
import { SettingText } from '../components/SettingFields'

// Conversion tab: two method radio groups, then the OCR engine radio group (whose own
// provider/model or languages fields only exist once the engine is known).
function DocumentsTabSkeleton() {
  return (
    <div className="space-y-6">
      <RadioGroupSkeleton count={4} />
      <RadioGroupSkeleton count={4} />
      <RadioGroupSkeleton count={2} />
    </div>
  )
}

// - Conversion tab -
//
// Two questions, in the order an operator answers them: which converter reads a document (per
// surface, since unattended indexing and a single chat attachment price differently), and - if OCR
// is in play at all - which OCR engine and model does the reading. See DocumentConversionSettings /
// OcrSettings on the backend for the semantics of each method and engine.

const CONVERSION_METHOD_OPTIONS = [
  { key: 'auto',        label: 'Auto (extract text, OCR if scanned)' },
  { key: 'markitdown',  label: 'Markitdown' },
  { key: 'pdfplumber',  label: 'pdfplumber (table-aware PDFs)' },
  { key: 'ocr',         label: 'OCR (always)' },
]

const OCR_ENGINE_OPTIONS = [
  { key: 'vision',    label: 'Vision model' },
  { key: 'tesseract', label: 'Tesseract' },
]

const OCR_DPI_OPTIONS = [
  { key: '150', label: '150' },
  { key: '200', label: '200' },
  { key: '300', label: '300' },
  { key: '400', label: '400' },
]

const OCR_MAX_PAGES_OPTIONS = [
  { key: '10',  label: '10' },
  { key: '25',  label: '25' },
  { key: '50',  label: '50' },
  { key: '200', label: '200' },
]

function ConversionMethodField({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const indexingMethod = useResolved('doc-conversion', 'indexing-method', pending, 'auto')
  return (
    <ChoiceField
      label={t('Method for indexing')}
      hint={t('For every source. Auto reads scans only where there is no text.')}
      value={indexingMethod}
      onChange={v => trackChange('doc-conversion', 'indexing-method', v)}
      options={CONVERSION_METHOD_OPTIONS}
    />
  )
}

/** The OCR engine, and what that engine needs: a vision model, or Tesseract's languages. */
function OcrEngineFields({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const engine = useResolved('ocr', 'engine', pending, 'vision')
  const languages = useResolved('ocr', 'languages', pending, 'eng')
  const saveLanguages = useSaveSetting('ocr', 'languages')
  return (
    <>
      <ChoiceField
        label={t('OCR engine')}
        hint={engine === 'tesseract'
          ? t('Free and offline, plain text only.')
          : t('Reads layouts, tables and handwriting. One model call per page.')}
        value={engine}
        onChange={v => trackChange('ocr', 'engine', v)}
        options={OCR_ENGINE_OPTIONS}
        horizontal
      />

      {/* What the engine needs, faded across when the engine changes. */}
      <FadeSwap swapKey={engine}>
      {engine === 'vision' ? (
        <ProviderModelRow
          role="ocr"
          providerLabel="OCR provider"
          modelLabel="OCR model"
          modelPlaceholder="qwen3-vl:32b"
          modelHint={t('A vision model. Leave blank to turn OCR off.')}
          trackChange={trackChange}
          pending={pending}
        />
      ) : (
        // Edited where it is read, saved when it is left (the click-to-edit rule, 2026-11-02).
        <SettingText
          label={t('Tesseract languages')}
          hint={t('Language codes joined with "+", like eng+vie.')}
          value={languages}
          onCommit={saveLanguages}
          placeholder="eng"
        />
      )}
      </FadeSwap>
    </>
  )
}

export function DocumentsTab({ trackChange, pending }: TabProps) {
  const { t } = useTranslation()
  const { isLoading: conversionLoading } = useSettings('doc-conversion')
  const { isLoading: ocrLoading } = useSettings('ocr')
  const attachmentMethod = useResolved('doc-conversion', 'attachment-method', pending, 'auto')
  const dpi = useResolved('ocr', 'dpi', pending, '200')
  const maxPages = useResolved('ocr', 'max-pages', pending, '50')

  if (conversionLoading || ocrLoading) return <DocumentsTabSkeleton />

  return (
    <SettingsSections intro={t('How documents are turned into text.')}>
      <SettingsSection title={t('Method')}>
        <ConversionMethodField trackChange={trackChange} pending={pending} />
        <ChoiceField
          label={t('Method for chat attachments')}
          hint={t('For chat attachments and pages the agent fetches.')}
          value={attachmentMethod}
          onChange={v => trackChange('doc-conversion', 'attachment-method', v)}
          options={CONVERSION_METHOD_OPTIONS}
        />
      </SettingsSection>

      <SettingsSection title={t('OCR')}>
        <OcrEngineFields trackChange={trackChange} pending={pending} />
        <ChoiceField
          label={t('Page resolution (DPI)')}
          hint={t('Higher is sharper and costs more.')}
          value={dpi}
          onChange={v => trackChange('ocr', 'dpi', v)}
          options={OCR_DPI_OPTIONS}
          horizontal
          figures
        />
        <ChoiceField
          label={t('Max pages per document')}
          hint={t('Pages past this are skipped.')}
          value={maxPages}
          onChange={v => trackChange('ocr', 'max-pages', v)}
          options={OCR_MAX_PAGES_OPTIONS}
          horizontal
          figures
        />
      </SettingsSection>
    </SettingsSections>
  )
}

