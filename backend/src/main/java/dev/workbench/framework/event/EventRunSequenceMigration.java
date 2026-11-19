package dev.workbench.framework.event;

import dev.workbench.persistence.entity.EventEntity;
import dev.workbench.persistence.entity.SchemaMigrationEntity;
import dev.workbench.persistence.repository.EventRepository;
import dev.workbench.persistence.repository.SchemaMigrationRepository;
import java.util.LinkedHashMap;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 为历史事件回填每运行提交序号。
 *
 * <p>新增事件由 {@link dev.workbench.task.RunRecordService} 在写入事务内分配序号；本迁移只执行一次，
 * 把既有事件按全局 ID 顺序编号（历史行早已全部提交，ID 顺序即提交顺序），使旧数据也能使用 {@code
 * run_seq} 游标读取。回填与迁移标记在同一事务提交，重复启动由标记和事务内复查共同保证幂等。
 */
@Component
public class EventRunSequenceMigration implements ApplicationRunner {
  static final String MIGRATION_ID = "event_run_seq_v1";

  private final EventRepository events;
  private final SchemaMigrationRepository migrations;
  private final TransactionTemplate tx;

  public EventRunSequenceMigration(
      EventRepository events,
      SchemaMigrationRepository migrations,
      TransactionTemplate tx) {
    this.events = events;
    this.migrations = migrations;
    this.tx = tx;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (done()) return;
    tx.executeWithoutResult(
        status -> {
          if (done()) return;
          var counters = new LinkedHashMap<String, Long>();
          for (EventEntity event : events.findUnsequenced()) {
            long sequence = counters.merge(event.getRunId(), 1L, Long::sum);
            events.assignRunSeq(event.getId(), sequence);
          }
          migrations.saveAndFlush(new SchemaMigrationEntity(MIGRATION_ID));
        });
  }

  private boolean done() {
    return migrations.existsById(MIGRATION_ID);
  }
}
