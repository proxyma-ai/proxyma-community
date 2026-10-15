package ai.proxyma.desktop.config.datasource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
        basePackages = {
            "ai.proxyma.core.registry.repository",
            "ai.proxyma.core.registry.files.upload.repository",
            // Knowledge graph. It rides the @Primary (local) datasource rather than getting one of
            // its own: kg_mentions is keyed by connectorId and documentId, so it is registry-shaped
            // data, and putting it in a separate H2 file would make the orphan purge a cross-
            // database operation with no transaction spanning it.
            "ai.proxyma.core.graph.repository"
        },
        entityManagerFactoryRef = "localEntityManagerFactory",
        transactionManagerRef   = "localTransactionManager"
)
public class LocalDataSourceConfig {

    @Bean @Primary
    @ConfigurationProperties(prefix = "spring.datasource.local")
    public DataSourceProperties localDataSourceProperties() { return new DataSourceProperties(); }

    @Bean @Primary
    public DataSource localDataSource(@Qualifier("localDataSourceProperties") DataSourceProperties p) {
        return H2EnumColumns.relaxed(p.initializeDataSourceBuilder().build());
    }

    @Bean @Primary
    public LocalContainerEntityManagerFactoryBean localEntityManagerFactory(
            EntityManagerFactoryBuilder builder, @Qualifier("localDataSource") DataSource ds) {
        return builder.dataSource(ds)
                .packages("ai.proxyma.core.registry.model",
                          "ai.proxyma.core.registry.files.upload.model",
                          "ai.proxyma.core.graph.model")
                .persistenceUnit("local")
                .properties(Map.of("hibernate.hbm2ddl.auto", "update",
                                   "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))
                .build();
    }

    @Bean @Primary
    public PlatformTransactionManager localTransactionManager(
            @Qualifier("localEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
        return new JpaTransactionManager(emf.getObject());
    }
}
