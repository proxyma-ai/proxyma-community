package ai.proxyma.core.tools.youtube;

/**
 * A refusal from one of YouTube's APIs, already said as the sentence the model acts on - sign in
 * again, enable an API, wait for the quota - rather than the bare status ApiClient would name.
 */
class YouTubeApiException extends RuntimeException {

    YouTubeApiException(String message) {
        super(message);
    }
}
