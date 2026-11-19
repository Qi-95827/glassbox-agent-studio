package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvaluationSuiteVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 不可变套件版本；只新增和读取，不覆盖历史定义。 */
public interface EvaluationSuiteVersionRepository
    extends JpaRepository<EvaluationSuiteVersionEntity, String> {
  List<EvaluationSuiteVersionEntity> findAllByOrderBySuiteAscVersionDesc();

  Optional<EvaluationSuiteVersionEntity> findFirstBySuiteOrderByVersionDesc(String suite);

  Optional<EvaluationSuiteVersionEntity> findFirstBySuiteAndVersion(String suite, int version);
}
