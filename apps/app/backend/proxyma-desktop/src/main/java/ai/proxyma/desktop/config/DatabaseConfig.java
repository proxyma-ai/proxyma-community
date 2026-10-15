package ai.proxyma.desktop.config;

import ai.proxyma.desktop.config.datasource.LocalDataSourceConfig;
import ai.proxyma.desktop.config.datasource.ChatDataSourceConfig;
import ai.proxyma.desktop.config.datasource.DiagnosticsDataSourceConfig;
import ai.proxyma.desktop.config.datasource.EvaluationDataSourceConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

// Aggregates all H2 datasource configs. Each is its own H2 file with a dedicated
// EntityManagerFactory and TransactionManager - non-overlapping basePackages prevent JPA scan
// conflicts. The six per-connector datasources (Jira, Confluence, Bitbucket, SVN, SharePoint,
// Teams) were removed on 2026-09-29: their packages no longer existed, so each only created an
// empty file under the data root on every start. Every source lives in the registry (Local).
@Configuration
@Import({
    LocalDataSourceConfig.class,
    ChatDataSourceConfig.class,
    DiagnosticsDataSourceConfig.class,
    EvaluationDataSourceConfig.class
})
public class DatabaseConfig {
}
