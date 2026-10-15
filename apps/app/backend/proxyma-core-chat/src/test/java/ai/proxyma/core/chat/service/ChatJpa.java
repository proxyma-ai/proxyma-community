package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.ChatSpendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * The chat module's persistence as the product wires it, on H2: Spring Data's own repository
 * proxies, a {@code chatTransactionManager}, and SmartChatMemory as a proxied bean whose
 * {@code @Transactional} methods really open transactions - and nothing else.
 *
 * <p>Not a raw SessionFactory, which is what the repository tests used before, and which is why a
 * derived delete with no transaction passed every test and failed in the product: what decides
 * whether a query method runs in a transaction is the repository PROXY, so only a test that goes
 * through one can see it. Nothing here runs inside an outer transaction unless a test opens one.
 */
final class ChatJpa implements AutoCloseable {

    record Settings(String conversationsDir, boolean multiUser) {}

    private final AnnotationConfigApplicationContext context;

    ChatJpa(Path conversationsDir, boolean multiUser) {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(Settings.class, () -> new Settings(conversationsDir.toString(), multiUser));
        context.register(Config.class);
        context.refresh();
    }

    <T> T get(Class<T> type) {
        return context.getBean(type);
    }

    @Override
    public void close() {
        context.close();
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories(basePackages = "ai.proxyma.core.chat.repository",
            entityManagerFactoryRef = "chatEntityManagerFactory", transactionManagerRef = "chatTransactionManager")
    @Import(JpaChatMemoryRepository.class)
    static class Config {

        @Bean
        DataSource chatDataSource() {
            // A connection per call, so the database has to outlive each one.
            return new DriverManagerDataSource("jdbc:h2:mem:chat-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean chatEntityManagerFactory(DataSource chatDataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(chatDataSource);
            factory.setPackagesToScan("ai.proxyma.core.chat.model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        PlatformTransactionManager chatTransactionManager(EntityManagerFactory chatEntityManagerFactory) {
            return new JpaTransactionManager(chatEntityManagerFactory);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        // Mocks, as beans, so a test can stub what it needs of them - the condensation's model call.
        @Bean
        LlmGateway llmGateway() {
            return mock(LlmGateway.class);
        }

        @Bean
        ModelPricingService modelPricingService() {
            return mock(ModelPricingService.class);
        }

        @Bean
        SmartChatMemory smartChatMemory(JpaChatMemoryRepository repository, ConversationRepository conversations,
                                        ChatMessageRepository messages, ApplicationEventPublisher events,
                                        ObjectMapper objectMapper, Settings settings, LlmGateway llmGateway,
                                        ModelPricingService pricing) {
            return new SmartChatMemory(repository, conversations, messages, llmGateway,
                    mock(SettingsService.class), pricing, mock(ChatSpendService.class),
                    events, new ChatEventSink(), settings.conversationsDir(), objectMapper, settings.multiUser());
        }
    }
}
