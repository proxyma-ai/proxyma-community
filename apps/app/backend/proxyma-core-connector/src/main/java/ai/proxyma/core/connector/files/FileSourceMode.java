package ai.proxyma.core.connector.files;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

/**
 * Which file source THIS deployment offers, answered once.
 *
 * <p>There are two, they are mutually exclusive, and the difference is a LIFECYCLE rather than a
 * user interface. {@link LocalFolderConnector} indexes a directory the user already has and must
 * never delete a byte of it. {@link UploadedFilesConnector} indexes bytes Proxyma was handed,
 * stored in a sandbox it owns, and deleting the source deletes them. As that class puts it: one of
 * these reclaims storage and the other must never touch a file, which is not a difference to
 * express with an {@code if}.
 *
 * <p>That is why this is a MODE and not a pair of feature flags. A deployment serving both would
 * have two file types behind one wire name ({@code "files"}), so nothing reading a connector could
 * tell from its type whether deleting it may remove the user's own documents.
 *
 * <h2>Who gets which</h2>
 *
 * <table>
 *   <tr><th>Deployment</th><th>Mode</th><th>Why</th></tr>
 *   <tr><td>Desktop (Windows, .deb)</td><td>local folder</td>
 *       <td>There is a screen, a native folder picker and the user's own filesystem. Copying bytes
 *           Proxyma would then own duplicates documents the user already has.</td></tr>
 *   <tr><td>Home server (the Docker image)</td><td>UPLOAD</td>
 *       <td>Same application, no screen and no shared filesystem. "Pick a folder" means a folder
 *           inside a container, which is a bind mount the operator has to get right before anything
 *           can be indexed at all.</td></tr>
 *   <tr><td>Server / enterprise</td><td>upload</td>
 *       <td>Many users, no shared filesystem, and a per-user sandbox is what keeps them apart.</td></tr>
 * </table>
 *
 * <h2>Why the rule is a static method, and why the beans are @Conditional</h2>
 *
 * <p>The two connectors were gated by Spring PROFILE - {@code @Profile("desktop")} and
 * {@code @Profile("!desktop")} - and that choice was documented as deliberate: the profile is
 * hardcoded in the desktop module's own properties, so a server deployment could not lose its
 * upload connector to an unset property. The home server broke the assumption underneath it rather
 * than the reasoning: it RUNS the desktop profile, being the desktop application in a container,
 * and now needs the other connector.
 *
 * <p>So the gate moves to a property, and the safety the profile was providing is kept by
 * {@link #uploadsEnabled(Environment)} rather than lost: {@code multi-user} forces uploads on and
 * the new property cannot turn them off. A server deployment missing the key still gets uploads,
 * because it is derived from something that edition already sets. Without that, an absent line in a
 * properties file would silently re-enable registering arbitrary host paths for every account -
 * which is the entire thing {@code requireAllowedLocalPath} exists to prevent.
 *
 * <p>A bean condition cannot call an instance method on a bean that does not exist yet, so the rule
 * is static and the instance methods delegate to it. One rule, three readers: this class, and the
 * two nested {@link Condition}s.
 */
@Component
public class FileSourceMode {

    /** Set by the server edition's own properties file. Never read directly - see the class note. */
    public static final String MULTI_USER_PROPERTY = "proxyma.multi-user";

    /**
     * The single-user opt-in, for a deployment with no accounts that still has no filesystem worth
     * pointing at. Set by the home server image; false for a desktop install, which has one.
     */
    public static final String FILE_UPLOAD_PROPERTY = "proxyma.file-upload.enabled";

    private final Environment environment;

    public FileSourceMode(Environment environment) {
        this.environment = environment;
    }

    /**
     * THE RULE. Every other method here and both conditions below are this one.
     *
     * <p>Multi-user implies uploads and the opt-in cannot override it - see the class note for what
     * an unset property would otherwise re-enable.
     */
    public static boolean uploadsEnabled(Environment env) {
        return env.getProperty(MULTI_USER_PROPERTY, Boolean.class, false)
                || env.getProperty(FILE_UPLOAD_PROPERTY, Boolean.class, false);
    }

    /** True when this deployment takes bytes and owns them - see the class note. */
    public boolean uploadsEnabled() {
        return uploadsEnabled(environment);
    }

    /** True when a file source is a directory the user already has, which Proxyma only reads. */
    public boolean localFoldersEnabled() {
        return !uploadsEnabled();
    }

    /**
     * Whether an upload is attributed to a named account.
     *
     * <p>False on the home server, where there are no accounts and the owner of everything is the
     * single local user - represented as a null userId everywhere in this codebase, including in
     * the connector rows the rest of the desktop UI looks up. See {@link UploadSandbox} for why
     * that null is carried through rather than replaced with a synthetic id.
     */
    public boolean uploadsAreAttributed() {
        return environment.getProperty(MULTI_USER_PROPERTY, Boolean.class, false);
    }

    /** Gates {@link UploadedFilesConnector} and the upload endpoints. */
    public static class UploadsEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return uploadsEnabled(context.getEnvironment());
        }
    }

    /** Gates {@link LocalFolderConnector} and the desktop folder picker. The exact complement. */
    public static class LocalFoldersEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !uploadsEnabled(context.getEnvironment());
        }
    }
}
