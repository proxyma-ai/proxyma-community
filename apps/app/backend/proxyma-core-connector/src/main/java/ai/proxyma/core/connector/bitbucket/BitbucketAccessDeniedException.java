package ai.proxyma.core.connector.bitbucket;

/**
 * Bitbucket answered a request with 401 or 403.
 *
 * <p>It exists so a WRITE can tell "your token cannot do that" apart from every other failure. Read
 * and write are separate permissions on a Bitbucket personal access token, and Bitbucket answers
 * both "unknown token" and "this token may not do that" with the same sentence - so a merge refused
 * for want of write permission used to arrive as a plain IllegalStateException, indistinguishable
 * from a conflict or a bad PR id, and the agent retried it or told the user their token was wrong
 * when reading with that exact token had just worked.
 *
 * <p>A subtype of {@link IllegalStateException} deliberately: every caller that already catches and
 * rethrows IllegalStateException keeps working unchanged, and only the callers that care about the
 * distinction - mergeBitbucketPr and createBitbucketTag - name this type.
 *
 * <p>Connector-local, and it should not stay that way: specification 03 section 6.3 names one
 * {@code AccessDeniedException} for the framework, which belongs beside ItemGoneException in
 * {@code ai.proxyma.core.connector}. Moving it there is a one-line import change here.
 */
public class BitbucketAccessDeniedException extends IllegalStateException {

    public BitbucketAccessDeniedException(String message) {
        super(message);
    }
}
