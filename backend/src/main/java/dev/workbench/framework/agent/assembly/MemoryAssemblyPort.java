package dev.workbench.framework.agent.assembly;

import java.util.List;

/**
 * 装配层读取本次运行记忆召回的端口。
 *
 * <p>基础设施层只依赖该纯净契约；具体读取运行引用表的实现位于 memory 包，避免顶层循环依赖。
 */
public interface MemoryAssemblyPort {
  List<AgentAssemblySnapshot.MemoryMatchView> matchesForRun(String runId);
}
