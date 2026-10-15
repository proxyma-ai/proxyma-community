import { LanguageMenu as KitLanguageMenu } from '@/components/ui/kit'
import { useI18n, LOCALE_NAMES, LOCALES } from '@/i18n'

/**
 * The kit's language control, wired to this app's i18n store.
 *
 * <p>BUTTONS, not anchors, and that is the one thing this app decides for itself: the locale here
 * is client state with no route of its own, so there is nothing to link to. The landing site's
 * wrapper returns anchors for the opposite reason - its locale is a URL segment, and a link is the
 * only thing a crawler can follow. Everything else - the trigger, the panel, the outside-click and
 * Escape handling, the check on the current row - comes from the kit, where it is written once.
 */
export function LanguageMenu() {
  const { locale, setLocale, t } = useI18n()

  return (
    <KitLanguageMenu
      locales={LOCALES}
      current={locale}
      nameOf={l => LOCALE_NAMES[l]}
      label={t('Language')}
      renderRow={({ locale: l, className, children, close }) => (
        <button type="button" lang={l} className={className} onClick={() => { setLocale(l); close() }}>
          {children}
        </button>
      )}
    />
  )
}
