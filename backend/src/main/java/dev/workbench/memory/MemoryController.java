package dev.workbench.memory;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import dev.workbench.domain.MemoryCategory;

/** 本地用户管理已确认项目背景；版本用于防止并发覆盖。 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/memories")
public class MemoryController {
  private final MemoryService memories;
  private final dev.workbench.ownership.OwnershipService ownership;
  private final dev.workbench.framework.skill.SkillDraftPort skillDrafts;

  public record Change(MemoryCategory category, String content, Integer version) {}

  public record Deleted(boolean deleted) {}

  public record PromoteRequest(String name) {}

  public MemoryController(
      MemoryService memories,
      dev.workbench.ownership.OwnershipService ownership,
      dev.workbench.framework.skill.SkillDraftPort skillDrafts) {
    this.memories = memories;
    this.ownership = ownership;
    this.skillDrafts = skillDrafts;
  }

  /** 所有记忆读写都先核对工作区归属。 */
  @ModelAttribute
  void requireWorkspace(@PathVariable String workspaceId) {
    ownership.requireWorkspace(workspaceId);
  }

  @GetMapping
  public List<MemoryRecord> list(
      @PathVariable String workspaceId,
      @RequestParam(required = false) dev.workbench.domain.MemoryStatus status,
      @RequestParam(required = false) dev.workbench.framework.memory.MemoryType type) {
    return memories.list(workspaceId, status, type);
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

  @PostMapping("/{id}/accept")
  public MemoryRecord accept(
      @PathVariable String workspaceId, @PathVariable String id, @RequestParam int version) {
    if (version < 1) throw new IllegalArgumentException("请提供有效记忆版本。");
    return memories.accept(workspaceId, id, version);
  }

  @PostMapping("/{id}/ignore")
  public MemoryRecord ignore(
      @PathVariable String workspaceId, @PathVariable String id, @RequestParam int version) {
    if (version < 1) throw new IllegalArgumentException("请提供有效记忆版本。");
    return memories.ignore(workspaceId, id, version);
  }

  /** 一键晋升为私有 Skill 草稿；只复制用户确认/采纳的经验正文，不授予工具权限。 */
  @PostMapping("/{id}/promote")
  public dev.workbench.framework.skill.SkillDraftPort.SkillDraft promote(
      @PathVariable String workspaceId,
      @PathVariable String id,
      @RequestParam int version,
      @RequestBody(required = false) PromoteRequest request) {
    if (version < 1) throw new IllegalArgumentException("请提供有效记忆版本。");
    var record =
        memories.list(workspaceId, null, null).stream()
            .filter(item -> item.id().toString().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("记忆不存在。"));
    if (record.version() != version)
      throw new IllegalStateException("记忆版本已变化，请刷新后重试。");
    String name =
        request == null || request.name() == null || request.name().isBlank()
            ? "经验卡：" + record.content().substring(0, Math.min(20, record.content().length()))
            : request.name().strip();
    return skillDrafts.createDraft(name, record.content());
  }

  @DeleteMapping("/{id}")
  public Deleted delete(
      @PathVariable String workspaceId, @PathVariable String id, @RequestParam int version) {
    if (version < 1) throw new IllegalArgumentException("请提供有效记忆版本。");
    memories.delete(workspaceId, id, version);
    return new Deleted(true);
  }
}
