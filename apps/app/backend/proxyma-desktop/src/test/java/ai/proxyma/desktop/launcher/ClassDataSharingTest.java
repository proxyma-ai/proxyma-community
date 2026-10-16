package ai.proxyma.desktop.launcher;

import ai.proxyma.desktop.launcher.ClassDataSharing.Launch;
import ai.proxyma.desktop.launcher.ClassDataSharing.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Class data sharing for the application JVM (the 0.36.0 feedback, 2026-10-16): which jar and options
 * each start of a release gets, and that every way it can go wrong leaves a start that works - the
 * packed jar, or the extracted copy without an archive.
 */
class ClassDataSharingTest {

    private static final String JVM = "C:\\jre 21.0.10+7";

    @TempDir
    Path dataRoot;

    private Path packedJar;
    private Path dir;

    @BeforeEach
    void release() throws IOException {
        Path part = Files.createDirectories(dataRoot.resolve("parts").resolve("app").resolve("0123abcd"));
        packedJar = Files.writeString(part.resolve("proxyma-desktop.jar"), "packed");
        dir = ClassDataSharing.dirFor(dataRoot, packedJar);
    }

    private Path extracted() throws IOException {
        Path thin = Files.createDirectories(dir.resolve("app")).resolve("proxyma-desktop.jar");
        Files.writeString(thin, "thin");
        Files.writeString(dir.resolve("extracted"), "");
        return thin;
    }

    @Test
    @DisplayName("each release has a directory of its own, named by its app part")
    void directoryPerRelease() {
        assertThat(dir).isEqualTo(dataRoot.resolve("class-data").resolve("0123abcd"));
    }

    @Test
    @DisplayName("nothing extracted yet: the release's own jar, no options")
    void packed() {
        Launch launch = ClassDataSharing.plan(dir, packedJar, JVM);
        assertThat(launch.stage()).isEqualTo(Stage.PACKED);
        assertThat(launch.jar()).isEqualTo(packedJar);
        assertThat(launch.jvmOptions()).isEmpty();
    }

    @Test
    @DisplayName("an extraction that never finished is not used")
    void unfinishedExtraction() throws IOException {
        Files.createDirectories(dir.resolve("app"));
        Files.writeString(dir.resolve("app").resolve("proxyma-desktop.jar"), "thin");
        assertThat(ClassDataSharing.plan(dir, packedJar, JVM).stage()).isEqualTo(Stage.PACKED);
    }

    @Test
    @DisplayName("extracted, no archive: the copy runs and records the classes it loads")
    void recording() throws IOException {
        Path thin = extracted();
        Launch launch = ClassDataSharing.plan(dir, packedJar, JVM);
        assertThat(launch.stage()).isEqualTo(Stage.RECORDING);
        assertThat(launch.jar()).isEqualTo(thin);
        assertThat(launch.jvmOptions()).containsExactly("-XX:DumpLoadedClassList=" + dir.resolve("classes.recording"));
    }

    @Test
    @DisplayName("an archive this JVM made is mapped, and an unusable one would be ignored (auto, never on)")
    void shared() throws IOException {
        Path thin = extracted();
        Files.writeString(dir.resolve("app.jsa"), "archive");
        Files.writeString(dir.resolve("app.jsa.jvm"), JVM);
        Launch launch = ClassDataSharing.plan(dir, packedJar, JVM);
        assertThat(launch.stage()).isEqualTo(Stage.SHARED);
        assertThat(launch.jar()).isEqualTo(thin);
        assertThat(launch.jvmOptions()).containsExactly("-XX:SharedArchiveFile=" + dir.resolve("app.jsa"), "-Xshare:auto");
    }

    @Test
    @DisplayName("an archive another JVM made is not handed to this one - it is made again")
    void archiveOfAnotherJvm() throws IOException {
        extracted();
        Files.writeString(dir.resolve("app.jsa"), "archive");
        Files.writeString(dir.resolve("app.jsa.jvm"), "C:\\jre 21.0.9+10");
        assertThat(ClassDataSharing.plan(dir, packedJar, JVM).stage()).isEqualTo(Stage.RECORDING);
    }

    @Test
    @DisplayName("a dump this JVM could not make is not tried again by it; another JVM tries")
    void failedDump() throws IOException {
        extracted();
        Files.writeString(dir.resolve("app.jsa.failed"), JVM);
        Launch launch = ClassDataSharing.plan(dir, packedJar, JVM);
        assertThat(launch.stage()).isEqualTo(Stage.EXTRACTED);
        assertThat(launch.jvmOptions()).isEmpty();

        assertThat(ClassDataSharing.plan(dir, packedJar, "C:\\jre 21.0.11+9").stage()).isEqualTo(Stage.RECORDING);
    }

    @Test
    @DisplayName("the list taken from a running application ends at its last complete line")
    void snapshotDropsAHalfWrittenLine() throws IOException {
        Path recording = Files.writeString(dataRoot.resolve("rec"), "java/lang/Object id: 0\norg/slf4j/Logger\norg/slf4j/Logg");
        Path list = dataRoot.resolve("list");
        assertThat(ClassDataSharing.snapshot(recording, list)).isTrue();
        assertThat(Files.readString(list)).isEqualTo("java/lang/Object id: 0\norg/slf4j/Logger\n");

        assertThat(ClassDataSharing.snapshot(Files.writeString(dataRoot.resolve("half"), "org/slf"), list)).isFalse();
        assertThat(ClassDataSharing.snapshot(dataRoot.resolve("none"), list)).isFalse();
    }

    @Test
    @DisplayName("an update's first preparation removes every other release's directory, and only those")
    void otherReleasesGo() throws IOException {
        extracted();
        Path old = Files.createDirectories(dataRoot.resolve("class-data").resolve("old-part").resolve("app"));
        Files.writeString(old.resolve("proxyma-desktop.jar"), "old");

        ClassDataSharing.deleteOtherReleases(dir);

        assertThat(dataRoot.resolve("class-data").resolve("old-part")).doesNotExist();
        assertThat(dir.resolve("app").resolve("proxyma-desktop.jar")).exists();
    }

    @Test
    @DisplayName("an old release's archive goes too, though the JVM wrote it read-only")
    void readOnlyArchiveGoes() throws IOException {
        Path old = Files.createDirectories(dataRoot.resolve("class-data").resolve("old-part"));
        Path archive = Files.writeString(old.resolve("app.jsa"), "archive");
        assertThat(archive.toFile().setWritable(false)).isTrue();

        ClassDataSharing.deleteOtherReleases(dir);

        assertThat(old).doesNotExist();
    }

    @Test
    @DisplayName("a start from the copy that died is followed by a packed one")
    void discardGoesBackToPacked() throws IOException {
        extracted();
        Launch launch = ClassDataSharing.plan(dir, packedJar, JVM);
        ClassDataSharing.discard(launch);
        assertThat(ClassDataSharing.plan(dir, packedJar, JVM).stage()).isEqualTo(Stage.PACKED);
    }

    @Test
    @DisplayName("an extraction that fails leaves nothing that a start would use")
    void failedExtraction() {
        // Not a Spring Boot jar, so the tools jar mode is not in it and the JVM refuses.
        Launch launch = ClassDataSharing.plan(dir, packedJar, JVM);
        assertThatThrownBy(() -> ClassDataSharing.prepare(launch, null, java(), JVM))
                .hasMessageContaining("extracting the application failed");
        assertThat(ClassDataSharing.plan(dir, packedJar, JVM).stage()).isEqualTo(Stage.PACKED);
    }

    @Test
    @DisplayName("the archive is made by a real JVM from the recorded list, and that JVM maps it")
    void realDump() throws Exception {
        // A real jar on the class path, standing in for the extracted application: SLF4J's.
        Path slf4j = Path.of(LoggerFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.createDirectories(dir.resolve("app"));
        Path thin = Files.copy(slf4j, dir.resolve("app").resolve("proxyma-desktop.jar"));
        Files.writeString(dir.resolve("extracted"), "");
        Files.writeString(dir.resolve("classes.recording"),
                "java/lang/Object id: 0\norg/slf4j/LoggerFactory\norg/slf4j/helpers/Util\norg/slf4j/Lo", StandardCharsets.UTF_8);

        Launch launch = ClassDataSharing.plan(dir, packedJar, JVM);
        assertThat(launch.stage()).isEqualTo(Stage.RECORDING);
        ClassDataSharing.prepare(launch, null, java(), JVM);

        Launch next = ClassDataSharing.plan(dir, packedJar, JVM);
        assertThat(next.stage()).isEqualTo(Stage.SHARED);
        assertThat(next.jar()).isEqualTo(thin);
        // -Xshare:on fails outright where auto would quietly go without: the archive maps, for this jar.
        List<String> check = new ArrayList<>(List.of(java(), next.jvmOptions().get(0), "-Xshare:on",
                "-cp", thin.toString(), "-version"));
        Process p = new ProcessBuilder(check).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(output).isZero();
    }

    private static String java() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }
}
