package ai.proxyma.core.connector.spi;

/**
 * The scope settings every source row carries beside its configuration.
 *
 * @param fileTypes comma-separated extensions to keep, or null for every supported type
 *                  (FileTypeSupport.parseFileTypes semantics) - meaningful only where the
 *                  descriptor declares a file-type filter
 * @param recursive whether to descend into subfolders - meaningful only where it declares one
 * @param maxItems  a ceiling on what one listing returns, or null for none
 */
public record SourceScope(String fileTypes, boolean recursive, Integer maxItems) {

    public static SourceScope defaults() {
        return new SourceScope(null, true, null);
    }
}
