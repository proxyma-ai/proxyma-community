package ai.proxyma.desktop.config.datasource;

import java.util.List;

/**
 * Entity packages a module on the classpath maps into the chat H2 file, beside the ones
 * {@link ChatDataSourceConfig} names - for a module this build does not know about, which adds its
 * tables there and registers its own repositories against {@code chatEntityManagerFactory} and
 * {@code chatTransactionManager}. None in the Community build.
 */
public interface ChatEntityPackages {

    List<String> packages();
}
