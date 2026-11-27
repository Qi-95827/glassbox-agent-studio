package dev.workbench.infrastructure.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.workbench.framework.tool.ToolSpecification;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 私有工具包装必须透传路径白名单校验。
 *
 * <p>回归：`privateSpec` 用旧构造器重建规格时漏了 `scopeValidate`，导致私有多文件工具被网关记为
 * `PATH_WHITELIST: SKIP`，越界路径直到执行期才报错。这里通过反射构造其包内 PinnedTool 后调用真实
 * `privateSpec`，验证重建后的规格仍带着原工具的路径校验闭包。
 */
class AgentToolRegistryPrivateSpecTest {
  @Test
  void privateSpecKeepsTheOriginalScopeValidation() throws Exception {
    var original =
        new ToolSpecification<String>(
                "private_read_tool",
                "读取授权文件",
                List.of(),
                List.of("ws-1"),
                "开始读取。",
                arguments -> {},
                (arguments, check) -> "done",
                (arguments, value) -> value,
                null)
            .withScopeValidate(
                arguments -> {
                  throw new IllegalArgumentException("路径不在授权范围：../secret.txt");
                });

    Class<?> pinnedType =
        Class.forName(
            "dev.workbench.framework.agent.definition.PrivateAgentDefinitionSource$PinnedTool");
    var constructor = pinnedType.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object pinned =
        constructor.newInstance(
            "tool-1", 1, "私有读取", "READ", "private_read_tool", List.of(".js"), "a".repeat(64));

    Method privateSpec =
        AgentToolRegistry.class.getDeclaredMethod(
            "privateSpec", ToolSpecification.class, pinnedType, String.class);
    privateSpec.setAccessible(true);
    @SuppressWarnings("unchecked")
    var spec = (ToolSpecification<String>) privateSpec.invoke(null, original, pinned, "zh-CN");

    assertEquals("private_read_tool", spec.name());
    assertNotNull(spec.scopeValidate(), "私有工具必须保留路径白名单校验，不能退化成 SKIP");
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> spec.scopeValidate().accept(Map.of("path", "../secret.txt")));
    assertEquals("路径不在授权范围：../secret.txt", error.getMessage());
  }
}
