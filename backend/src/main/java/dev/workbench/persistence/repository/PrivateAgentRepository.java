package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PrivateAgentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 私有 Agent 草稿读取；所有按 ID 的读取都必须带当前归属。 */
public interface PrivateAgentRepository extends JpaRepository<PrivateAgentEntity, String> {
  List<PrivateAgentEntity> findAllByOwnerScopeKeyOrderByUpdatedAtDesc(String ownerScopeKey);

  Optional<PrivateAgentEntity> findByIdAndOwnerScopeKey(String id, String ownerScopeKey);
}
