package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PrivateToolVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 私有工具版本读取；版本固定执行器与文件扩展名约束。 */
public interface PrivateToolVersionRepository
    extends JpaRepository<PrivateToolVersionEntity, PrivateToolVersionEntity.Key> {
  List<PrivateToolVersionEntity> findByToolIdOrderByVersionDesc(String toolId);

  Optional<PrivateToolVersionEntity> findByToolIdAndVersion(String toolId, int version);
}
