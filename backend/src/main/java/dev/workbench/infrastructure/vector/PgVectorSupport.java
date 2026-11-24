package dev.workbench.infrastructure.vector;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * pgvector 写入与召回的可选参数辅助方法。
 *
 * <p>向量列在基线脚本中固定为 {@code vector(1536)}，而 Embedding 端点允许 768/1024/3072 维模型：
 * 直接写入不匹配维度会让整笔入库事务回滚，因此这里提供「列声明维度」的只读探测，供调用方在写之前 安全跳过向量列、保留 {@code embedding_json}。探测失败一律返回
 * 0（表示未知），由调用方按可用处理。
 */
final class PgVectorSupport {
  private static final Pattern DIMENSION = Pattern.compile("\\((\\d+)\\)");
  private static final int MIN_EF_SEARCH = 1;
  private static final int MAX_EF_SEARCH = 1_000;

  private PgVectorSupport() {}

  /**
   * 读取向量列的声明维度。
   *
   * @return 声明维度；列不存在、不是 vector 或查询失败时返回 0
   */
  static int declaredDimension(JdbcTemplate jdbc, String table, String column) {
    if (jdbc == null || table == null || column == null) return 0;
    try {
      String declared =
          jdbc.queryForObject(
              "SELECT format_type(atttypid, atttypmod) FROM pg_attribute"
                  + " WHERE attrelid = to_regclass(?) AND attname = ? AND NOT attisdropped",
              String.class,
              table,
              column);
      if (declared == null) return 0;
      Matcher matcher = DIMENSION.matcher(declared);
      return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /** 把配置的 {@code hnsw.ef_search} 收敛到合法区间，避免 0 或超大数据集参数。 */
  static int effectiveEfSearch(int configured) {
    return Math.max(MIN_EF_SEARCH, Math.min(MAX_EF_SEARCH, configured));
  }

  /** 在已开启的事务中设置本次查询的 HNSW 候选池；失败不影响主查询。 */
  static void applyEfSearch(JdbcTemplate jdbc, int efSearch) {
    try {
      jdbc.execute("SET LOCAL hnsw.ef_search = " + effectiveEfSearch(efSearch));
    } catch (RuntimeException e) {
      // 老版本 pgvector 或只读代理可能拒绝该参数，保持默认候选池继续查询。
    }
  }
}
