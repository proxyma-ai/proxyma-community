package ai.proxyma.it.harness;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Photographs the browser when a UI test fails, then closes it.
 *
 * <h2>Why the test cannot do this itself</h2>
 *
 * A browser test that fails leaves behind the one artifact that would explain it - the rendered
 * page - and a stack trace saying a locator timed out. "Element not found" is compatible with a
 * renamed button, a dead application, a blocked bundle and a page that simply had not finished;
 * a picture tells those apart in a second and the trace never does. This matters most on the
 * production host, where nobody can watch the run.
 *
 * <p>It could not be done inside the test because of ORDERING. With
 * {@code try (Ui ui = Ui.open(app))}, the resource closes when the method exits - which is BEFORE
 * the exception reaches JUnit, so by the time any watcher hears about the failure the page is
 * gone. That is why these tests no longer use try-with-resources: this extension owns the
 * lifetime instead, screenshots while the page is still alive, and closes afterwards. A test may
 * still close a {@link Ui} explicitly when it wants one gone early; {@link Ui#close} deregisters
 * it, so this will not photograph a corpse.
 *
 * <p>Registered with {@code @ExtendWith(UiLifecycle.class)} on the classes that drive a browser.
 * Nothing closes over a {@code Ui} that was never opened, so it is harmless on a class that opens
 * none.
 */
public final class UiLifecycle implements AfterTestExecutionCallback {

    @Override
    public void afterTestExecution(ExtensionContext context) {
        boolean failed = context.getExecutionException().isPresent();
        for (Ui ui : Ui.openInstances()) {
            if (failed) {
                try {
                    // Named for the test, not for the moment: a file called
                    // DesktopUiIT.aChatTurnInTheBrowser.png is findable in an artifact browser,
                    // and a timestamp is not. One test, one picture, overwritten on a re-run -
                    // which is right, because the interesting one is always the latest.
                    ui.screenshot(context.getRequiredTestClass().getSimpleName()
                            + "." + context.getRequiredTestMethod().getName());
                } catch (RuntimeException e) {
                    // A screenshot is a diagnostic, never a verdict. A browser that died mid-test
                    // cannot be photographed, and turning that into a second failure would bury
                    // the first one - which is the one worth reading.
                    System.err.println("[UiLifecycle] could not photograph the failure: " + e);
                }
            }
            try {
                ui.close();
            } catch (RuntimeException e) {
                System.err.println("[UiLifecycle] could not close a browser context: " + e);
            }
        }
        Ui.forgetAll();
    }
}
