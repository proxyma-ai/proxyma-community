package ai.proxyma.core.chat.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Whether a failed model call is worth asking again, for code that calls a model outside a chat turn (the YouTube
 * team's one-shot calls, 2026-11-11). The same reading of the failure the chat's own retry uses (ModelCall): a rate
 * limit or an overloaded provider clears with time, and so does a dropped or failed connection to the provider's
 * server; anything else repeats.
 * <p>Implements [PROXYMA-AD 055].
 */
public final class ModelFailures {

    private ModelFailures() {
    }

    /** A rate limit (429), an overloaded provider (503, 529) or a transient server failure. */
    public static boolean passing(Throwable e) {
        if (e == null) return false;
        if (ModelCall.isRateLimited(e) || ModelCall.isTransientServerError(e)) return true;
        int status = ModelCall.httpStatus(e);
        return status == 503 || status == 529;
    }

    /**
     * The provider refused the ACCOUNT, not the request: a key it does not accept (401, 403), payment it needs (402), or
     * a spent credit or quota it answers with another status (Anthropic: 400 "Your credit balance is too low"; OpenAI:
     * "insufficient_quota"). Asking again with the same request helps only once the person fixes the account - so work
     * waits, and nothing it was working on is blamed (2026-11-11: a spent credit counted against three comments' drafts).
     */
    public static boolean accountRefused(Throwable e) {
        if (e == null) return false;
        int status = ModelCall.httpStatus(e);
        if (status == 401 || status == 402 || status == 403) return true;
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String m = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (m.contains("credit balance") || m.contains("insufficient_quota") || m.contains("billing")
                    || m.contains("exceeded your current quota")) {
                return true;
            }
        }
        return false;
    }

    /**
     * THE PROVIDER'S SERVER COULD NOT BE REACHED (2026-11-13: Ollama on the same PC was stopped, every call failed with
     * "Connection refused", and each counted as a failed attempt of whatever it was for - the YouTube team's voice
     * learning spent its tries on it and wrote nothing until the next day). No answer came at all, so nothing about
     * the request is known to be wrong: work waits for the server, and nothing it was working on is blamed.
     */
    public static boolean unreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.UnknownHostException || t instanceof java.net.SocketTimeoutException
                    || t instanceof org.springframework.web.client.ResourceAccessException
                    || t instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
                return true;
            }
        }
        return false;
    }

    /** How long the provider asked to wait (Retry-After), when it said. */
    public static Optional<Duration> retryAfter(Throwable e) {
        return e == null ? Optional.empty() : ModelCall.retryAfter(e, Instant.now());
    }
}
