package ai.proxyma.core.system.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single-user install's access code: made once, kept to its owner, the same for the launcher and
 * the app, and never itself the cookie or the token.
 */
class AccessCodeTest {

    @Test
    void madeOnceAndReadBackTheSame(@TempDir Path data) throws Exception {
        String code = AccessCode.loadOrCreate(data);
        assertThat(code).hasSizeGreaterThanOrEqualTo(43);
        assertThat(AccessCode.loadOrCreate(data)).isEqualTo(code);
        assertThat(AccessCode.resolve("", data)).as("a blank PROXYMA_ACCESS_CODE is unset").isEqualTo(code);
        assertThat(Files.list(data)).as("no temporary file left behind").hasSize(1);
    }

    @Test
    void ownerOnlyWhereTheFilesystemCanSaySo(@TempDir Path data) throws Exception {
        AccessCode.loadOrCreate(data);
        Path file = data.resolve(AccessCode.FILE_NAME);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
    }

    @Test
    void aFileTooShortToBeACodeIsReplaced(@TempDir Path data) throws Exception {
        Files.writeString(data.resolve(AccessCode.FILE_NAME), "");
        assertThat(AccessCode.loadOrCreate(data)).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void aConfiguredCodeWinsAndMustBeLongEnough(@TempDir Path data) throws Exception {
        assertThat(AccessCode.resolve("  my-own-long-access-code  ", data)).isEqualTo("my-own-long-access-code");
        assertThat(Files.exists(data.resolve(AccessCode.FILE_NAME))).as("nothing generated").isFalse();
        assertThatThrownBy(() -> AccessCode.resolve("1234", data)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCookieAndTheTokenAreDerivedNotTheCode() {
        String code = AccessCode.generate();
        assertThat(AccessCode.sessionValue(code)).isNotEqualTo(code).isEqualTo(AccessCode.sessionValue(code));
        assertThat(AccessCode.bearerToken(code)).matches("pxm_[0-9a-f]{64}");
        assertThat(AccessCode.bearerToken(code)).isNotEqualTo(AccessCode.bearerToken(AccessCode.generate()));
        assertThat(AccessCode.matches("a", "a")).isTrue();
        assertThat(AccessCode.matches("a", "b")).isFalse();
        assertThat(AccessCode.matches(null, "b")).isFalse();
    }
}
