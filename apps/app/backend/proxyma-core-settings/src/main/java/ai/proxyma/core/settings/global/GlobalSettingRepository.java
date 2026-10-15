package ai.proxyma.core.settings.global;

import org.springframework.data.jpa.repository.JpaRepository;

// Authoritative persistence for deployment-wide settings (see GlobalSetting). Scanned into the
// primary datasource on server (ServerDatabaseConfig) and into the chat H2 datasource on desktop
// (ChatDataSourceConfig), so it is always backed by a real database in both runtime modes.
public interface GlobalSettingRepository extends JpaRepository<GlobalSetting, String> {
}
