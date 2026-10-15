package ai.proxyma.core.system.parts;

import java.util.Locale;

/** The platform names parts are published for. A contract with tools/release/parts.py. */
public final class Platform {

    public static final String WINDOWS_X64 = "windows-x64";
    public static final String LINUX_X64 = "linux-x64";

    private Platform() {}

    /** This machine's platform, or null where no desktop build exists (macOS, ARM). */
    public static String current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!x64) return null;
        if (os.contains("win")) return WINDOWS_X64;
        if (os.contains("linux")) return LINUX_X64;
        return null;
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
