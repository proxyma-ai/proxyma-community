package ai.proxyma.core.settings.repository;

import ai.proxyma.core.settings.model.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSettingsRepository extends JpaRepository<UserSettings, String> {
}
