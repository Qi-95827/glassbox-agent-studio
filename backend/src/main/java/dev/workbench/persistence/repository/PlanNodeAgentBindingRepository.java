package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.PlanNodeAgentBindingEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 计划节点 Agent 绑定读取；节点标识全局唯一。 */
public interface PlanNodeAgentBindingRepository
    extends JpaRepository<PlanNodeAgentBindingEntity, String> {
  List<PlanNodeAgentBindingEntity> findByRunIdOrderByNodeId(String runId);
}
