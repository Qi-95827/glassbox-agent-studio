package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PrivateAgentVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 私有 Agent 版本读取；旧运行按固定版本和组件快照查询。 */
public interface PrivateAgentVersionRepository
    extends JpaRepository<PrivateAgentVersionEntity, PrivateAgentVersionEntity.Key> {
  List<PrivateAgentVersionEntity> findByAgentIdOrderByVersionDesc(String agentId);

  Optional<PrivateAgentVersionEntity> findByAgentIdAndVersion(String agentId, int version);
}
