package dev.workbench.workspace;

import dev.workbench.audit.AuditService;
import dev.workbench.persistence.entity.GitActionEntity;
import dev.workbench.persistence.entity.GitDeliveryRequestEntity;
import dev.workbench.persistence.repository.GitActionRepository;
import dev.workbench.persistence.repository.GitDeliveryRequestRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/**
 * 用户明确发起的推送与合并。
 *
 * <p>准备阶段绑定当前 HEAD、源分支、远端和目标分支；批准后执行前再次核对。冲突转为 CONFLICT，网络或认证失败转为 UNKNOWN，必须人工核对，不自动重试。
 */
@Service
public class GitDeliveryService {
  private final GitDeliveryRequestRepository requests;
  private final GitActionRepository actions;
  private final WorkspaceService workspaces;
  private final AuditService audit;
  /** 测试注入点：模拟校验完成后、外部推送前远端配置被改写。 */
  Runnable beforePush = () -> {};

  public GitDeliveryService(
      GitDeliveryRequestRepository requests,
      GitActionRepository actions,
      WorkspaceService workspaces,
      AuditService audit) {
    this.requests = requests;
    this.actions = actions;
    this.workspaces = workspaces;
    this.audit = audit;
  }

  public synchronized DeliveryRequest prepare(
      String workspaceId, String kindText, String remote, String target, String note) {
    GitAction.Kind kind = parseKind(kindText);
    String normalizedRemote =
        remote == null || remote.isBlank() ? "local" : remote.strip();
    if (kind == GitAction.Kind.PUSH
        && (remote == null || !remote.matches("[A-Za-z0-9._-]{1,80}") || remote.startsWith("-")))
      throw new IllegalArgumentException("远端名称无效。");
    WorkspaceService.Workspace workspace = workspaces.get(workspaceId);
    workspaces.requireBranchName(Path.of(workspace.repositoryPath()), target);
    String head = workspaces.head(workspaceId);
    String branch = workspaces.currentBranch(workspaceId);
    if ("HEAD".equals(branch)) throw new IllegalStateException("分离 HEAD 状态不能发起交付。");
    String remoteUrlSha256 =
        kind == GitAction.Kind.PUSH
            ? workspaces.remotePushUrlFingerprint(workspaceId, normalizedRemote)
            : null;
    String targetCommit =
        kind == GitAction.Kind.MERGE ? workspaces.branchCommit(workspaceId, target) : null;
    String id = dev.workbench.domain.WorkbenchIds.newId(), now = dev.workbench.domain.WorkbenchTime.now();
    requests.save(
        new GitDeliveryRequestEntity(
            id,
            workspaceId,
            kind.name(),
            normalizedRemote,
            target,
            targetCommit,
            head,
            branch,
            remoteUrlSha256,
            DeliveryStatus.PENDING.name(),
            note == null ? null : note.strip().substring(0, Math.min(500, note.strip().length())),
            null,
            now,
            now));
    audit.record(
        "GIT_DELIVERY_PREPARE",
        "WORKSPACE",
        workspaceId,
        "PENDING",
        kind.name() + " " + remote + ":" + target + " @ " + head);
    return get(id);
  }

  public synchronized DeliveryRequest decide(String id, boolean approve) {
    DeliveryRequest request = get(id);
    if (!DeliveryStatus.PENDING.name().equals(request.status()))
      throw new IllegalStateException("该交付请求已处理，请重新发起。");
    if (approve) validate(request);
    requests.updateStatus(
        id,
        approve ? DeliveryStatus.APPROVED.name() : DeliveryStatus.REJECTED.name(),
        dev.workbench.domain.WorkbenchTime.now());
    audit.record(
        approve ? "GIT_DELIVERY_APPROVE" : "GIT_DELIVERY_REJECT",
        "GIT_DELIVERY",
        id,
        approve ? "APPROVED" : "REJECTED",
        request.kind() + " " + request.remote() + ":" + request.target());
    return get(id);
  }

  public synchronized DeliveryRequest execute(String id) {
    DeliveryRequest request = get(id);
    if (!DeliveryStatus.APPROVED.name().equals(request.status()))
      throw new IllegalStateException("只有已批准的交付请求可以执行。");
    Validated validated = validate(request);
    requests.updateStatus(id, DeliveryStatus.STARTED.name(), dev.workbench.domain.WorkbenchTime.now());
    String actionId = dev.workbench.domain.WorkbenchIds.newId(), now = dev.workbench.domain.WorkbenchTime.now();
    actions.save(
        new GitActionEntity(
            actionId,
            request.workspaceId().toString(),
            request.kind(),
            GitActionStatus.STARTED.name(),
            id,
            request.headCommit(),
            request.remote() + ":" + request.target(),
            null,
            now,
            now));
    WorkspaceService.Workspace workspace = workspaces.get(request.workspaceId().toString());
    Path path = Path.of(workspace.workspacePath());
    WorkspaceService.GitResult result;
    DeliveryStatus status;
    String detail;
    String commitId = request.headCommit();
    if (GitAction.Kind.PUSH.name().equals(request.kind())) {
      beforePush.run();
      result =
          workspaces.runGitResult(
              path,
              "push",
              validated.remoteUrl(),
              request.headCommit() + ":refs/heads/" + request.target());
      String output = result.output().strip().replace(validated.remoteUrl(), request.remote());
      if (result.exitCode() == 0) {
        status = DeliveryStatus.CONFIRMED;
        detail =
            "已推送 "
                + request.headCommit()
                + " 到 "
                + request.remote()
                + "/"
                + request.target();
      } else if (output.contains("non-fast-forward")
          || output.contains("rejected")
          || output.contains("fetch first")) {
        status = DeliveryStatus.CONFLICT;
        detail = "远端拒绝或需要先同步，未确认远端状态：" + excerpt(output);
      } else {
        status = DeliveryStatus.UNKNOWN;
        detail = "推送结果无法确认，请检查远端：" + excerpt(output);
      }
    } else {
      String mergeTarget =
          request.targetCommit() == null ? request.target() : request.targetCommit();
      result = workspaces.runGitResult(path, "merge", "--no-ff", mergeTarget);
      String output = result.output().strip();
      if (result.exitCode() == 0) {
        status = DeliveryStatus.CONFIRMED;
        commitId = workspaces.head(request.workspaceId().toString());
        detail =
            "已合并 "
                + request.target()
                + " @ "
                + (request.targetCommit() == null ? "未记录" : request.targetCommit().substring(0, 8))
                + " 到 "
                + request.sourceBranch()
                + "，提交 "
                + commitId;
      } else if (output.contains("CONFLICT") || output.contains("Automatic merge failed")) {
        workspaces.runGitResult(path, "merge", "--abort");
        status = DeliveryStatus.CONFLICT;
        detail = "合并冲突，已中止合并，工作区保持原提交：" + excerpt(output);
      } else {
        workspaces.runGitResult(path, "merge", "--abort");
        status = DeliveryStatus.UNKNOWN;
        detail = "合并结果无法确认，请检查工作区：" + excerpt(output);
      }
    }
    requests.updateResult(id, status.name(), detail, dev.workbench.domain.WorkbenchTime.now());
    actions.updateOutcome(
        actionId,
        switch (status) {
          case CONFIRMED -> GitActionStatus.CONFIRMED.name();
          case CONFLICT -> GitActionStatus.CONFLICT.name();
          case UNKNOWN -> GitActionStatus.UNKNOWN.name();
          default -> GitActionStatus.UNKNOWN.name();
        },
        commitId,
        detail,
        dev.workbench.domain.WorkbenchTime.now());
    audit.record(
        "GIT_DELIVERY_EXECUTE",
        "GIT_DELIVERY",
        id,
        status.name(),
        request.kind() + " " + request.remote() + ":" + request.target());
    return get(id);
  }

  public List<DeliveryRequest> list(String workspaceId) {
    workspaces.get(workspaceId);
    return requests.findByWorkspaceIdOrderByCreatedAtDescIdDesc(workspaceId).stream()
        .map(GitDeliveryService::map)
        .toList();
  }

  public DeliveryRequest get(String id) {
    return map(requests.findById(id).orElseThrow(() -> new EmptyResultDataAccessException(1)));
  }

  private Validated validate(DeliveryRequest request) {
    String head = workspaces.head(request.workspaceId().toString());
    String branch = workspaces.currentBranch(request.workspaceId().toString());
    if (!head.equals(request.headCommit()) || !branch.equals(request.sourceBranch()))
      throw new IllegalStateException("当前 HEAD 或分支已变化，请重新发起交付请求。");
    String validatedRemote = null;
    if (GitAction.Kind.PUSH.name().equals(request.kind())) {
      try {
        validatedRemote =
            workspaces.remotePushUrl(request.workspaceId().toString(), request.remote());
      } catch (RuntimeException e) {
        throw new IllegalStateException("远端地址无法确认，原批准已失效，请重新发起交付请求。");
      }
      String currentRemote = hash(validatedRemote);
      if (request.remoteUrlSha256() == null
          || !java.security.MessageDigest.isEqual(
              currentRemote.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
              request.remoteUrlSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
        throw new IllegalStateException("远端推送地址已变化，原批准已失效，请重新发起交付请求。");
    }
    if (GitAction.Kind.MERGE.name().equals(request.kind())) {
      if (request.targetCommit() == null)
        throw new IllegalStateException("合并请求未绑定待合入提交，请重新发起。");
      String currentTarget =
          workspaces.branchCommit(request.workspaceId().toString(), request.target());
      if (!java.security.MessageDigest.isEqual(
          currentTarget.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          request.targetCommit().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
        throw new IllegalStateException("待合入分支已变化，原批准已失效，请重新发起合并请求。");
    }
    var status = workspaces.status(request.workspaceId().toString());
    if (!status.changes().isBlank())
      throw new IllegalStateException("工作区存在未提交变化，请先完成本地提交。");
    return new Validated(validatedRemote);
  }

  private static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法计算远端地址摘要。", e);
    }
  }

  private record Validated(String remoteUrl) {}

  private static GitAction.Kind parseKind(String value) {
    if (value == null) throw new IllegalArgumentException("请选择推送或合并。");
    try {
      GitAction.Kind kind = GitAction.Kind.valueOf(value.toUpperCase(Locale.ROOT));
      if (kind != GitAction.Kind.PUSH && kind != GitAction.Kind.MERGE)
        throw new IllegalArgumentException("只支持推送或合并。");
      return kind;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("只支持推送或合并。");
    }
  }

  private static String excerpt(String output) {
    String value = output == null ? "" : output.strip().replaceAll("\\s+", " ");
    return value.length() > 500 ? value.substring(0, 500) : value;
  }

  private static DeliveryRequest map(GitDeliveryRequestEntity row) {
    return new DeliveryRequest(
        UUID.fromString(row.getId()),
        UUID.fromString(row.getWorkspaceId()),
        row.getKind(),
        row.getRemote(),
        row.getTarget(),
        row.getTargetCommit(),
        row.getHeadCommit(),
        row.getSourceBranch(),
        row.getRemoteUrlSha256(),
        row.getStatus(),
        row.getNote(),
        row.getResultDetail(),
        Instant.parse(row.getCreatedAt()),
        Instant.parse(row.getUpdatedAt()));
  }

  public enum DeliveryStatus {
    PENDING,
    APPROVED,
    REJECTED,
    STARTED,
    CONFIRMED,
    CONFLICT,
    UNKNOWN
  }

  public record DeliveryRequest(
      UUID id,
      UUID workspaceId,
      String kind,
      String remote,
      String target,
      String targetCommit,
      String headCommit,
      String sourceBranch,
      String remoteUrlSha256,
      String status,
      String note,
      String resultDetail,
      Instant createdAt,
      Instant updatedAt) {}
}
