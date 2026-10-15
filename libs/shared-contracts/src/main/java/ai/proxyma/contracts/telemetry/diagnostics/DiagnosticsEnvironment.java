package ai.proxyma.contracts.telemetry.diagnostics;

/**
 * What kind of machine an installation runs on - coarse on purpose.
 *
 * <p>Enough to answer "is this slow only on 8 GB machines" or "does anyone run the ARM build",
 * and too coarse to single a machine out: the OS family rather than its build number, memory as
 * a range rather than a figure, and never a hostname, a user name or a hardware identifier.
 */
public class DiagnosticsEnvironment {

    /** "Windows", "Linux", "Mac" - the OS family, never its version or build. */
    private String os;
    /** "amd64", "aarch64". */
    private String arch;
    private int cpuCores;
    /** "<8", "8-16", "16-32", "32+" (GB), or "unknown". */
    private String memoryGb;
    /** The Java feature release, e.g. 21. */
    private int javaVersion;

    public DiagnosticsEnvironment() {}

    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }

    public String getArch() { return arch; }
    public void setArch(String arch) { this.arch = arch; }

    public int getCpuCores() { return cpuCores; }
    public void setCpuCores(int cpuCores) { this.cpuCores = cpuCores; }

    public String getMemoryGb() { return memoryGb; }
    public void setMemoryGb(String memoryGb) { this.memoryGb = memoryGb; }

    public int getJavaVersion() { return javaVersion; }
    public void setJavaVersion(int javaVersion) { this.javaVersion = javaVersion; }
}
