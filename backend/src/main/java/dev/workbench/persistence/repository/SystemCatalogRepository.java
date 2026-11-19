package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 环境自检使用的连通性和系统目录查询。
 *
 * <p>这里保留原生 SQL 是必要的：{@code SELECT 1} 不依赖任何业务实体，用于验证数据库连接；
 * {@code information_schema.tables} 是数据库元数据，JPQL 没有可移植表达。当前方言为 H2，启动和测试都在 H2
 * 上验证；更换数据库时需要同步调整表结构查询。
 */
@Repository
@Transactional(readOnly = true)
public class SystemCatalogRepository {
  private final EntityManager sessions;

  public SystemCatalogRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  /** H2 连通性探测；不读取业务表，也不暴露连接信息。 */
  public Integer ping() {
    Number value = (Number) sessions.createNativeQuery("SELECT 1").getSingleResult();
    return value == null ? null : value.intValue();
  }

  /** H2 系统目录：PUBLIC schema 下的表数量，用于判断结构迁移是否执行。 */
  public Integer countPublicTables() {
    Number value =
        (Number)
            sessions
                .createNativeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema='PUBLIC'")
                .getSingleResult();
    return value == null ? null : value.intValue();
  }
}
