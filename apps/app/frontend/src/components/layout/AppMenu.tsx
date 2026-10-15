import { Menu, MessageSquarePlus, Settings } from 'lucide-react'
import { IconButton, MenuGroup, MenuSurface, menuRow } from '@/components/ui/kit'
import { QuitButton } from '@/features/system/components/QuitButton'
import { useSettingsModalStore } from '@/store/settingsModalStore'
import { useFeedbackModalStore } from '@/store/feedbackModalStore'
import { useTranslation } from '@/i18n'
import { AppearanceMenuRow, LanguageMenuRow } from './PreferenceMenuRows'

/**
 * The desktop edition's header menu - what UserMenu is on server, for an edition with no account.
 *
 * Desktop had no menu at all: Settings was a top-level nav word and Quit was a bare power icon,
 * both sitting in the header beside Chat and Sources. That put a deployment-wide configuration
 * screen and the one irreversible control in this app at the same visual weight as a page you
 * switch to, and a header can hold four words, not ten.
 *
 * So both move in here. The two editions now have the same shape in the same corner: one trigger,
 * Settings, feedback, appearance and language, and the destructive action
 * last and in the danger tone. Only the trigger differs, because only one of them has a name to
 * show - and there is deliberately no avatar or fake identity on this side to make them match.
 */
export function AppMenu() {
  const { t } = useTranslation()

  // THE SHELL IS THE KIT'S since the 2026-10 rebuild - the trigger slot, the outside press,
  // Escape (marked handled, so a dialog beneath stays open) and the panel measured to the room
  // under it. This was the one header menu still writing its own listeners and panel, with rules
  // between groups where every other menu has the kit's MenuGroup rhythm.
  return (
    // HIDDEN ON THE WRAPPER, NOT THE BUTTON. With only the button hidden, this div stayed a
    // zero-width flex item in the header's row below `lg`, and a flex item takes a gap whatever
    // its width - so the hamburger sat two gaps from Sources while Chat and Sources were one apart.
    //
    // The negative margin pulls the 36px (44px on glass) box left by its own inset, so the GLYPH
    // is one row gap from the last nav word, as the words are one gap from each other:
    // (36 - 16) / 2 = 10px of box plus 2px of the glyph's own side bearing is `-ml-3`, and
    // (44 - 16) / 2 + 2 = 16px is `touch:-ml-4`. Topbar's fit measurement counts margins.
    <MenuSurface
      className="hidden lg:block -ml-3 touch:-ml-4"
      // w-64 since 2026-09-14 (was w-52): its rows wrapped onto two lines, Vietnamese most of all.
      panelClassName="w-64"
      trigger={({ open, toggle, controls }) => (
        // 36px on a mouse, 44px on glass (the kit IconButton at `lg`). It is the ONLY control at
        // this end of the header on desktop: Settings and Quit are both behind it, and there is no
        // second way to any of them at this width. No tooltip: it would hang over the menu it opens.
        <IconButton
          icon={Menu}
          label={t('Menu')}
          size="lg"
          tooltip={false}
          onClick={toggle}
          aria-expanded={open}
          aria-haspopup="menu"
          aria-controls={controls}
        />
      )}
    >
      {close => (
        <>
          <MenuGroup>
            <button
              type="button"
              onClick={() => { close(); useSettingsModalStore.getState().open() }}
              className={menuRow()}
            >
              {/* t(), not a bare text node. This one word sat untranslated in a menu whose every
                  other row was Vietnamese - and it is invisible to the catalog checker in both
                  directions: not orphaned, because the literal is in the source; not untranslated,
                  because nothing asked for it. Found by reading the running app. */}
              <Settings size={15} className="shrink-0" aria-hidden="true" /> {t('Settings')}
            </button>
            {/* One click from anywhere, since 2026-09-28 (the maintainer's request) - it was only
                under Settings > Feedback, three steps from the thing being reported. */}
            <button
              type="button"
              onClick={() => { close(); useFeedbackModalStore.getState().open() }}
              className={menuRow()}
            >
              <MessageSquarePlus size={15} className="shrink-0" aria-hidden="true" /> {t('Send feedback')}
            </button>
          </MenuGroup>

          {/* NO SETTINGS SHORTCUTS, since 2026-09-30 (F26 of that day's review, approved by the
              maintainer). Seven tab rows under Settings made this a second, worse settings rail -
              thirteen rows, with Quit or Sign out below the fold on a phone - and every one of
              them was one click from the Settings row above anyway. */}

          {/* APPEARANCE AND LANGUAGE ARE HERE AGAIN, since 2026-09-14 - off the header bar, into the
              menu, at the maintainer's request. Not in two places at once: Topbar no longer puts
              them on the bar for desktop. */}
          <MenuGroup>
            <AppearanceMenuRow />
          </MenuGroup>
          <MenuGroup>
            <LanguageMenuRow onChosen={close} />
          </MenuGroup>

          {/* Quit last, in its own group, in the danger tone - the same position and treatment Sign
              out has on server. It keeps its own confirmation and its own "Proxyma has stopped"
              screen; only the trigger is a row. */}
          <MenuGroup>
            <QuitButton onOpenConfirm={close} />
          </MenuGroup>
        </>
      )}
    </MenuSurface>
  )
}
