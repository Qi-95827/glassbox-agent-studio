package dev.workbench.persistence.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 环境自检使用的连通性和系统目录查询。
 *
 * <p>这里保留原生 SQL 是必要的：{@code SELECT 1} 不依赖任何业务实体，用于验证数据库连接； {@code information_schema.tables}
 * 是数据库元数据，JPQL 没有可移植表达。
 *
 * <p>schema 名大小写因数据库而异：H2 存大写 {@code PUBLIC}，PostgreSQL 存小写 {@code public}。 因此统一用 {@code
 * LOWER(table_schema)} 比较，避免 PostgreSQL 下自检恒为 0 表而误报失败。
 */
@Repository
@Transactional(readOnly = true)
public class SystemCatalogRepository {
  private final EntityManager sessions;

  public SystemCatalogRepository(EntityManager sessions) {
    this.sessions = sessions;
  }

  /** 连通性探测；不读取业务表，也不暴露连接信息。 */
  public Integer ping() {
    Number value = (Number) sessions.createNativeQuery("SELECT 1").getSingleResult();
    return value == null ? null : value.intValue();
  }

  /** 当前库 public schema 下的表数量，用于判断结构迁移是否执行；H2 与 PostgreSQL 均适用。 */
  public Integer countPublicTables() {
    Number value =
        (Number)
            sessions
                .createNativeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE LOWER(table_schema) = 'public'")
                .getSingleResult();
    return value == null ? null : value.intValue();
  }
}
