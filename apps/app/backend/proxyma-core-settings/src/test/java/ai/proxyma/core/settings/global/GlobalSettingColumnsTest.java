package ai.proxyma.core.settings.global;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A setting's value is PostgreSQL {@code text}, never an {@code oid} large object (settings-core-10).
 *
 * <p>It was {@code @Lob}, under a comment saying that meant TEXT on both databases. On PostgreSQL it
 * meant {@code oid}: every changed value written as a new large object and the old one never
 * unlinked, and a value readable only inside the transaction that fetched its row - the class of bug
 * SubAgentRunColumnsTest pins for the sub-agent runs. Asked of the type Hibernate gives PostgreSQL,
 * which is what decides the column on a server install - no database needed.
 */
class GlobalSettingColumnsTest {

    @Test
    @DisplayName("on PostgreSQL a setting's value is a text column, not a large object")
    void valueIsText() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        try {
            Metadata metadata = new MetadataSources(registry).addAnnotatedClass(GlobalSetting.class).buildMetadata();
            PersistentClass entity = metadata.getEntityBinding(GlobalSetting.class.getName());
            Column column = entity.getProperty("settingValue").getColumns().get(0);
            assertEquals("text", column.getSqlType(metadata).toLowerCase(Locale.ROOT));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
