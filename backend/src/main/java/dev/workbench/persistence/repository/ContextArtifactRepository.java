package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ContextArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** L1 卸载产物：按受控 artifactRef 回查完整正文与大纲。 */
public interface ContextArtifactRepository extends JpaRepository<ContextArtifactEntity, String> {}
