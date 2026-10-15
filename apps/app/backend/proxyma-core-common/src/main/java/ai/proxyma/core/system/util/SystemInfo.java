package ai.proxyma.core.system.util;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Immutable OS/architecture snapshot.
// Use SystemInfo.detect() in pre-Spring contexts; inject the bean everywhere else.
public record SystemInfo(boolean isWindows, boolean isMac, boolean isLinux, boolean isArm) {

    public static SystemInfo detect() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return new SystemInfo(
                os.contains("win"),
                os.contains("mac"),
                os.contains("nix") || os.contains("nux"),
                arch.contains("arm") || arch.contains("aarch64"));
    }

    @Configuration
    static class SystemInfoConfig {
        @Bean
        public SystemInfo systemInfo() {
            return SystemInfo.detect();
        }
    }
}
