package dev.workbench.infrastructure.vector;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * pgvector 索引在 PostgreSQL 方言下的异常安全降级。
 *
 * <p>反例对应 F-02：维度不一致或列/扩展未就绪时，写入异常会回滚外层文档入库与记忆回填事务。 这里用“声明为 PostgreSQL
 * 但任何语句都失败”的数据源模拟该场景，要求写入与召回都只降级、不抛出。
 */
class JdbcVectorIndexDegradationTest {
  private DataSource failingPostgresDataSource() throws Exception {
    var dataSource = mock(DataSource.class);
    var connection = mock(Connection.class);
    var metaData = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
    // 连接上的任何 PreparedStatement 请求都失败，模拟列维度不匹配/扩展缺失。
    when(connection.prepareStatement(any())).thenThrow(new java.sql.SQLException("simulated"));
    return dataSource;
  }

  @Test
  void knowledgeIndexStoreAndNearestDegradeWithoutThrowing() throws Exception {
    var index = new JdbcKnowledgeVectorIndex(failingPostgresDataSource(), 64);

    assertTrue(index.available());
    assertDoesNotThrow(
        () -> index.store("ws-1", "chunk-1", "embed-1536", new float[] {0.1f, 0.2f, 0.3f}));
    assertEquals(List.of(), index.nearest("ws-1", "embed-1536", new float[] {0.1f}, 5));
  }

  @Test
  void memoryIndexStoreAndNearestDegradeWithoutThrowing() throws Exception {
    var index = new JdbcMemoryVectorIndex(failingPostgresDataSource(), 64);

    assertTrue(index.available());
    assertDoesNotThrow(
        () -> index.store("ws-1", "memory-1", "embed-768", new float[] {0.1f, 0.2f}));
    assertEquals(List.of(), index.nearest("ws-1", "embed-768", new float[] {0.1f}, 5));
  }

  @Test
  void h2DatasourceKeepsIndexDisabled() throws Exception {
    var dataSource = mock(DataSource.class);
    var connection = mock(Connection.class);
    var metaData = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("H2");

    var knowledge = new JdbcKnowledgeVectorIndex(dataSource, 64);
    var memory = new JdbcMemoryVectorIndex(dataSource, 64);

    assertFalse(knowledge.available());
    assertFalse(memory.available());
    knowledge.store("ws", "chunk", "m", new float[] {0.1f});
    memory.store("ws", "memory", "m", new float[] {0.1f});
    verify(connection, never()).prepareStatement(any());
  }
}
