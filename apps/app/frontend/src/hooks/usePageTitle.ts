import { useEffect } from 'react'
import { useThemeStore } from '@/store/themeStore'
import { useTranslation } from '@/i18n'
import { pageTitle } from '@/components/ui/kit'

/**
 * The browser tab's text: "Sources - Proxyma".
 *
 * TRANSLATED HERE RATHER THAN AT THE SEVEN CALL SITES, and that is the point of doing it in the
 * hook at all. The document title is in the HEAD, so nothing on the page displays it: an English
 * title on a Vietnamese page is invisible to the catalog checker, to the build, and to reading the
 * emitted markup. The landing site shipped exactly that defect and only a browser found it. A
 * page added later calls this hook the way its neighbors do and gets a translated title without
 * anybody remembering to ask for one.
 *
 * `title` is the ENGLISH text and doubles as the lookup key, like every other string in this app -
 * so a caller passes what it always passed and an untranslated one renders correct English.
 * `check-catalogs.mjs` cannot see a string reaching `t()` as a variable, so each caller has a
 * `DYNAMIC` entry naming this call; without those the seven titles would be in no report at all.
 *
 * THE SHAPE IS THE KIT'S `pageTitle` ("Page - Product"), the one every surface uses; what stays
 * here is the translation and the product name, which no kit file may read.
 *
 * `t` is in the dependency list on purpose: it changes identity when the locale does, so the tab
 * follows a language switch instead of keeping the wording it was first given.
 */
export function usePageTitle(title: string) {
  const productName = useThemeStore(s => s.branding.productName)
  const { t } = useTranslation()

  useEffect(() => {
    document.title = pageTitle(title ? t(title) : '', productName)
  }, [title, productName, t])
}
