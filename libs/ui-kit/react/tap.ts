/**
 * THE TOUCH TARGET FOR A CONTROL MADE OF WORDS.
 *
 * Every ICON control in this product's chrome had been given a pointer-aware target by
 * 2026-09-08 - `w-9 h-9 touch:w-11 touch:h-11` on ThemeToggle, LanguageMenu, MobileMenu and
 * AppMenu, and `touch:min-h-tap` on every menu row. The TEXT controls beside them had not, and
 * nothing said they were a different case, because nobody had asked the question about them.
 *
 * <p>Measured on the license console's account trigger at 768 x 1024 - an iPad mini upright, which
 * is a `sm`-and-up width, so the trigger is the account NAME with the hamburger gone: 22px tall.
 * That is `text-sm`'s line box and nothing else. WCAG 2.2 SC 2.5.8 sets a floor of 24px, so it
 * misses even the floor, on a header whose every icon neighbour is 44px.
 *
 * <p>IT IS ONE DECISION ACROSS FOUR APPS, not four patches, because it is the same shape in all of
 * them: the app's nav words, the telemetry console's nav words, the landing site's nav words and
 * all three account triggers are a line of `text-sm` with no box of its own. Fixing one of them
 * leaves the other three wrong and gives the next person no reason to think the question was ever
 * settled.
 *
 * <p>MIN-HEIGHT, NOT HEIGHT, and never `py-`. A fixed height cannot grow if a translation wraps -
 * Vietnamese runs longer than English on nearly every one of these words - and vertical padding
 * grows the target on a mouse too, where 36px is already correct and 22px of text plus 22px of
 * padding is a header that has to get taller to hold it. `touch:min-h-tap` changes the box on
 * glass and leaves the mouse alone.
 *
 * <p>THE DISPLAY IS PART OF THE RECIPE, and it is the part that is easy to leave out.
 * `min-height` does nothing at all to a non-replaced INLINE box, and these controls are anchors -
 * `hidden sm:inline` was the class three of them carried. So the box has to be an inline-flex one
 * before the min-height means anything, and `items-center` has to come with it or the words sit at
 * the top of a 44px box rather than in the middle of it.
 */

/**
 * A text control that is on the bar at every width - an account trigger, a footer link.
 *
 * <p>Safe inside a flex row: a flex item is blockified, so `inline-flex` resolves to `flex` and
 * `items-center` still does what it says.
 */
export const TAP_TEXT = 'inline-flex items-center touch:min-h-tap'

/**
 * A text control that is not on the bar below `sm` - every nav word in all four headers.
 *
 * <p>WHY THIS IS A SEPARATE CONSTANT RATHER THAN `cn('hidden sm:inline', TAP_TEXT)`: `hidden` and
 * `inline-flex` are both base display utilities at equal specificity, so which one wins is decided
 * by the order Tailwind emits them in - and it emits `hidden` last, so composing them that way
 * happens to work while reading as though it should not. That is the same class of accident that
 * put a 44px button under an overflowing account name (see UserMenu) and drew the landing nav on
 * top of the language button (see AppHeader). Relying on emission order twice in one afternoon was
 * enough; here the display is stated once per breakpoint and there is nothing to resolve.
 *
 * <p>The `sm:` prefix on `items-center` is not decoration either - an unprefixed `items-center`
 * would be a base utility applying to a box that does not exist below `sm`, which is harmless but
 * says the wrong thing about when this control is on screen.
 */
export const TAP_TEXT_FROM_SM = 'hidden sm:inline-flex sm:items-center touch:min-h-tap'
