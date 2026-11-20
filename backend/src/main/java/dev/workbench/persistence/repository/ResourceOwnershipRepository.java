package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.ResourceOwnershipEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 资源归属读取；主键由资源类型与资源标识组成。 */
public interface ResourceOwnershipRepository
    extends JpaRepository<ResourceOwnershipEntity, String> {
  Optional<ResourceOwnershipEntity> findByResourceTypeAndResourceId(
      String resourceType, String resourceId);

  default Optional<String> findOwner(String resourceType, String resourceId) {
    return findByResourceTypeAndResourceId(resourceType, resourceId)
        .map(ResourceOwnershipEntity::getOwnerScopeKey);
  }

  java.util.List<ResourceOwnershipEntity> findByResourceType(String resourceType);
}
