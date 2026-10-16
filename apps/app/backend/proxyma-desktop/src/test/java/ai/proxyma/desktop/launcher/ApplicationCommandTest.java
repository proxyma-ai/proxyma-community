package ai.proxyma.desktop.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application JVM's command line, as ProxymaLauncher builds it (editions-assembly-01, -14).
 * A desktop install listens on loopback and answers to loopback names only, and nothing the user's
 * environment or the launcher's own arguments carry can widen either.
 */
class ApplicationCommandTest {

    private static List<String> command(List<String> inherited) {
        return command(inherited, List.of());
    }

    private static List<String> command(List<String> inherited, List<String> jvmOptions) {
        return ProxymaLauncher.applicationCommand("java", Path.of("app.jar"), 4246, Path.of("parts"),
                null, null, null, "logs", inherited, jvmOptions);
    }

    @Test
    void theListenerIsPinnedToLoopback() {
        List<String> cmd = command(List.of());
        assertThat(cmd).contains("-Dserver.address=127.0.0.1", "-Dproxyma.network.allowed-hosts=", "-Dserver.port=4246");
        assertThat(cmd.indexOf("-Dserver.address=127.0.0.1")).isLessThan(cmd.indexOf("-jar"));
        assertThat(cmd.subList(cmd.size() - 2, cmd.size())).containsExactly("-jar", "app.jar");
    }

    @Test
    void inheritedArgumentsCannotOpenIt() {
        List<String> cmd = command(List.of("-Dserver.address=0.0.0.0", "-Dproxyma.network.allowed-hosts=*",
                "-Dproxyma.access.code=chosen-by-someone-else", "-Dfile.encoding=UTF-8", "-Xmx2g"));
        assertThat(cmd).doesNotContain("-Dserver.address=0.0.0.0", "-Dproxyma.network.allowed-hosts=*",
                "-Dproxyma.access.code=chosen-by-someone-else", "-Xmx2g");
        assertThat(cmd).contains("-Dfile.encoding=UTF-8");
    }

    @Test
    void classDataSharingOptionsComeBeforeTheJar() {
        List<String> cmd = command(List.of(), List.of("-XX:SharedArchiveFile=x.jsa", "-Xshare:auto"));
        assertThat(cmd.indexOf("-XX:SharedArchiveFile=x.jsa")).isPositive().isLessThan(cmd.indexOf("-jar"));
        assertThat(cmd.indexOf("-Xshare:auto")).isLessThan(cmd.indexOf("-jar"));
        assertThat(cmd.subList(cmd.size() - 2, cmd.size())).containsExactly("-jar", "app.jar");
    }
}
