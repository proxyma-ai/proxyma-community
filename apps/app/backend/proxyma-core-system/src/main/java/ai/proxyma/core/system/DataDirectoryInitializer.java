package ai.proxyma.core.system;

import ai.proxyma.core.system.util.AppPaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Creates the data root on first launch - and ONLY the data root, since 2026-09-29.
//
// It used to create seven folders up front, and two of them nothing ever wrote to: `conversations`
// (desktop keeps conversations under proxyma.conversations-dir, ~/.proxyma/conversations by default)
// and `markdown` (a "save markdown" setting whose writer had long gone). Every other folder is made
// by the code that uses it, at the moment it first needs it - ChromaDB at start, Lucene per index,
// temp before a cache write, update-staging before the update history - so an edition that never
// uses one (Enterprise Server has no ChromaDB) never gets it.
@Component
public class DataDirectoryInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataDirectoryInitializer.class);

    private final AppPaths appPaths;

    public DataDirectoryInitializer(AppPaths appPaths) {
        this.appPaths = appPaths;
    }

    // Owner-only on POSIX - see AppPaths.createDataRoot.
    @PostConstruct
    public void init() {
        try {
            appPaths.createDataRoot();
        } catch (IOException e) {
            log.warn("Could not create directory {}: {}", appPaths.dataRoot(), e.getMessage());
        }
    }
}
