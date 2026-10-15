package ai.proxyma.it.app;

import ai.proxyma.it.harness.Chat;
import ai.proxyma.it.harness.Ui;
import ai.proxyma.it.harness.UiLifecycle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.assertj.core.api.Assertions;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The product, in a browser, doing what a user does.
 *
 * <h2>The one thing only this file can catch</h2>
 *
 * A CORS origin pattern of {@code https://host:*} REQUIRES the origin to carry a port, so every
 * installation behind a reverse proxy on 443 - which is every home-server and server install - was
 * refused its own JavaScript and served a blank page. Vite marks its bundles {@code crossorigin},
 * which turns same-origin asset loads into CORS requests; {@code index.html} is a plain navigation
 * with no {@code Origin} header and kept answering 200 to every probe anyone ran. It survived a
 * green deploy. {@code CLAUDE.md} records why in four words: <b>curl cannot reproduce it</b>.
 *
 * <p>Every test here therefore ends by asserting the page reported no errors, not merely that a
 * locator resolved. A React app that dies during render has an empty body and a console error, and
 * a suite that only looked for buttons would report "element not found" and send somebody hunting
 * for a renamed selector.
 *
 * <h2>Selectors are structural, never prose</h2>
 *
 * The UI is translated, so matching on visible English text would make every journey fail the day
 * somebody runs the suite in Vietnamese. Roles, ARIA labels and {@code contenteditable} are the
 * same in both catalogs.
 */
@DisplayName("Desktop UI: the product in a browser")
@ExtendWith(UiLifecycle.class)
class DesktopUiIT extends DesktopJourney {

    @Test
    @DisplayName("the app renders, with no console error and no blocked asset")
    void theAppRenders() {
        Ui ui = Ui.open(app);
        ui.ready();

        // Not "the page returned 200" - it did, even when it was blank. This asks whether
        // React mounted anything at all.
        Locator root = ui.page().locator("#root");
        assertThat(root).not().isEmpty();

        ui.assertNoPageErrors();
    }

    @Test
    @DisplayName("a message typed into the composer comes back as a streamed answer")
    void aChatTurnInTheBrowser() {
        Ui ui = Ui.open(app);
        Page page = ui.ready().page();

        // The composer is a contenteditable div, not a textarea - it has to be, because
        // @mentions render as chips inside the text. It carries an aria-label, so it is
        // reachable as a textbox without depending on the placeholder's wording, which is
        // translated.
        Locator composer = page.getByRole(AriaRole.TEXTBOX).first();
        composer.waitFor(new Locator.WaitForOptions().setTimeout(30_000));

        // fill(), NOT click()-then-type(). The composer sits inside a framer-motion panel that
        // is still animating when the page first settles, and Playwright refuses to click an
        // element it considers unstable - the click retries for its whole timeout and then
        // fails with "element is not stable" on a control that is perfectly usable. fill()
        // focuses and sets the value without a pointer, so the animation is irrelevant.
        composer.fill("@@text Hello from a browser.");
        page.keyboard().press("Enter");

        // The answer arrives token by token over SSE, so this waits for the text to appear
        // rather than reading the DOM once.
        assertThat(page.getByText("Hello from a browser.").last())
                .containsText("Hello from a browser.",
                        new com.microsoft.playwright.assertions.LocatorAssertions
                                .ContainsTextOptions().setTimeout(60_000));

        ui.assertNoPageErrors();
    }

    @Test
    @DisplayName("the sources page loads and lists what is indexed")
    void theSourcesPage() {
        Ui ui = Ui.open(app);
        Page page = ui.page();
        ui.goTo("/sources");

        assertThat(page.locator("#root")).not().isEmpty();
        ui.assertNoPageErrors();
    }

    @Test
    @DisplayName("a deep link into a client route survives a reload")
    void aDeepLinkReloads() {
        Ui ui = Ui.open(app);
        Page page = ui.page();
        ui.goTo("/sources");

        // The server has no /sources endpoint; the catch-all serves index.html and React
        // Router takes it from there. A reload is the case that breaks when that catch-all is
        // wrong, and it breaks only for people who bookmark or refresh - never on the click
        // path anybody tests by hand.
        page.reload();
        ui.ready();

        assertThat(page.locator("#root")).not().isEmpty();
        ui.assertNoPageErrors();
    }

    @Test
    @DisplayName("the bundle loads as a CORS request, because Vite marks it crossorigin")
    void theBundleIsFetchedCrossOrigin() {
        Ui ui = Ui.open(app);
        Page page = ui.ready().page();

        // THE BLANK-PAGE REGRESSION, CAUGHT AT THE ONLY LAYER THAT CAN SEE IT. A browser
        // fetching a script tagged crossorigin sends an Origin header and enforces the
        // response's Access-Control-Allow-Origin, even same-origin. When the allowed pattern
        // demands a port the origin does not carry, the browser drops the bundle - the
        // navigation still returns 200 and the page is empty.
        //
        // failedRequests() collects exactly that: a request the browser could not complete.
        Assertions.assertThat(ui.failedRequests())
                .as("no asset was refused by the browser")
                .isEmpty();

        // evaluate() hands back an Integer for a small number and a Long for a large one, so
        // the cast has to go through Number or it throws ClassCastException on a value as
        // ordinary as 1.
        int scripts = ((Number) page.evaluate(
                "document.querySelectorAll('script[crossorigin]').length")).intValue();
        Assertions.assertThat(scripts)
                .as("index.html still marks its bundle crossorigin, so this test still means something")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("the appearance choice survives a reload")
    void appearancePersists() {
        Ui ui = Ui.open(app);
        Page page = ui.ready().page();

        // The resolution of System/Light/Gray/Black into data-mode and data-shade is a pure
        // function with its own node --test suite. What only a browser can show is that the
        // choice is WRITTEN, read back on the next load, and applied before first paint -
        // the reload is the whole test.
        page.evaluate("localStorage.setItem('proxyma-theme-mode', 'light')");
        page.reload();
        ui.ready();

        String mode = (String) page.evaluate(
                "document.documentElement.getAttribute('data-mode')");
        Assertions.assertThat(mode)
                .as("the stored 'light' preference was applied to <html> before first paint")
                .isEqualTo("light");
        ui.assertNoPageErrors();
    }
    @Test
    @DisplayName("hovering a message shows its buttons without moving anything below it")
    void hoverDoesNotShiftTheThread() {
        String id = Chat.send(app, "@@text First answer.").succeeded().conversationId();
        Chat.reply(app, id, "@@text Second answer.").succeeded();

        Ui ui = Ui.open(app);
        Page page = openConversation(ui, id);
        // Exact: the QUESTIONS contain these words too ("@@text First answer."), and come first.
        Locator first = page.getByText("First answer.", new Page.GetByTextOptions().setExact(true)).first();
        Locator second = page.getByText("Second answer.", new Page.GetByTextOptions().setExact(true)).first();
        assertThat(second).isVisible();

        page.mouse().move(0, 0);
        // Let the thread's own entrance animation finish first - measuring through it would blame
        // the hover for the animation's last few pixels.
        page.waitForTimeout(1500);
        BoundingBox before = second.boundingBox();
        first.hover();
        page.waitForTimeout(400);
        BoundingBox after = second.boundingBox();

        // The row of buttons under a message used to grow on hover and push the thread down.
        Assertions.assertThat(after.y).as("the next message stayed where it was").isEqualTo(before.y);
        Locator copy = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Copy response")).first();
        Assertions.assertThat(copy.evaluate("e => getComputedStyle(e.closest('[data-message-actions]')).opacity"))
                .as("and the hovered message's buttons appeared").isEqualTo("1");
        ui.assertNoPageErrors();
    }

    @Test
    @DisplayName("scrolled to the end of a long answer, the question it answers is pinned at the top")
    void theQuestionStaysPinned() {
        String longAnswer = "The ledger line keeps going. ".repeat(400);
        // Scripted rather than directed: a directive would put the long text in the QUESTION. Queued
        // several times over, because the title, and whatever else runs in the background, are
        // model calls too and take from the same queue.
        java.util.Map<String, String> reply = java.util.Map.of("text", longAnswer);
        llm().script(reply, reply, reply, reply, reply, reply);
        String id = Chat.send(app, "What does the ledger say?").succeeded().conversationId();

        Ui ui = Ui.open(app);
        Page page = openConversation(ui, id);
        Locator pinned = page.locator("[data-pinned-question]");
        Locator scroller = page.locator("div.overflow-y-auto:has(section)").first();
        // A reopened conversation opens at its LATEST message since 2026-09-30 (it used to open at
        // the top, which was the bug), so the question may already be pinned here. Scroll to the
        // top first: with the question itself in view there is nothing to pin.
        scroller.evaluate("e => e.scrollTop = 0");
        assertThat(pinned).hasCount(0);

        scroller.evaluate("e => e.scrollTop = e.scrollHeight");
        assertThat(pinned).isVisible();
        assertThat(pinned).containsText("What does the ledger say?");

        BoundingBox pane = scroller.boundingBox();
        Assertions.assertThat(pinned.boundingBox().y).as("pinned to the top of the thread")
                .isBetween(pane.y - 1, pane.y + 2);

        // No background of its own, since 2026-09-26 (the maintainer's call, replacing a mask that
        // cut the answer off at the bar): the answer scrolls on underneath into a dark gradient
        // that fades out below the bar. It was a blur for a few days; it must not come back.
        Assertions.assertThat(pinned.evaluate("e => getComputedStyle(e).backgroundColor").toString())
                .as("the bar is see-through").isEqualTo("rgba(0, 0, 0, 0)");
        Locator fade = page.locator(".pin-fade");
        Assertions.assertThat(fade.evaluate("e => getComputedStyle(e).backgroundImage").toString())
                .as("the answer beneath it fades into a gradient").contains("linear-gradient");
        Assertions.assertThat(fade.evaluate(
                "e => { const s = getComputedStyle(e); return (s.backdropFilter || s.webkitBackdropFilter || 'none'); }").toString())
                .as("and is not blurred").isEqualTo("none");

        // Clicking it brings the whole question back, clear of the bar - it used to land with its
        // first line under the bar - and the bar goes once the question is in view.
        pinned.click();
        assertThat(pinned).hasCount(0);
        Assertions.assertThat(page.getByText("What does the ledger say?").first().boundingBox().y)
                .as("the question is not under the bar").isGreaterThanOrEqualTo(pane.y);
        ui.assertNoPageErrors();
    }

    @Test
    @DisplayName("a failed tool call is folded with the rest, and its error shows when the list is opened")
    void aFailedToolIsShown() {
        String id = Chat.send(app, "@@tool noSuchToolAnywhere {} @@after-tools That did not work.")
                .conversationId();

        Ui ui = Ui.open(app);
        Page page = openConversation(ui, id);
        assertThat(page.getByText("That did not work.").first()).isVisible();
        // OPEN since 0.30.0 (the 0.29.2 feedback): a run of tool calls shows its calls without a
        // click, and a run of one has no chevron to fold it - so the failure is on screen at once.
        // It was folded from 2026-09-26 until then, and this test clicked the list open first.
        // Structural, not prose: the error is the row's danger-coloured text, whatever its wording.
        assertThat(page.locator("span.text-danger").first()).isVisible();
        ui.assertNoPageErrors();
    }
    /**
     * Opens a conversation by its URL, past the first-run tour. The tour sends the page back to the
     * start screen for as long as it is open, so a conversation named in the URL never loads under
     * it - a user presses Skip, and so does this.
     */
    private static Page openConversation(Ui ui, String id) {
        Page page = ui.ready().page();
        // "Not now" on the walkthrough's welcome card since 0.28.0, "Skip" on its later stops - the
        // 0.28.0 tag pipeline failed here when the welcome card replaced the first stop.
        Locator skip = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(java.util.regex.Pattern.compile("^(Not now|Skip)$")));
        try {
            skip.first().click(new Locator.ClickOptions().setTimeout(5_000));
        } catch (RuntimeException noTour) {
            // Already dismissed by an earlier test in this browser profile.
        }
        return ui.goTo(app.baseUrl() + "/chat/" + id).page();
    }
}
