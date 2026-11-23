package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EvalBenchmarkPackEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Benchmark Hub 下载包状态仓储。 */
public interface EvalBenchmarkPackRepository extends JpaRepository<EvalBenchmarkPackEntity, String> {
  Optional<EvalBenchmarkPackEntity> findByPackId(String packId);

  List<EvalBenchmarkPackEntity> findAllByOrderByPackIdAsc();
}
