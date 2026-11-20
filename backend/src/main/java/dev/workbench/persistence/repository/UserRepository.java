package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.UserEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 本地账号读取；用户名唯一约束由数据库保证。 */
public interface UserRepository extends JpaRepository<UserEntity, String> {
  Optional<UserEntity> findByUsername(String username);

  List<UserEntity> findAllByOrderByCreatedAtAsc();

  boolean existsByUsername(String username);

  default boolean anyUser() {
    return count() > 0;
  }
}
