package dev.workbench.framework.event;

import java.util.LinkedHashMap;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 为历史事件回填每运行提交序号。
 *
 * <p>新增事件由 {@link dev.workbench.task.RunRepository} 在写入事务内分配序号；本迁移只执行一次，
 * 把既有事件按全局 ID 顺序编号（历史行早已全部提交，ID 顺序即提交顺序），使旧数据也能使用 {@code
 * run_seq} 游标读取。
 */
@Component
public class EventRunSequenceMigration implements ApplicationRunner {
  static final String MIGRATION_ID = "event_run_seq_v1";

  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public EventRunSequenceMigration(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (done()) return;
    tx.executeWithoutResult(
        status -> {
          if (done()) return;
          var rows =
              db.query(
                  "SELECT id,run_id FROM events WHERE run_seq IS NULL ORDER BY run_id,id",
                  (row, index) ->
                      new String[] {row.getString("id"), row.getString("run_id")});
          var counters = new LinkedHashMap<String, Long>();
          for (var row : rows) {
            long sequence = counters.merge(row[1], 1L, Long::sum);
            db.update(
                "UPDATE events SET run_seq=? WHERE id=? AND run_seq IS NULL", sequence, row[0]);
          }
          db.update(
              "INSERT INTO workbench_schema_migrations(migration_id) VALUES(?)", MIGRATION_ID);
        });
  }

  private boolean done() {
    Integer count =
        db.queryForObject(
            "SELECT COUNT(*) FROM workbench_schema_migrations WHERE migration_id=?",
            Integer.class,
            MIGRATION_ID);
    return count != null && count > 0;
  }
}
