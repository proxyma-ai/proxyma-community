package ai.proxyma.desktop.config.datasource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "ai.proxyma.core.diagnostics",
        entityManagerFactoryRef = "diagnosticsEntityManagerFactory",
        transactionManagerRef = "diagnosticsTransactionManager"
)
public class DiagnosticsDataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.diagnostics")
    public DataSourceProperties diagnosticsDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource diagnosticsDataSource(
            @Qualifier("diagnosticsDataSourceProperties") DataSourceProperties props) {
        return H2EnumColumns.relaxed(props.initializeDataSourceBuilder().build());
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean diagnosticsEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("diagnosticsDataSource") DataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages("ai.proxyma.core.diagnostics")
                .persistenceUnit("diagnostics")
                .properties(Map.of(
                        "hibernate.hbm2ddl.auto", "update",
                        "hibernate.dialect", "org.hibernate.dialect.H2Dialect",
                        "hibernate.show_sql", false))
                .build();
    }

    @Bean
    public PlatformTransactionManager diagnosticsTransactionManager(
            @Qualifier("diagnosticsEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
        return new JpaTransactionManager(emf.getObject());
    }
}
