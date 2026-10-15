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

// Own H2 file rather than riding along in chat_history, even though every one of these tables is
// written from a chat/search turn: this is append-only instrumentation that grows per turn and is
// wiped independently of conversations (turning Evaluation Mode off and deleting its file must not
// take chat history with it), and the anonymity design in proxyma-core-evaluation depends on there
// being no join path from an eval row back to a real conversation - which a shared datasource
// would quietly hand back.
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "ai.proxyma.core.evaluation.repository",
        entityManagerFactoryRef = "evaluationEntityManagerFactory",
        transactionManagerRef = "evaluationTransactionManager"
)
public class EvaluationDataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.evaluation")
    public DataSourceProperties evaluationDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource evaluationDataSource(
            @Qualifier("evaluationDataSourceProperties") DataSourceProperties props) {
        return H2EnumColumns.relaxed(props.initializeDataSourceBuilder().build());
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean evaluationEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("evaluationDataSource") DataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages("ai.proxyma.core.evaluation.model")
                .persistenceUnit("evaluation")
                .properties(Map.of(
                        "hibernate.hbm2ddl.auto", "update",
                        "hibernate.dialect", "org.hibernate.dialect.H2Dialect",
                        "hibernate.show_sql", false))
                .build();
    }

    @Bean
    public PlatformTransactionManager evaluationTransactionManager(
            @Qualifier("evaluationEntityManagerFactory") LocalContainerEntityManagerFactoryBean emf) {
        return new JpaTransactionManager(emf.getObject());
    }
}
