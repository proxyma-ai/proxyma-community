package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rewrites rows written before ConnectorType.FILES was split into
 * {@link ConnectorType#LOCAL_FOLDER} and {@link ConnectorType#UPLOADED_FILES}.
 *
 * WHY THIS IS CODE AND NOT A MIGRATION FILE. connectorType is mapped
 * {@code @Enumerated(EnumType.STRING)} on both RegisteredConnector and TrackedDocument, so the
 * literal text "FILES" is what sits in the database of every install that predates the split. This
 * project has no Flyway and no Liquibase; the schema is managed by {@code ddl-auto=update}. There
 * is no migration file this could have been, and nothing that would run before the first read of
 * those tables. So FILES stays in the enum as a deprecated, never-written constant - old rows
 * always deserialize - and this rewrites them at startup.
 *
 * WHICH VALUE A LEGACY ROW BECOMES is decided by the edition doing the migrating, which is a fact
 * rather than a guess: desktop and server never share a database. Every FILES row in a desktop H2
 * file was registered by reference and is a LOCAL_FOLDER; every FILES row in a server's PostgreSQL
 * was an upload into that user's sandbox and is UPLOADED_FILES.
 *
 * <h2>Why an ApplicationRunner with an explicit TransactionTemplate</h2>
 *
 * The first version of this class was a {@code @PostConstruct} method annotated
 * {@code @Transactional}, and it never once ran. {@code @Transactional} is applied by a proxy
 * wrapped AROUND the bean, and {@code @PostConstruct} runs during that bean's own initialisation -
 * on the raw target, before any proxy is in play - so the annotation is silently ignored. The
 * {@code @Modifying} bulk update underneath it therefore executed with no transaction and threw
 * {@code TransactionRequiredException} on every boot. It was caught and logged by the handler
 * below, exactly as designed, so startup succeeded and the application served traffic normally
 * while quietly migrating nothing at all.
 *
 * Both halves of the fix matter:
 * <ul>
 *   <li>{@link ApplicationRunner} instead of {@code @PostConstruct} - runs after the context is
 *       fully refreshed, so there are no bean-initialisation ordering subtleties left to get
 *       wrong.</li>
 *   <li>A programmatic {@link TransactionTemplate} instead of {@code @Transactional} - it depends
 *       on no proxy whatsoever, so it cannot be defeated the same way again by some future caller
 *       invoking this from a context where AOP does not apply.</li>
 * </ul>
 *
 * Idempotent, and free once it has run - the UPDATE matches nothing on every later boot. Delete
 * this class, and the FILES constant, only once every deployment that matters has been through a
 * release carrying it.
 */
@Component
public class LegacyFilesTypeMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyFilesTypeMigrator.class);

    private final RegisteredConnectorRepository connectorRepository;
    private final TrackedDocumentRepository documentRepository;
    private final TransactionTemplate transactionTemplate;
    private final boolean multiUser;

    public LegacyFilesTypeMigrator(RegisteredConnectorRepository connectorRepository,
                                   TrackedDocumentRepository documentRepository,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.connectorRepository = connectorRepository;
        this.documentRepository = documentRepository;
        // UNQUALIFIED on purpose, which resolves the @Primary manager in both editions and is the
        // right one in each:
        //
        //  - desktop wires one transaction manager per connector database, and the @Primary one is
        //    localTransactionManager - the very manager LocalDataSourceConfig binds these two
        //    repositories to via @EnableJpaRepositories(transactionManagerRef=...).
        //  - proxyma-server declares no JPA configuration at all and lets Boot autoconfigure a
        //    single datasource, so there is exactly one manager to resolve.
        //
        // Deliberately NOT @Qualifier("connectorTransactionManager"), which is what the rest of this
        // module names in its @Transactional annotations: no bean by that name is defined anywhere
        // in this repository. Whatever that turns out to mean for those annotations, a startup
        // migration is the wrong place to find out.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.multiUser = multiUser;
    }

    @Override
    public void run(ApplicationArguments args) {
        ConnectorType target = multiUser ? ConnectorType.UPLOADED_FILES : ConnectorType.LOCAL_FOLDER;

        int connectors = rewrite("registered connectors",
                () -> connectorRepository.rewriteConnectorType(legacyFilesType(), target));
        int documents = rewrite("tracked documents",
                () -> documentRepository.rewriteConnectorType(legacyFilesType(), target));

        // Logged UNCONDITIONALLY, including the nothing-to-do case. The previous version only
        // spoke when it had migrated something, which meant silence covered both "there was
        // nothing to migrate" and "this never ran" - and when it never ran, that ambiguity is
        // precisely what hid the failure. A one-line INFO on every boot is a small price for
        // "did the migration happen?" being answerable from the log alone.
        log.info("[LegacyFilesType] Legacy FILES rows migrated to {}: {} connectors, {} tracked documents",
                target, connectors, documents);
    }

    // Never fails startup. A deployment that cannot migrate has pre-existing file connectors that
    // will not be seen by the edition-scoped lookups - bad, and loudly logged - but aborting the
    // whole application over it helps nobody and takes every OTHER connector down with it.
    private int rewrite(String what, java.util.function.IntSupplier update) {
        try {
            Integer updated = transactionTemplate.execute(status -> update.getAsInt());
            return updated != null ? updated : 0;
        } catch (Exception e) {
            log.error("[LegacyFilesType] Could not migrate legacy FILES {} - pre-existing file "
                    + "connectors may not load until this is resolved", what, e);
            return 0;
        }
    }

    // Isolated in one method so the deprecation warning is suppressed at exactly the one place the
    // legacy constant is legitimately named, rather than across the whole class.
    @SuppressWarnings("deprecation")
    private static ConnectorType legacyFilesType() {
        return ConnectorType.FILES;
    }
}
