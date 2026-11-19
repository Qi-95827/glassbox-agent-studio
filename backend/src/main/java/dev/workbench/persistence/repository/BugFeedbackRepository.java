package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.BugFeedbackEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户显式提交的 Bug 反馈历史。 */
public interface BugFeedbackRepository extends JpaRepository<BugFeedbackEntity, String> {
  List<BugFeedbackEntity> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
