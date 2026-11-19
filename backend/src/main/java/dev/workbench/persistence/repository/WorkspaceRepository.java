package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.WorkspaceEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 本地工作区读取。创建使用实体的新行语义，主键冲突由数据库拒绝。 */
public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, String> {
  List<WorkspaceEntity> findAllByOrderByCreatedAtDesc();
}
