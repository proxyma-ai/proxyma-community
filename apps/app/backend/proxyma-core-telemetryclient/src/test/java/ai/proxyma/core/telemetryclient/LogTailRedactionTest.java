package ai.proxyma.core.telemetryclient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * isd-03: the feedback form's log tail crosses to the telemetry server, and it went verbatim -
 * Desktop logs at DEBUG - while the daily diagnostics report carries no free text at all. Each
 * shape a log line has been seen to carry is pushed in, and the tail as it would be sent is
 * scanned for it.
 */
class LogTailRedactionTest {

    private static final String KEY = "sk-proj-4f9Qa7Zk2Lm8Xw1Rt6YbAbCd";
    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJlLXZhbHVl";
    private static final String MCP = "pxm_" + "0123456789abcdef".repeat(4);

    @Test
    @DisplayName("paths, query strings and credentials are taken out; the line is otherwise kept")
    void credentialsAndPathsLeaveNoTrace(@TempDir Path dir) throws Exception {
        String log = String.join("\n",
                "2026-10-14 DEBUG reading C:\\Users\\alice\\Documents\\merger-plan.pdf",
                "2026-10-14 DEBUG wrote /data/users/u-17/uploads/nda-contoso.docx",
                "2026-10-14 DEBUG GET https://jira.contoso.internal/rest/api/2/search?jql=project%3DSECRET",
                "2026-10-14 DEBUG Authorization: Bearer " + KEY,
                "2026-10-14 DEBUG body {\"api_key\": \"AIzaSyD-ExampleExampleExampleExample\", \"model\": \"gpt\"}",
                "2026-10-14 DEBUG password=hunter2-correct-horse token=abc123def456",
                "2026-10-14 DEBUG session " + JWT,
                "2026-10-14 DEBUG mcp token " + MCP,
                "2026-10-14 INFO Sync of 'Engineering wiki' finished: 42 items");
        Path file = dir.resolve("proxyma.log");
        Files.writeString(file, log);
        LogTailProvider provider = new LogTailProvider();
        ReflectionTestUtils.setField(provider, "logFileName", file.toString());

        String sent = provider.readTail();

        assertThat(sent).doesNotContain("alice", "u-17", "SECRET", KEY, "AIzaSyD", "hunter2", "abc123def456",
                JWT, MCP);
        assertThat(sent).contains("merger-plan.pdf", "https://jira.contoso.internal/rest/api/2/search?...",
                "Bearer ***", "\"model\": \"gpt\"", "finished: 42 items");
    }

    @Test
    @DisplayName("ordinary text and URLs without a query string pass untouched")
    void ordinaryTextIsUntouched() {
        String line = "2026-10-14 INFO fetched https://proxyma.ai/docs/ in 120 ms, max-output-tokens=4096";
        assertThat(LogTailProvider.redact(line)).isEqualTo(line);
    }
}
