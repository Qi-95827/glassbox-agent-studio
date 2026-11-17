package dev.workbench.memory;

import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 本地用户管理已确认项目背景；版本用于防止并发覆盖。 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/memories")
public class MemoryController {
  private final MemoryService memories;

  public record Change(MemoryCategory category, String content, Integer version) {}

  public record Deleted(boolean deleted) {}

  public MemoryController(MemoryService memories) {
    this.memories = memories;
  }

  @GetMapping
  public List<MemoryRecord> list(@PathVariable String workspaceId) {
    return memories.list(workspaceId);
  }

  @GetMapping("/search")
  public List<MemoryRecord> search(
      @PathVariable String workspaceId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) MemoryCategory category,
      @RequestParam(defaultValue = "20") int limit) {
    return memories.search(workspaceId, q, category, limit);
  }

  /** 历史正文不应被浏览器或中间缓存长期保存。 */
  @GetMapping("/{id}/history")
  public org.springframework.http.ResponseEntity<MemoryHistoryPage> history(
      @PathVariable String workspaceId,
      @PathVariable String id,
      @RequestParam(required = false) Integer beforeVersion,
      @RequestParam(defaultValue = "20") int limit) {
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(memories.history(workspaceId, id, beforeVersion, limit));
  }

  @PostMapping
  public MemoryRecord create(@PathVariable String workspaceId, @RequestBody Change request) {
    return memories.create(workspaceId, request.category(), request.content());
  }

  @PutMapping("/{id}")
  public MemoryRecord update(
      @PathVariable String workspaceId, @PathVariable String id, @RequestBody Change request) {
    if (request.version() == null || request.version() < 1)
      throw new IllegalArgumentException("请提供有效记忆版本。");
    return memories.update(
        workspaceId, id, request.version(), request.category(), request.content());
  }

  @DeleteMapping("/{id}")
  public Deleted delete(
      @PathVariable String workspaceId, @PathVariable String id, @RequestParam int version) {
    if (version < 1) throw new IllegalArgumentException("请提供有效记忆版本。");
    memories.delete(workspaceId, id, version);
    return new Deleted(true);
  }
}
