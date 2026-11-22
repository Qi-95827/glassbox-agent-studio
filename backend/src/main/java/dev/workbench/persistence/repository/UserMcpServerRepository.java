package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.UserMcpServerEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMcpServerRepository extends JpaRepository<UserMcpServerEntity, String> {
  List<UserMcpServerEntity> findByOwnerScopeKeyOrderByUpdatedAtDesc(String ownerScopeKey);

  List<UserMcpServerEntity> findByOwnerScopeKeyAndEnabledTrueOrderByUpdatedAtDesc(
      String ownerScopeKey);

  Optional<UserMcpServerEntity> findByIdAndOwnerScopeKey(String id, String ownerScopeKey);

  boolean existsByOwnerScopeKeyAndSlug(String ownerScopeKey, String slug);
}
