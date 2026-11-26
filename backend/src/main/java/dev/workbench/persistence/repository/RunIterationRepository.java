package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.RunIterationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 同任务迭代请求的只读查询与唯一写入；一轮运行只有一条记录。 */
public interface RunIterationRepository extends JpaRepository<RunIterationEntity, String> {
  Optional<RunIterationEntity> findByRunId(String runId);

  List<RunIterationEntity> findByTaskIdOrderByRoundIndexAsc(String taskId);

  Optional<RunIterationEntity> findFirstByTaskIdOrderByRoundIndexDesc(String taskId);
}
