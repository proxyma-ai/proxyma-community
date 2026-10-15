package ai.proxyma.core.system.controller;

import ai.proxyma.core.system.util.SystemInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this instance IS, and whether it is up. The health check for monitoring and for support.
 *
 * FIVE FACTS AND NOTHING ELSE: is it alive, which edition, which version, which platform, and how
 * long it has been running (in seconds, and in words). Those are the things somebody asks when a check goes red or a customer
 * opens a ticket, and every one of them is safe to say to an unauthenticated caller.
 *
 * WHY IT IS NOT /api/v1/config RENAMED, which is what was first asked for. That endpoint looks
 * like a health check and is not one - it is the SPA's configuration feed, and the fields that
 * make it a poor health check are each load-bearing somewhere:
 *
 *   branding                          ThemeProvider, AppShell, Logo, MaintenanceGate
 *   fileUpload                        whether a source is added by upload or by naming a folder
 *   homeServer                        the About tab, and which update channel Updates offers
 *   sandbox                           the Enterprise sandbox's banner and sign-in account list
 *
 * So the two concerns are separated rather than merged: this is the small, stable, public thing a
 * monitor polls, and /api/v1/config stays what it already was. A health endpoint that also carries
 * an application's feature flags is how a health endpoint comes to change shape whenever the
 * frontend does.
 *
 * NOT Spring Boot Actuator's /actuator/health. That would mean exposing the actuator on a desktop
 * install with no auth in front of it, and its default payload answers "are my dependencies up",
 * not "what am I" - which is the question this one exists for.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    // Asked once: which build this is cannot change while it runs, and the probe threw and caught a
    // ClassNotFoundException on every Desktop health poll (platform-14).
    private static final boolean SERVER_EDITION = isServerEdition();

    private final String version;
    /** Set by the home server image (PROXYMA_HOME_SERVER in deploy-homeserver/Dockerfile). */
    private final boolean homeServer;
    private final SystemInfo systemInfo;

    // Injected through the constructor rather than onto fields, so the payload can be asserted in
    // a plain unit test with no Spring context - which is the whole of what HealthControllerTest
    // needs to pin the one property this endpoint has to keep: that it stays small.
    public HealthController(@Value("${spring.application.version:0.0.0}") String version,
                            @Value("${proxyma.home-server:false}") boolean homeServer,
                            SystemInfo systemInfo) {
        this.version = version;
        this.homeServer = homeServer;
        this.systemInfo = systemInfo;
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> health() {
        return Mono.fromCallable(() -> {
            // LinkedHashMap so the JSON reads in the order somebody would ask the questions. A
            // health payload is read by people at least as often as by machines.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("edition", edition());
            body.put("version", version);
            body.put("platform", systemInfo.isWindows() ? "windows"
                    : systemInfo.isMac() ? "macos" : "linux");
            body.put("uptimeSeconds", uptime().toSeconds());
            body.put("uptime", humanUptime(uptime()));
            return ResponseEntity.ok(body);
        });
    }

    /**
     * Which of the three editions this is, in the words the About tab and a support mail use.
     *
     * DERIVED HERE RATHER THAN IN THE SPA, which is the point of putting it in a health payload:
     * the frontend works it out from its build mode plus the homeServer flag, and a monitor or a
     * support engineer curling this endpoint has neither. The home server is the DESKTOP jar in a
     * container, so build mode alone calls it "Desktop" - which is why that flag exists at all.
     */
    private String edition() {
        if (SERVER_EDITION) return "Enterprise Server";
        return homeServer ? "Home Server" : "Desktop";
    }

    /**
     * The server edition is a separate Maven profile, so the two are told apart by something only
     * that build puts on the classpath - not by a property, which a desktop install could be
     * started with by accident.
     *
     * <p>A marker RESOURCE rather than a class name: the marker ships in proxyma-core-auth (ee/), and
     * naming one of its classes here would be the Community code knowing the Enterprise code, which
     * the boundary check (tools/oss/check-boundary.mjs) refuses. Until 2026-10-15 this asked for
     * {@code SecurityConfig} by name; the marker sits in the same jar, so the answer is the same.
     */
    static final String ENTERPRISE_MARKER = "META-INF/proxyma/enterprise-edition";

    private static boolean isServerEdition() {
        ClassLoader loader = HealthController.class.getClassLoader();
        return loader != null && loader.getResource(ENTERPRISE_MARKER) != null;
    }

    private static Duration uptime() {
        return Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
    }

    /** "3d 4h 12m", for the half of readers who are people. Seconds are in the field above. */
    static String humanUptime(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m " + d.toSecondsPart() + "s";
    }
}
