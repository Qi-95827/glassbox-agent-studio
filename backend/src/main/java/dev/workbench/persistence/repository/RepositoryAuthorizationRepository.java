package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.RepositoryAuthorizationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 仓库导入授权读取；按账号与规范化根目录唯一。 */
public interface RepositoryAuthorizationRepository
    extends JpaRepository<RepositoryAuthorizationEntity, String> {
  List<RepositoryAuthorizationEntity> findByOwnerScopeKeyOrderByCreatedAtDesc(String ownerScopeKey);

  Optional<RepositoryAuthorizationEntity> findByOwnerScopeKeyAndRepositoryKey(
      String ownerScopeKey, String repositoryKey);

  List<RepositoryAuthorizationEntity> findAllByOrderByCreatedAtDesc();
}
