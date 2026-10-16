package ai.proxyma.desktop.system;

import ai.proxyma.core.settings.global.GlobalSettingRepository;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.system.util.ProcessExit;
import ai.proxyma.desktop.launcher.PendingReset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The desktop edition's two resets.
 *
 * The server's SystemResetService cannot be reused, and not only because it is
 * {@code @Profile("server")} in a module that does not build here. It wipes ONE PostgreSQL schema
 * with a self-discovering {@code TRUNCATE ... CASCADE} over {@code pg_tables}. Desktop has four
 * separate H2 databases, a ChromaDB directory, a Lucene index and its conversations, skills and
 * workflows under ~/.proxyma - and H2 holds its files open, which is the whole difficulty.
 *
 * SO THE FULL WIPE DOES NOT HAPPEN HERE. This service writes a marker file and exits; the next
 * start deletes the data before anything opens it (PendingReset) - the launcher's, or this jar's own
 * main() where there is no launcher, as on the Home Server. That removes the file-locking problem by
 * construction rather than fighting it - on Windows, deleting an open .mv.db simply fails - and it
 * reuses the restart path that already exists: exit code 3 starts the application again.
 *
 * WHAT SURVIVES A FULL RESET, and why it is a deletion allowlist rather than "delete everything
 * except":
 * <ul>
 *   <li>The license. A desktop user who wipes their index has not stopped being a customer, and
 *       may not have their key to hand. The server clears it because a container reset is a
 *       re-provision; this is not that.</li>
 *   <li>The credential encryption key. Deleting it would not delete the stored credentials, it
 *       would turn them into bytes nobody can ever decrypt - including any that survive in a
 *       backup the user made.</li>
 *   <li>{@code runtime/} and {@code browsers/} - the bundled Python interpreter and the headless
 *       browser, several hundred megabytes between them. Wiping those would turn "start over" into
 *       a long re-download on a machine that may be offline, for no benefit: neither holds user
 *       data.</li>
 *   <li>{@code logs/}, which is what someone reads to find out why they needed the reset.</li>
 * </ul>
 */
@Service
public class DesktopResetService {

    private static final Logger log = LoggerFactory.getLogger(DesktopResetService.class);

    private final GlobalSettingRepository globalSettingRepository;
    private final AppPaths appPaths;
    private final ConfigurableApplicationContext context;

    // What lives OUTSIDE the data directory, as this installation resolved it. Only the app knows
    // these - the launcher reads no configuration - so the marker carries them (editions-assembly-04).
    @Value("${proxyma.conversations-dir:${user.home}/.proxyma/conversations}")
    private String conversationsDir;
    @Value("${proxyma.skills-dir:${user.home}/.proxyma/skills}")
    private String skillsDir;
    @Value("${proxyma.workflows-dir:${user.home}/.proxyma/workflows}")
    private String workflowsDir;
    @Value("${proxyma.evaluation-media-dir:${user.home}/.proxyma/evaluation-media}")
    private String evaluationMediaDir;
    @Value("${proxyma.guardrail.policies-dir:}")
    private String policiesDir;
    // The Home Server's uploads, which it owns: {storage root}/users/{user}/uploads.
    @Value("${proxyma.file-upload.enabled:false}")
    private boolean fileUpload;
    @Value("${proxyma.storage.local.root:/data}")
    private String storageRoot;

    public DesktopResetService(GlobalSettingRepository globalSettingRepository, AppPaths appPaths,
                               ConfigurableApplicationContext context) {
        this.context = context;
        this.globalSettingRepository = globalSettingRepository;
        this.appPaths = appPaths;
    }

    /**
     * Settings back to defaults, keeping every connector, document and conversation.
     *
     * No marker and no launcher involvement: settings live in one JPA-managed table in both
     * editions, so this is a delete through the repository rather than a file operation, and
     * nothing holds a lock that matters. SettingsService re-seeds the defaults on the next start
     * when it finds the table empty.
     */
    // The chat unit's manager: GLOBAL_SETTINGS lives on that datasource (ChatDataSourceConfig), and
    // a bare @Transactional binds the @Primary local one instead (editions-assembly-09).
    @Transactional("chatTransactionManager")
    public void resetSettings() {
        long count = globalSettingRepository.count();
        globalSettingRepository.deleteAll();
        log.warn("[DesktopReset] Settings reset - {} row(s) deleted, restarting", count);
        restart();
    }

    /**
     * Everything Proxyma has stored: connectors, indexed content, chat history and settings.
     *
     * Only writes the marker - see the class comment for why the deletion itself belongs to the
     * next start. Settings are NOT deleted here even though they could be: doing half the wipe
     * before the restart and half after would leave a window where a crash in between produces an
     * install with no settings and a full index, which is a state nothing else in the app expects.
     */
    public void resetEverything() throws IOException {
        PendingReset.request(appPaths.dataRoot(), outsideDataRoot(), Instant.now());
        log.warn("[DesktopReset] Full reset requested - marker written, restarting to apply it");
        restart();
    }

    List<Path> outsideDataRoot() {
        List<Path> dirs = new ArrayList<>();
        for (String dir : List.of(conversationsDir, skillsDir, workflowsDir, evaluationMediaDir, policiesDir)) {
            if (dir != null && !dir.isBlank()) dirs.add(Path.of(dir));
        }
        if (fileUpload && storageRoot != null && !storageRoot.isBlank()) dirs.add(Path.of(storageRoot, "users"));
        return dirs;
    }

    // Exit code 3, the launcher's restart signal - it applies any staged update, then any pending
    // reset, then starts the app (ProxymaLauncher). Through ProcessExit, after a beat for the HTTP
    // response, and with the context closed and bounded like every other exit: this called
    // System.exit bare until the 0.36.0 feedback.
    private void restart() {
        ProcessExit.restartShortly(context, "Reset requested");
    }
}
