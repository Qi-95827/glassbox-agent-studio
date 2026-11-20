package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PrivateSkillEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 私有 Skill 草稿读取；所有按 ID 的读取都必须带当前归属。 */
public interface PrivateSkillRepository extends JpaRepository<PrivateSkillEntity, String> {
  List<PrivateSkillEntity> findAllByOwnerScopeKeyOrderByUpdatedAtDesc(String ownerScopeKey);

  Optional<PrivateSkillEntity> findByIdAndOwnerScopeKey(String id, String ownerScopeKey);
}
