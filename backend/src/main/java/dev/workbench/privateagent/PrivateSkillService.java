package dev.workbench.privateagent;

import static dev.workbench.privateagent.PrivateComponentSupport.blankToNull;
import static dev.workbench.privateagent.PrivateComponentSupport.optional;
import static dev.workbench.privateagent.PrivateComponentSupport.requireEditable;
import static dev.workbench.privateagent.PrivateComponentSupport.sha256;
import static dev.workbench.privateagent.PrivateComponentSupport.text;

import dev.workbench.domain.ExecutionScopeResolver;
import dev.workbench.domain.WorkbenchIds;
import dev.workbench.domain.WorkbenchTime;
import dev.workbench.persistence.entity.PrivateSkillEntity;
import dev.workbench.persistence.entity.PrivateSkillVersionEntity;
import dev.workbench.persistence.repository.PrivateSkillRepository;
import dev.workbench.persistence.repository.PrivateSkillVersionRepository;
import dev.workbench.privateagent.PrivateComponentModels.ComponentRef;
import dev.workbench.privateagent.PrivateComponentModels.SkillRequest;
import dev.workbench.privateagent.PrivateComponentModels.SkillView;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 私有 Skill 的草稿、发布版本与归档；所有读写都带当前会话归属。 */
@Service
public class PrivateSkillService {
  private final PrivateSkillRepository skills;
  private final PrivateSkillVersionRepository skillVersions;
  private final ExecutionScopeResolver scopes;

  public PrivateSkillService(
      PrivateSkillRepository skills,
      PrivateSkillVersionRepository skillVersions,
      ExecutionScopeResolver scopes) {
    this.skills = skills;
    this.skillVersions = skillVersions;
    this.scopes = scopes;
  }

  public List<SkillView> skills() {
    return skills.findAllByOwnerScopeKeyOrderByUpdatedAtDesc(scope()).stream()
        .map(this::skillView)
        .toList();
  }

  @Transactional
  public SkillView createSkill(SkillRequest request) {
    validateSkill(request);
    var row =
        new PrivateSkillEntity(
            WorkbenchIds.newId(),
            scope(),
            request.name().strip(),
            WorkbenchTime.now());
    apply(row, request);
    return skillView(skills.saveAndFlush(row));
  }

  @Transactional
  public SkillView updateSkill(String id, SkillRequest request) {
    var row = ownSkill(id);
    requireEditable(row.getStatus());
    validateSkill(request);
    apply(row, request);
    row.setUpdatedAt(WorkbenchTime.now());
    return skillView(skills.saveAndFlush(row));
  }

  @Transactional
  public SkillView publishSkill(String id) {
    var row = ownSkill(id);
    requireEditable(row.getStatus());
    int version = row.getCurrentVersion() + 1;
    String now = WorkbenchTime.now();
    skillVersions.saveAndFlush(
        new PrivateSkillVersionEntity(
            row.getId(),
            version,
            row.getName(),
            row.getSummary(),
            row.getUsageNotes(),
            row.getInputContract(),
            row.getOutputContract(),
            row.getContentMd(),
            sha256(row.getContentMd()),
            now));
    row.setCurrentVersion(version);
    row.setStatus("PUBLISHED");
    row.setUpdatedAt(now);
    return skillView(skills.saveAndFlush(row));
  }

  @Transactional
  public SkillView archiveSkill(String id) {
    var row = ownSkill(id);
    row.setStatus("ARCHIVED");
    row.setArchivedAt(WorkbenchTime.now());
    row.setUpdatedAt(row.getArchivedAt());
    return skillView(skills.saveAndFlush(row));
  }

  public List<SkillView> skillVersions(String id) {
    ownSkill(id);
    return skillVersions.findBySkillIdOrderByVersionDesc(id).stream()
        .map(
            row ->
                new SkillView(
                    row.getSkillId(),
                    row.getName(),
                    row.getSummary(),
                    row.getUsageNotes(),
                    row.getInputContract(),
                    row.getOutputContract(),
                    row.getContentMd(),
                    "PUBLISHED",
                    row.getVersion(),
                    false,
                    null,
                    row.getCreatedAt()))
        .toList();
  }

  /** Agent 发布时固定引用版本；归属或发布状态不符即拒绝。 */
  PrivateSkillVersionEntity resolveVersion(ComponentRef ref) {
    if (ref == null || ref.id() == null) throw new IllegalArgumentException("Skill 引用为空。");
    var skill =
        skills
            .findByIdAndOwnerScopeKey(ref.id(), scope())
            .orElseThrow(() -> new IllegalArgumentException("Skill 不存在或无权访问：" + ref.id()));
    if (!"PUBLISHED".equals(skill.getStatus()))
      throw new IllegalArgumentException("Skill 未发布：" + skill.getName());
    int version = ref.version() == null ? skill.getCurrentVersion() : ref.version();
    return skillVersions
        .findBySkillIdAndVersion(ref.id(), version)
        .orElseThrow(
            () -> new IllegalArgumentException("Skill 版本不存在：" + ref.id() + " v" + version));
  }

  private SkillView skillView(PrivateSkillEntity row) {
    boolean changed =
        row.getCurrentVersion() < 1
            || !sha256(row.getContentMd())
                .equals(
                    skillVersions
                        .findBySkillIdAndVersion(row.getId(), row.getCurrentVersion())
                        .map(PrivateSkillVersionEntity::getContentSha256)
                        .orElse(""));
    return new SkillView(
        row.getId(),
        row.getName(),
        row.getSummary(),
        row.getUsageNotes(),
        row.getInputContract(),
        row.getOutputContract(),
        row.getContentMd(),
        row.getStatus(),
        row.getCurrentVersion(),
        changed,
        row.getArchivedAt(),
        row.getUpdatedAt());
  }

  private void apply(PrivateSkillEntity row, SkillRequest request) {
    row.setName(request.name().strip());
    row.setSummary(request.summary().strip());
    row.setUsageNotes(blankToNull(request.usageNotes()));
    row.setInputContract(blankToNull(request.inputContract()));
    row.setOutputContract(blankToNull(request.outputContract()));
    row.setContentMd(request.contentMd().strip());
    row.setDraftSha256(sha256(request.contentMd().strip()));
    row.setUpdatedAt(WorkbenchTime.now());
  }

  private void validateSkill(SkillRequest request) {
    if (request == null) throw new IllegalArgumentException("Skill 内容不能为空。");
    text(request.name(), 120, "Skill 名称");
    text(request.summary(), 300, "用途");
    if (request.contentMd() == null
        || request.contentMd().isBlank()
        || request.contentMd().strip().length() > 20000)
      throw new IllegalArgumentException("Skill 正文须为 1 至 20000 字 Markdown。");
    optional(request.usageNotes(), 2000, "使用说明");
    optional(request.inputContract(), 1000, "输入约定");
    optional(request.outputContract(), 1000, "预期输出");
  }

  private PrivateSkillEntity ownSkill(String id) {
    return skills
        .findByIdAndOwnerScopeKey(id, scope())
        .orElseThrow(() -> new EmptyResultDataAccessException(1));
  }

  private String scope() {
    return scopes.current().ownerScopeKey();
  }
}
