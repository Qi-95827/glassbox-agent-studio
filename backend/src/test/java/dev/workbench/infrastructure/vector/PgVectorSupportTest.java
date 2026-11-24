package dev.workbench.infrastructure.vector;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** pgvector 可选参数辅助：列声明维度探测与 HNSW 候选池收敛。 */
class PgVectorSupportTest {
  @Test
  void parsesDeclaredDimensionFromColumnType() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(
            anyString(), eq(String.class), eq("knowledge_chunks"), eq("embedding_vec")))
        .thenReturn("vector(1536)");

    assertEquals(
        1536, PgVectorSupport.declaredDimension(jdbc, "knowledge_chunks", "embedding_vec"));
  }

  @Test
  void unknownOrInvalidColumnTypeMeansUnknownDimension() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(String.class), eq("t"), eq("c"))).thenReturn("text");
    assertEquals(0, PgVectorSupport.declaredDimension(jdbc, "t", "c"));

    when(jdbc.queryForObject(anyString(), eq(String.class), eq("t2"), eq("c2")))
        .thenThrow(new IllegalStateException("column missing"));
    assertEquals(0, PgVectorSupport.declaredDimension(jdbc, "t2", "c2"));
    assertEquals(0, PgVectorSupport.declaredDimension(null, "t", "c"));
  }

  @Test
  void efSearchIsClampedToSafeRange() {
    assertEquals(1, PgVectorSupport.effectiveEfSearch(0));
    assertEquals(1, PgVectorSupport.effectiveEfSearch(-20));
    assertEquals(64, PgVectorSupport.effectiveEfSearch(64));
    assertEquals(1_000, PgVectorSupport.effectiveEfSearch(5_000));
  }

  @Test
  void efSearchFailureNeverBlocksTheQuery() {
    var jdbc = mock(JdbcTemplate.class);
    doThrow(new IllegalStateException("hnsw not available"))
        .when(jdbc)
        .execute("SET LOCAL hnsw.ef_search = 64");

    assertDoesNotThrow(() -> PgVectorSupport.applyEfSearch(jdbc, 64));
  }
}
