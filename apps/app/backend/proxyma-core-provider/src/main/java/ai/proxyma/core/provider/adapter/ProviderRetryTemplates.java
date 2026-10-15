package ai.proxyma.core.provider.adapter;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.Map;

/**
 * The retrying Spring AI does INSIDE one blocking model call - which is to say, almost none.
 *
 * <p>Spring AI's own default retries ten times, backing off from 2 s by a factor of 5 up to 180 s:
 * about nineteen minutes inside a single {@code call()} that nobody can interrupt. Callers that
 * bound the call with a future timeout (title, query expansion, rerank, graph, guardrail) only
 * stopped WAITING; the thread kept retrying. A template here once removed the connection-failure
 * half of that and kept the rest, and its comment called the hang removed.
 *
 * <ul>
 *   <li>{@link #CHAT}: no retry. ModelCall owns retrying an agent's call - with the provider's
 *       Retry-After, a budget and Stop handling - and every other caller has a fallback of its own.
 *       A second layer underneath only multiplied the waits.</li>
 *   <li>{@link #EMBEDDING}: three attempts, 2 s and then 4 s apart, for a busy (429) or failing (5xx)
 *       embedding endpoint. Ingest has no ModelCall around it; a document that still fails is
 *       retried by its sync.</li>
 * </ul>
 *
 * <p>The statuses are typed because ProviderErrors raises them typed - see that class.
 */
final class ProviderRetryTemplates {

    private ProviderRetryTemplates() {}

    static final RetryTemplate CHAT = template(new NeverRetryPolicy(), 0);

    static final RetryTemplate EMBEDDING = template(new SimpleRetryPolicy(3, Map.of(
            HttpServerErrorException.class, true,
            HttpClientErrorException.TooManyRequests.class, true,
            TransientAiException.class, true), true, false), 2000);

    private static RetryTemplate template(RetryPolicy policy, long initialMillis) {
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(policy);
        if (initialMillis > 0) {
            ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
            backOff.setInitialInterval(initialMillis);
            backOff.setMultiplier(2.0);
            backOff.setMaxInterval(4000);
            template.setBackOffPolicy(backOff);
        }
        return template;
    }
}
