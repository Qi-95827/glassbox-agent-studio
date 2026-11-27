package dev.workbench.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import dev.workbench.workspace.AuthorizedFileSet;
import dev.workbench.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 工作区工具的两个校验器必须各管一件事：Schema 只认参数字段，路径白名单只认授权集合。
 *
 * <p>这样网关才能把「参数填错」与「路径越界」分别记到 ARGUMENT_SCHEMA / PATH_WHITELIST，而不是双重 DENY。
 */
class WorkspaceToolValidationSplitTest {
  private final WorkspaceService service = mock(WorkspaceService.class);
  private final AuthorizedFileSet scope =
      new AuthorizedFileSet(AuthorizedFileSet.normalizeScope(List.of("src/app.js")));

  @Test
  void scopedReadSchemaAcceptsAnAuthorizedPathWhileScopeCheckAllowsIt() {
    var spec = WorkspaceScopedReadTool.specification(service, "ws-1", scope, "zh-CN");
    assertNotNull(spec.scopeValidate(), "路径校验必须单独声明，不能混进 Schema 闭包");
    assertDoesNotThrow(() -> spec.validate().accept(Map.of("path", "src/app.js")));
    assertDoesNotThrow(() -> spec.scopeValidate().accept(Map.of("path", "src/app.js")));
  }

  @Test
  void outOfScopePathPassesSchemaButIsRejectedByTheScopeCheck() {
    var spec = WorkspaceScopedReadTool.specification(service, "ws-1", scope, "zh-CN");
    Map<String, String> outOfScope = Map.of("path", "../etc/passwd");

    // 参数本身格式合法：Schema 门不得代替路径门给出结论。
    assertDoesNotThrow(() -> spec.validate().accept(outOfScope));
    assertThrows(
        IllegalArgumentException.class, () -> spec.scopeValidate().accept(outOfScope));
  }

  @Test
  void blankPathIsRejectedBySchemaBeforeTheScopeCheckRuns() {
    var spec = WorkspaceScopedReadTool.specification(service, "ws-1", scope, "zh-CN");
    assertThrows(
        IllegalArgumentException.class, () -> spec.validate().accept(Map.of("path", "  ")));
  }

  @Test
  void scopedWriteSplitsContentValidationFromPathWhitelist() {
    var spec = WorkspaceScopedWriteTool.specification(service, "ws-1", scope, "zh-CN");
    assertNotNull(spec.scopeValidate());

    Map<String, String> invalidSha =
        Map.of("path", "src/app.js", "expectedSha256", "not-a-sha", "content", "text");
    assertThrows(IllegalArgumentException.class, () -> spec.validate().accept(invalidSha));

    Map<String, String> unauthorized =
        Map.of("path", "src/other.js", "expectedSha256", "a".repeat(64), "content", "text");
    assertThrows(
        IllegalArgumentException.class, () -> spec.scopeValidate().accept(unauthorized));
  }
}
