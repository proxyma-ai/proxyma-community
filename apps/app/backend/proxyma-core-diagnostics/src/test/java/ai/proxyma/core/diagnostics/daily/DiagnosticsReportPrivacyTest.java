package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.contracts.telemetry.TelemetryEventType;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsReport;
import ai.proxyma.core.common.diagnostics.DiagCounter;
import ai.proxyma.core.common.diagnostics.DiagLabels;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.DiagnosticsAggregator;
import ai.proxyma.core.common.diagnostics.DiagnosticsContributor;
import ai.proxyma.core.common.diagnostics.DiagnosticsLogAppender;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.common.diagnostics.ErrorCategory;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.telemetryclient.InstanceIdProvider;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * THE REPORT NEVER CARRIES WHAT A USER TYPED OR NAMED. Secrets are pushed in through every door
 * the report has - a label value, a log message, an exception message, an event payload, a
 * feature contributor - and the serialized report, exactly as it would be sent, is then scanned
 * for them and for anything shaped like a path or a URL.
 */
class DiagnosticsReportPrivacyTest {

    private static final String PATH = "C:\\Users\\alice\\Documents\\merger-plan.pdf";
    private static final String UNIX_PATH = "/home/alice/contracts/nda-contoso.docx";
    private static final String QUERY = "what is the acquisition price for Contoso";
    private static final String URL = "https://jira.contoso.internal/browse/SECRET-42";
    private static final String API_KEY = "sk-ant-api03-supersecretvalue";
    private static final String REALISTIC_KEY = "sk-proj-4f9Qa7Zk2Lm8Xw1Rt6Yb";
    private static final String INSTANCE_LIKE_ID = "9d0a1f2c3b4d5e6f";

    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\");
    private static final Pattern UNIX_HOME = Pattern.compile("/(home|Users)/");
    private static final Pattern ANY_URL = Pattern.compile("(?i)\\bhttps?://");

    @BeforeEach
    @AfterEach
    void clean() {
        Diagnostics.aggregator().clear();
    }

    @Test
    void theSerializedReportContainsNoneOfWhatWasPushedIn() throws Exception {
        DiagnosticsAggregator agg = Diagnostics.aggregator();

        // Labels: a careless call site passing a path, a URL, a query, a key.
        agg.count(DiagCounter.SOURCE_REGISTERED, "connector", PATH);
        agg.count(DiagCounter.SYNC_OUTCOME, "connector", "JIRA", "error", URL);
        agg.time(DiagOp.SEARCH_TOTAL, 120, "rerank", QUERY);
        agg.time(DiagOp.TOOL_CALL, 30, "tool", API_KEY + " " + UNIX_PATH);
        agg.time(DiagOp.INGEST_ITEM, 900, "fileClass", DiagLabels.fileClass(PATH), "connector", "LOCAL_FOLDER");
        agg.count(DiagCounter.LLM_FAILURE, "error", ErrorCategory.of(new RuntimeException(URL + " " + API_KEY)));
        // Token-shaped secrets: no space, no slash, nothing the character rule alone would catch.
        agg.count(DiagCounter.SYNC_OUTCOME, "connector", REALISTIC_KEY);
        agg.count(DiagCounter.SYNC_OUTCOME, "connector", INSTANCE_LIKE_ID);

        // The event path, with content-bearing fields in the payload.
        new TelemetryShipper().record(TelemetryEventType.SEARCH, Map.of("query", QUERY, "queryExpansion", true));
        new TelemetryShipper().record(TelemetryEventType.SOURCE_REGISTERED,
                Map.of("connectorType", "LOCAL_FOLDER", "config", UNIX_PATH, "name", "Contoso deal room"));

        // The log path: message and exception message both carry everything.
        LoggerContext ctx = new LoggerContext();
        DiagnosticsLogAppender appender = new DiagnosticsLogAppender(agg);
        appender.setContext(ctx);
        appender.start();
        Logger logger = ctx.getLogger("ai.proxyma.core.registry.service.SyncService");
        appender.doAppend(new LoggingEvent(Logger.class.getName(), logger, Level.ERROR,
                "Failed item {} for {}", new IllegalStateException(PATH + " " + QUERY + " " + URL),
                new Object[] {UNIX_PATH, API_KEY}));
        appender.doAppend(new LoggingEvent(Logger.class.getName(), logger, Level.WARN,
                "Skipping " + PATH + " at " + URL, null, null));

        // The feature snapshot, with a contributor that passes names and paths.
        DiagnosticsContributor careless = put -> {
            put.accept("sources", 3L);
            put.accept("sources." + PATH, 3L);
            put.accept("lastSource", UNIX_PATH);
            put.accept("apiKey", API_KEY);
            put.accept("nested", Map.of("q", QUERY));
            put.accept("list", List.of(URL));
        };
        DiagnosticsSnapshot snapshot = new DiagnosticsSnapshot(mock(SettingsService.class),
                mock(LlmProviderFactory.class), providerOf(careless), new MockEnvironment());

        InstanceIdProvider ids = mock(InstanceIdProvider.class);
        when(ids.getInstanceId()).thenReturn("0b7e3c51-7f0e-4a4e-9a51-9d0a1f2c3b4d");
        FeatureGateService gate = mock(FeatureGateService.class);
        when(gate.getCurrentTier()).thenReturn(LicenseTier.COMMUNITY);

        List<DiagnosticsDay> partials = agg.peek();
        DiagnosticsReport report = new DiagnosticsReportBuilder(ids, gate, snapshot).build(partials);
        String json = new ObjectMapper().writeValueAsString(report);

        assertThat(json).doesNotContain("alice", "merger", "Contoso", "contoso", "acquisition", "SECRET-42",
                "sk-ant", "supersecret", "nda-", "deal room", "Documents", "Skipping", "Failed item",
                "4f9Qa7", "sk-proj", INSTANCE_LIKE_ID);
        assertThat(WINDOWS_PATH.matcher(json).find()).as("a Windows path in: %s", json).isFalse();
        assertThat(UNIX_HOME.matcher(json).find()).as("a home directory in: %s", json).isFalse();
        assertThat(ANY_URL.matcher(json).find()).as("a URL in: %s", json).isFalse();

        // And the report is not empty - a test that passes because nothing was recorded proves nothing.
        DiagnosticsDay day = report.getDays().get(0);
        assertThat(day.getCounters()).isNotEmpty();
        assertThat(day.getTimings()).isNotEmpty();
        assertThat(day.getErrors()).hasSize(2);
        // The readable trace (2026-09-24) is Proxyma code and a line number, frame by frame - the
        // "Proxyma code location" the Privacy Policy allows, and nothing a library or a user wrote.
        assertThat(day.getErrors()).as("the error logged with an exception carries a trace")
                .anyMatch(e -> !e.getTrace().isEmpty() && !e.getCauses().isEmpty());
        day.getErrors().forEach(e -> {
            assertThat(e.getTrace()).allMatch(f -> f.startsWith("ai.proxyma.") && f.matches(".*#[^:]+:-?\\d+"));
            assertThat(e.getCauses()).allMatch(c -> !c.contains(" ") && !c.contains("/"));
        });
        assertThat(day.getFeatures()).containsKey("sources");
        assertThat(day.getFeatures()).doesNotContainKeys("nested", "list");
        assertThat(day.getEnvironment()).isNotNull();
        assertThat(report.getEdition()).isEqualTo("desktop");
    }

    private static ObjectProvider<DiagnosticsContributor> providerOf(DiagnosticsContributor... contributors) {
        return new ObjectProvider<>() {
            @Override public DiagnosticsContributor getObject(Object... args) { return contributors[0]; }
            @Override public DiagnosticsContributor getIfAvailable() { return contributors[0]; }
            @Override public DiagnosticsContributor getIfUnique() { return contributors[0]; }
            @Override public DiagnosticsContributor getObject() { return contributors[0]; }
            @Override public Iterator<DiagnosticsContributor> iterator() { return List.of(contributors).iterator(); }
            @Override public Stream<DiagnosticsContributor> stream() { return new ArrayList<>(List.of(contributors)).stream(); }
        };
    }
}
