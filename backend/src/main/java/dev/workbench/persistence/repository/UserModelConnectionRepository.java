package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.UserModelConnectionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 按登录用户隔离的模型连接仓储；所有按 ID 查询都要求同时匹配归属。 */
public interface UserModelConnectionRepository
    extends JpaRepository<UserModelConnectionEntity, String> {
  List<UserModelConnectionEntity> findByOwnerUserIdOrderByCreatedAtAsc(String ownerUserId);

  Optional<UserModelConnectionEntity> findByIdAndOwnerUserId(String id, String ownerUserId);

  List<UserModelConnectionEntity> findByOwnerUserIdAndDefaultFlagTrue(String ownerUserId);

  Optional<UserModelConnectionEntity>
      findFirstByOwnerUserIdAndDefaultFlagTrueAndEnabledTrueOrderByCreatedAtAsc(String ownerUserId);

  boolean existsByOwnerUserId(String ownerUserId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update UserModelConnectionEntity c set c.defaultFlag = false, c.updatedAt = :updatedAt"
          + " where c.ownerUserId = :ownerUserId and c.defaultFlag = true")
  int clearDefault(
      @Param("ownerUserId") String ownerUserId, @Param("updatedAt") String updatedAt);
}
