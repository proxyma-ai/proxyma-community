package ai.proxyma.core.tools.office;

import ai.proxyma.core.ingest.service.PythonRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * Which {@link PythonSandbox} this edition runs code in.
 *
 * <ul>
 *   <li>{@code proxyma.sandbox.url} set - the sandbox container. Home Server's and Enterprise
 *   Server's compose files set it (PROXYMA_SANDBOX_URL).</li>
 *   <li>Otherwise, when {@code proxyma.sandbox.local-enabled} (the default) - the bundled Python,
 *   as a child process. That is Desktop.</li>
 *   <li>Otherwise none: runPython says so. Neither server edition ever runs it locally - by its
 *   edition, and by local-enabled=false in its configuration - so a
 *   server whose sandbox container is missing refuses to run code rather than falling back to
 *   running it inside the app's own container, next to every user's data (the maintainer's
 *   decision, 2026-09-26).</li>
 * </ul>
 */
@Configuration
public class SandboxConfig {

    @Bean
    PythonSandbox pythonSandbox(@Value("${proxyma.sandbox.url:}") String url,
                                @Value("${proxyma.sandbox.token:}") String token,
                                @Value("${proxyma.sandbox.local-enabled:true}") boolean localEnabled,
                                @Value("${proxyma.multi-user:false}") boolean multiUser,
                                @Value("${proxyma.home-server:false}") boolean homeServer,
                                PythonRuntime python) {
        if (!url.isBlank()) return new ContainerPythonSandbox(url, token);
        // Desktop by EDITION as well: a Home Server image started without its compose environment
        // (docker run, Kubernetes) has no sandbox URL and local-enabled at its default, and ran
        // model-written code inside the app's own container.
        if (localEnabled && !multiUser && !homeServer) return new LocalPythonSandbox(python);
        return new PythonSandbox() {
            @Override public boolean isAvailable() { return false; }
            @Override public Result run(String code, Map<String, byte[]> inputs, Duration timeout) {
                throw new IllegalStateException("No sandbox is configured");
            }
        };
    }
}
