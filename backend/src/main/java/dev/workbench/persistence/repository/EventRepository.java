package dev.workbench.persistence.repository;

import dev.workbench.persistence.entity.EventEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 事件查询；游标按 run_seq 稳定排序，历史列表按事件 ID 排序。 */
public interface EventRepository extends JpaRepository<EventEntity, Long> {
  List<EventEntity> findByRunIdOrderByIdAsc(String runId);

  List<EventEntity> findByRunIdAndRunSeqGreaterThanOrderByRunSeqAsc(
      String runId, long after, Pageable pageable);

  List<EventEntity> findByRunIdAndNodeIdAndRunSeqIsNotNullOrderByRunSeqAsc(
      String runId, String nodeId);

  Optional<EventEntity> findFirstByRunIdAndEventTypeInOrderByIdDesc(
      String runId, Collection<String> eventTypes);

  boolean existsByIdAndRunIdAndEventType(Long id, String runId, String eventType);

  @Query("select coalesce(max(e.runSeq), 0) from EventEntity e where e.runId = :runId")
  long maxRunSeq(@Param("runId") String runId);

  @Query("select e from EventEntity e where e.runSeq is null order by e.runId asc, e.id asc")
  List<EventEntity> findUnsequenced();

  @Modifying
  @Query("update EventEntity e set e.runSeq = :runSeq where e.id = :id and e.runSeq is null")
  int assignRunSeq(@Param("id") Long id, @Param("runSeq") long runSeq);

  @Query("select max(e.id) from EventEntity e where e.runId = :runId")
  Long maxId(@Param("runId") String runId);

  @Query(
      "select e.id from EventEntity e where e.runId = :runId and e.eventType = :eventType"
          + " order by e.id asc")
  List<Long> findIdsByRunIdAndEventType(
      @Param("runId") String runId, @Param("eventType") String eventType);

  default List<EventEntity> findByRunId(String runId) {
    return findByRunIdOrderByIdAsc(runId);
  }

  default List<EventEntity> findAfter(String runId, long after, int limit) {
    return findByRunIdAndRunSeqGreaterThanOrderByRunSeqAsc(
        runId, after, org.springframework.data.domain.PageRequest.of(0, limit));
  }

  default List<EventEntity> findByNode(String runId, String nodeId) {
    return findByRunIdAndNodeIdAndRunSeqIsNotNullOrderByRunSeqAsc(runId, nodeId);
  }

  default java.util.Optional<dev.workbench.domain.EventType> findLatestType(
      String runId, java.util.Collection<dev.workbench.domain.EventType> types) {
    return findFirstByRunIdAndEventTypeInOrderByIdDesc(
            runId, types.stream().map(Enum::name).toList())
        .map(event -> dev.workbench.domain.EventType.valueOf(event.getEventType()));
  }

  default boolean exists(long id, String runId, dev.workbench.domain.EventType type) {
    return existsByIdAndRunIdAndEventType(id, runId, type.name());
  }

  default List<Long> findIdsByType(String runId, dev.workbench.domain.EventType type) {
    return findIdsByRunIdAndEventType(runId, type.name());
  }
}