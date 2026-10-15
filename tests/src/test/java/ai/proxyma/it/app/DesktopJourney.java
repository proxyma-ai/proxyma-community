package ai.proxyma.it.app;

import ai.proxyma.it.harness.FakeLlm;
import ai.proxyma.it.harness.Http;
import ai.proxyma.it.harness.ProxymaApp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * The shared desktop installation every desktop journey runs against.
 *
 * <p>One booted app for the whole suite, because booting costs tens of seconds and these journeys
 * do not interfere: each creates its own sources, conversations and settings. A test that genuinely
 * needs a virgin installation - first run, the onboarding tour, a reset - boots its own and says in
 * a comment why.
 *
 * <p>The recorded provider requests ARE cleared per test. They are the main assertion surface, and
 * a test that found a chat request from the previous test would pass while proving nothing.
 */
abstract class DesktopJourney {

    protected static ProxymaApp app;
    protected static String providerId;

    @BeforeAll
    static void bootOnce() {
        app = ProxymaApp.shared(ProxymaApp.Mode.DESKTOP);
        providerId = app.ensureFakeProvider();
    }

    @BeforeEach
    void forgetPreviousRequests() {
        llm().clear();
    }

    protected static Http api() { return app.api(); }

    protected static FakeLlm llm() { return app.llm(); }
}
