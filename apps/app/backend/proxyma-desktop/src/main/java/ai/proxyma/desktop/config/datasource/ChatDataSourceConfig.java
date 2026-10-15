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
        // Cost/pricing settings piggyback on the chat H2 file rather than getting a dedicated
        // datasource of their own - it's a handful of admin-configured rows, not a growing
        // per-item table, and ModelPricingService is only ever consumed from the chat feature.
        // The global settings store (GLOBAL_SETTINGS) rides here too for the same reason - a small
        // admin-config table; on server it lives in the single primary datasource instead.
        // ai.proxyma.core.settings.usage rides here for the same reason as pricing, and beside it
        // deliberately: PlatformSpendService prices every record through ModelPricingService, so the
        // ledger and the price list are read together and a spend row is never worth a datasource of
        // its own. Desktop has no user accounts, so platform spend is the only ledger there is here;
        // the per-user one (ChatSpendEvent) exists only under the server profile, in core-auth.
        //
        // BOTH lists below have to name it. A package missing from basePackages fails loudly at
        // startup ("required a bean of type PlatformSpendRepository"); missing from .packages() below
        // it fails later and worse, when a query hits an entity Hibernate never mapped.
        // ai.proxyma.core.settings.provider is here for the same reason again: the configured
        // provider instances are a handful of admin rows, read on every model build and beside the
        // price list that is keyed on their names.
        // ai.proxyma.core.settings.connection: the user's connections to Jira, Confluence and the
        // rest - a handful of rows, read by every sync and connector tool call.
        // ai.proxyma.core.settings.audit: the Audit Logs page's entries, beside the LLM call log it
        // is read with, and written on the same chatTransactionManager.
        basePackages            = { "ai.proxyma.core.chat.repository", "ai.proxyma.core.settings.pricing",
                                    "ai.proxyma.core.settings.global", "ai.proxyma.core.settings.usage",
                                    "ai.proxyma.core.settings.provider",
                                    "ai.proxyma.core.settings.connection",
                                    "ai.proxyma.core.settings.audit" },
        entityManagerFactoryRef = "chatEntityManagerFactory",
        transactionManagerRef   = "chatTransactionManager"
)
public class ChatDataSourceConfig {

    @Bean @ConfigurationProperties(prefix = "spring.datasource.chat")
    public DataSourceProperties chatDataSourceProperties() { return new DataSourceProperties(); }

    @Bean
    public DataSource chatDataSource(@Qualifier("chatDataSourceProperties") DataSourceProperties p) {
        return H2EnumColumns.relaxed(p.initializeDataSourceBuilder().build());
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean chatEntityManagerFactory(
            EntityManagerFactoryBuilder builder, @Qualifier("chatDataSource") DataSource ds) {
        return builder.dataSource(ds)
                .packages("ai.proxyma.core.chat.model", "ai.proxyma.core.settings.pricing",
                          "ai.proxyma.core.settings.global", "ai.proxyma.core.settings.usage",
                          "ai.proxyma.core.settings.provider",
                          "ai.proxyma.core.settings.connection",
                          "ai.proxyma.core.settings.audit")
                .persistenceUnit("chat")
                .properties(Map.of("hibernate.hbm2ddl.auto", "update",
                                   "hibernate.dialect", "org.hibernate.dialect.H2Dialect"))
                .build();
    }

    @Bean
    public PlatformTransactionManager chatTransactionManager(
            @Qualifier("chatEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
        return new JpaTransactionManager(emf.getObject());
    }
}
