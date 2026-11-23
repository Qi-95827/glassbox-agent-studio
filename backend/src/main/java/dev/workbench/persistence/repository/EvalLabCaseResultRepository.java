package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvalLabCaseResultEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 逐题结果仓储；按类别与重复序号稳定排序。 */
public interface EvalLabCaseResultRepository
    extends JpaRepository<EvalLabCaseResultEntity, String> {
  List<EvalLabCaseResultEntity> findByEvalRunIdOrderByCategoryAscSampleIdAscRepeatIndexAsc(
      String evalRunId);

  long countByEvalRunId(String evalRunId);
}
