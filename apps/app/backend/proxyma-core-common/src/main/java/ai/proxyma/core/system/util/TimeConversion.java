package ai.proxyma.core.system.util;

public final class TimeConversion {

    private TimeConversion() {}

    public static String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long hours   = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
