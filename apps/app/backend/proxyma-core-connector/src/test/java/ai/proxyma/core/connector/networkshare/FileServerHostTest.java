package ai.proxyma.core.connector.networkshare;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a Network share connection's server field accepts. Every way Windows writes a server names
 * the same host, and the host is what a source's path is compared with - so they must all reduce
 * to one string, and nothing that names more than one server may get through.
 */
class FileServerHostTest {

    @Test
    void everyWindowsSpellingIsTheSameHost() {
        for (String raw : new String[]{"FileServer", "\\\\FileServer", "//fileserver", "smb://FILESERVER",
                "\\\\fileserver\\Share\\Folder", "smb://fileserver/share/a", " fileserver. "}) {
            assertThat(FileServerHost.normalize(raw)).as(raw).isEqualTo("fileserver");
        }
        assertThat(FileServerHost.normalize("FS1.Corp.Example.COM")).isEqualTo("fs1.corp.example.com");
        assertThat(FileServerHost.normalize("10.0.0.5")).isEqualTo("10.0.0.5");
    }

    @Test
    void aPatternAPortOrNothingIsRefused() {
        assertThatThrownBy(() -> FileServerHost.normalize("*.corp.example.com")).hasMessageContaining("one file server");
        assertThatThrownBy(() -> FileServerHost.normalize("fileserver:445")).hasMessageContaining("only the file server");
        assertThatThrownBy(() -> FileServerHost.normalize("user@fileserver")).hasMessageContaining("only the file server");
        assertThatThrownBy(() -> FileServerHost.normalize("  ")).hasMessageContaining("Enter the file server");
        assertThatThrownBy(() -> FileServerHost.normalize("\\\\")).hasMessageContaining("Enter the file server");
    }
}
