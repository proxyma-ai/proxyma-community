package ai.proxyma.core.update;

import ai.proxyma.core.settings.dto.UpdatesSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.parts.Platform;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cancel works while the download is still WAITING for the server. The cancel was a flag read
 * between chunks of a body already arriving, so on a slow network - a connection or a response that
 * takes minutes - pressing Cancel did nothing until a timeout (feedback, 2026-09-29). Here the
 * server accepts the request and never answers.
 */
class UpdateCancelTest {

    @TempDir Path parts;
    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch requested = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        if (server != null) server.stop(0);
        System.clearProperty(UpdateService.PARTS_DIR_PROPERTY);
    }

    @Test
    @DisplayName("a cancel while waiting for the server ends the download within seconds")
    void cancelWhileWaitingForAResponse() throws Exception {
        String platform = Platform.current();
        assumeTrue(platform != null, "the parts channel needs a supported platform");
        System.setProperty(UpdateService.PARTS_DIR_PROPERTY, parts.toString());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);   // a server that never answers in time
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/download/v0.99.0/";

        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings(UpdatesSettings.PREFIX, UpdatesSettings.class))
                .thenReturn(new UpdatesSettings());
        // The JDK client, as RestClientConfig builds it for the app.
        RestClient.Builder builder = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory());
        UpdateService service = new UpdateService(builder, settingsService, mock(UpdateHistory.class));
        ReflectionTestUtils.setField(service, "currentVersion", "0.20.0");
        ReflectionTestUtils.setField(service, "status", UpdateService.UpdateStatus.AVAILABLE);
        ReflectionTestUtils.setField(service, "pending", release("v0.99.0", base + "parts-" + platform + ".txt"));

        service.downloadNow();
        assertTrue(requested.await(10, TimeUnit.SECONDS), "the download should have reached the server");
        assertEquals(UpdateService.UpdateStatus.DOWNLOADING, service.getStatus());

        long start = System.nanoTime();
        service.cancelDownload();
        while (service.getStatus() == UpdateService.UpdateStatus.DOWNLOADING
                && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10)) {
            Thread.sleep(50);
        }

        assertEquals(UpdateService.UpdateStatus.AVAILABLE, service.getStatus(),
                "cancelled, and the same version can be downloaded again by hand");
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5),
                "a cancel must not wait for the server to answer");
    }

    private static Object release(String tag, String assetUrl) throws Exception {
        Class<?> type = Class.forName("ai.proxyma.core.update.UpdateService$Release");
        Constructor<?> ctor = type.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(tag, assetUrl);
    }
}
