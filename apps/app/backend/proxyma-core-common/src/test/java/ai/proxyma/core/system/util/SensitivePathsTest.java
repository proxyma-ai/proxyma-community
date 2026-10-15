package ai.proxyma.core.system.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SensitivePaths#scrub} is the last thing between a server-local path and a browser, so it
 * has two ways to fail and both have happened in this project's history.
 *
 * It can UNDER-match, and a real path reaches the client - which is the bug it was written for.
 * It can also OVER-match, and it did: `security.md` records that the drive-letter pattern ate
 * every URL the agent cited, because in "https://host" the "s" is a letter, ":" is a colon and
 * "//" is a separator, which is a textbook drive-letter match. Both lookbehinds in that file are
 * load-bearing, and the URL cases below are what stops someone "simplifying" them away.
 */
class SensitivePathsTest {

    @Nested
    @DisplayName("URLs must survive untouched - the over-matching bug")
    class UrlsSurvive {
        @ParameterizedTest
        @ValueSource(strings = {
                "https://proxyma.ai/docs/guardrails/",
                "http://localhost:4299/api/v1/chat",
                "https://github.com/proxyma-ai/proxyma/releases/latest",
                "See https://example.com/data/report for the numbers.",
                "https://host/opt/thing and https://host/var/other",
                "ftp://files.example.com/srv/pub/readme.txt",
        })
        void urlsAreNotScrubbed(String text) {
            assertEquals(text, SensitivePaths.scrub(text));
        }

        @Test
        @DisplayName("a URL and a real path in the same sentence: only the path goes")
        void mixedTextScrubsOnlyThePath() {
            String out = SensitivePaths.scrub(
                    "Downloaded https://proxyma.ai/x.zip to /home/alice/data/x.zip");
            assertTrue(out.contains("https://proxyma.ai/x.zip"), out);
            assertFalse(out.contains("/home/alice"), out);
            assertTrue(out.contains(".../x.zip"), out);
        }
    }

    @Nested
    @DisplayName("real paths must go - the under-matching bug")
    class PathsAreScrubbed {
        @Test
        void windowsPath() {
            assertEquals("...\\report.xlsx".replace('\\', '/'),
                    SensitivePaths.scrub("C:\\Users\\alice\\report.xlsx").replace('\\', '/'));
        }

        @Test
        @DisplayName("JSON-escaped backslashes, because this runs over serialized tool arguments")
        void jsonEscapedWindowsPath() {
            String out = SensitivePaths.scrub("{\"path\":\"C:\\\\Users\\\\alice\\\\report.xlsx\"}");
            assertFalse(out.contains("Users"), out);
            assertTrue(out.contains("report.xlsx"), out);
        }

        @Test
        @DisplayName("a per-user data directory carries the user id, which is the point")
        void posixUserDataPath() {
            String out = SensitivePaths.scrub("/data/users/u-8813/conversations/c-42/notes.txt");
            assertFalse(out.contains("u-8813"), out);
            assertFalse(out.contains("conversations"), out);
            assertEquals(".../notes.txt", out);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/opt/proxyma-server/config/application.properties",
                "/var/lib/proxyma/index/segments",
                "/home/alice/.proxyma/skills/secret.md",
                "/etc/cloudflared/token",
                "/tmp/pxtest/chroma/chroma.sqlite3",
        })
        void serverDirectoriesAreScrubbed(String path) {
            String out = SensitivePaths.scrub(path);
            assertTrue(out.startsWith(".../"), "not scrubbed: " + out);
            assertFalse(out.contains("/opt/"), out);
            assertFalse(out.contains("/etc/"), out);
        }

        @Test
        @DisplayName("the final segment survives, because \"[redacted]\" tells the user nothing")
        void lastSegmentIsKept() {
            assertEquals(".../budget-2026.xlsx",
                    SensitivePaths.scrub("/home/alice/docs/budget-2026.xlsx"));
        }
    }

    @Nested
    @DisplayName("ordinary prose must not be mangled")
    class ProseSurvives {
        @ParameterizedTest
        @ValueSource(strings = {
                "Use and/or as you like.",
                "The ratio is 3/4 of the total.",
                "A bare / on its own.",
                "Nothing here resembles a path at all.",
                "Rated 9/10 by the team.",
        })
        void proseIsUnchanged(String text) {
            assertEquals(text, SensitivePaths.scrub(text));
        }
    }

    @Nested
    @DisplayName("the replacement must never break a JSON document it is substituted into")
    class ReplacementIsSafe {
        @Test
        void replacementCarriesNoBackslashOrQuote() {
            String out = SensitivePaths.scrub("C:\\Users\\x\\a\"b.txt");
            assertFalse(out.contains("\\"), "replacement reintroduced a backslash: " + out);
        }
    }

    @Nested
    @DisplayName("null and empty are passed through rather than thrown on")
    class NullSafety {
        @Test
        void nullIsReturned() {
            assertEquals(null, SensitivePaths.scrub(null));
        }

        @Test
        void emptyIsReturned() {
            assertEquals("", SensitivePaths.scrub(""));
        }
    }
}
