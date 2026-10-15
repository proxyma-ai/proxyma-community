'use client'

/**
 * The React binding: one provider, one hook.
 *
 * Deliberately not react-i18next. The four frontends already share code by mechanical copy with
 * a `--check` in CI, so a shared runtime costs nothing to distribute here - while a dependency
 * would have to be added, versioned and upgraded in four package.json files, and would bring a
 * plural-rule engine, a loader, a backend plugin system and a namespace concept that none of
 * these apps need. What is actually required is a lookup with a fallback, `{name}` substitution
 * and a two-form plural, which is `translate.ts`.
 *
 * Revisit that if a real translation workflow arrives - a TMS that wants standard catalog formats
 * is a good reason to adopt a standard runtime.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'

import {
  DEFAULT_LOCALE,
  LOCALES,
  initialLocale,
  isLocale,
  storeLocale,
  type Locale,
} from './locale.ts'
import { translate, type Catalogs, type TranslateParams } from './translate.ts'

export interface I18nValue {
  locale: Locale
  /**
   * Whether `locale` is the one in force. ALWAYS TRUE since 2026-10-14: the stored locale is read
   * before the first render (see the provider), so there is no placeholder to wait out. Kept so
   * the callers that guarded a once-only string on it - a toast raised in a mount effect - still
   * compile and still read correctly.
   */
  ready: boolean
  setLocale: (locale: Locale) => void
  /** `t("Connectors")` - the English text is the key. */
  t: (source: string, params?: TranslateParams) => string
  locales: readonly Locale[]
}

/**
 * The default is a WORKING context, not null.
 *
 * A component rendered outside the provider - a modal in a portal, a test mounting one widget -
 * then renders English rather than throwing. The alternative, a null context and a `useI18n must
 * be used within I18nProvider` error, turns a missing wrapper into a blank screen; here it is a
 * screen in the wrong language, which is visible and harmless.
 */
const FALLBACK: I18nValue = {
  locale: DEFAULT_LOCALE,
  // A component outside the provider has no detection to wait for, so it is ready immediately.
  ready: true,
  setLocale: () => {},
  t: (source) => source,
  locales: LOCALES,
}

const I18nContext = createContext<I18nValue>(FALLBACK)

export interface I18nProviderProps {
  catalogs: Catalogs
  children: ReactNode
  /**
   * Forces a locale instead of detecting one. The landing site passes this, because there the
   * locale comes from the URL (`/vi/...`) and the server has already decided it.
   */
  locale?: Locale
  /** Called after a change, for surfaces that must persist it somewhere else as well. */
  onLocaleChange?: (locale: Locale) => void
}

export function I18nProvider({ catalogs, children, locale: forced, onLocaleChange }: I18nProviderProps) {
  // THE STORED LOCALE IS READ BEFORE THE FIRST RENDER (2026-10-14, the cross-app review's X01).
  // It used to start at English and correct itself in an effect, to avoid a hydration mismatch -
  // but every consumer is a client-only SPA (`createRoot`, no server HTML to disagree with), and
  // the landing site mounts no provider at all. So all the effect did was paint every load in
  // English and repaint it in Vietnamese for a Vietnamese reader. Without `window` (a server
  // render) it still starts at the default; a surface that renders on a server and hydrates
  // passes `locale` instead, as the landing site would. `initialLocale` is tested.
  const [detected, setDetected] = useState<Locale>(() => initialLocale(forced))

  const active = forced ?? detected

  useEffect(() => {
    if (typeof document !== 'undefined') document.documentElement.lang = active
  }, [active])

  const setLocale = useCallback(
    (next: Locale) => {
      if (!isLocale(next)) return
      storeLocale(next)
      setDetected(next)
      onLocaleChange?.(next)
    },
    [onLocaleChange],
  )

  const value = useMemo<I18nValue>(
    () => ({
      locale: active,
      ready: true,
      setLocale,
      t: (source: string, params?: TranslateParams) => translate(catalogs, active, source, params),
      locales: LOCALES,
    }),
    [catalogs, active, setLocale],
  )

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n(): I18nValue {
  return useContext(I18nContext)
}

/**
 * The common case, so it reads as `const { t } = useTranslation()`.
 *
 * `ready` comes with it, and is always true now - see `I18nValue.ready`.
 */
export function useTranslation(): Pick<I18nValue, 't' | 'locale' | 'ready'> {
  const { t, locale, ready } = useI18n()
  return { t, locale, ready }
}
