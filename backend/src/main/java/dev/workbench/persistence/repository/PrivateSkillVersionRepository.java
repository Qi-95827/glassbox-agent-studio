package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PrivateSkillVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 私有 Skill 版本读取；版本号只增不改。 */
public interface PrivateSkillVersionRepository
    extends JpaRepository<PrivateSkillVersionEntity, PrivateSkillVersionEntity.Key> {
  List<PrivateSkillVersionEntity> findBySkillIdOrderByVersionDesc(String skillId);

  Optional<PrivateSkillVersionEntity> findBySkillIdAndVersion(String skillId, int version);
}
