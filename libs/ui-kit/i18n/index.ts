export {
  LOCALES,
  DEFAULT_LOCALE,
  LOCALE_NAMES,
  isLocale,
  matchLocale,
  resolveInitialLocale,
  readStoredLocale,
  storeLocale,
  type Locale,
} from './locale.ts'

export {
  translate,
  type Catalog,
  type Catalogs,
  type TranslateParams,
} from './translate.ts'

export {
  I18nProvider,
  useI18n,
  useTranslation,
  type I18nValue,
  type I18nProviderProps,
} from './I18nProvider.tsx'


export { richParts, type RichPart } from './richParts.ts'
export { Rich } from './Rich.tsx'
