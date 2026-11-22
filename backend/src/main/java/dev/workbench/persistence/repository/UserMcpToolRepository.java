package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.UserMcpToolEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMcpToolRepository extends JpaRepository<UserMcpToolEntity, String> {
  List<UserMcpToolEntity> findByServerIdOrderByNameAsc(String serverId);

  List<UserMcpToolEntity> findByServerIdAndEnabledTrueOrderByNameAsc(String serverId);

  void deleteByServerId(String serverId);
}
