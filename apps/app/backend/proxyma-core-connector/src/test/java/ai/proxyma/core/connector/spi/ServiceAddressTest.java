package ai.proxyma.core.connector.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Taking a typed address apart: the cutting every connector's own address rules are built on. */
class ServiceAddressTest {

    @Test
    @DisplayName("origin, host and segments; query, fragment and trailing slashes never count")
    void parts() {
        String typed = " https://Host.Example:8443/a/b/?q=1#top ";
        assertThat(ServiceAddress.origin(typed)).isEqualTo("https://host.example:8443");
        assertThat(ServiceAddress.host(typed)).isEqualTo("host.example");
        assertThat(ServiceAddress.segments(typed)).containsExactly("a", "b");
        assertThat(ServiceAddress.prefix(typed, 1)).isEqualTo("https://host.example:8443/a");
        assertThat(ServiceAddress.prefix(typed, 9)).isEqualTo("https://host.example:8443/a/b");
        assertThat(ServiceAddress.origin("HTTPS://user@Host.Example/x")).isEqualTo("https://host.example");
    }

    @Test
    @DisplayName("a marker cuts before it, wherever it is and in any case; the earliest of several wins")
    void markers() {
        assertThat(ServiceAddress.before("https://h/gitlab/api/v4/user", "api/v4")).contains("https://h/gitlab");
        assertThat(ServiceAddress.before("https://h/API/V4", "/api/v4/")).contains("https://h");
        assertThat(ServiceAddress.before("https://h/api/v3x", "api/v3")).isEmpty();
        assertThat(ServiceAddress.before("https://h/a/api", "api/v4")).isEmpty();
        assertThat(ServiceAddress.cutAt("https://h/g/p/-/issues/1/api/v4", "api/v4", "-")).isEqualTo("https://h/g/p");
        assertThat(ServiceAddress.cutAt("https://h/g/p/", "-")).isEqualTo("https://h/g/p");
    }

    @Test
    @DisplayName("what is not an http(s) URL with a host comes back trimmed, for the client to refuse in words")
    void notUrls() {
        for (String typed : List.of("acme.example/path/", "ftp://h/x/", "https:///x/")) {
            assertThat(ServiceAddress.host(typed)).as(typed).isEmpty();
            assertThat(ServiceAddress.cutAt(typed, "x")).as(typed).isEqualTo(typed.replaceAll("/+$", ""));
            assertThat(ServiceAddress.before(typed, "x")).as(typed).isEqualTo(Optional.empty());
        }
        assertThat(ServiceAddress.origin(null)).isNull();
    }
}
