package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PrivateToolEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 私有工具草稿读取；所有按 ID 的读取都必须带当前归属。 */
public interface PrivateToolRepository extends JpaRepository<PrivateToolEntity, String> {
  List<PrivateToolEntity> findAllByOwnerScopeKeyOrderByUpdatedAtDesc(String ownerScopeKey);

  Optional<PrivateToolEntity> findByIdAndOwnerScopeKey(String id, String ownerScopeKey);
}
