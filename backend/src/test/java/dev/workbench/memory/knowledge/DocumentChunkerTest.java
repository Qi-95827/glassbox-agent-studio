package dev.workbench.memory.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 分块器：标题锚点、重叠窗口、行号与超长段落切分。 */
class DocumentChunkerTest {
  @Test
  void splitsOnHeadingsAndKeepsSectionTitle() {
    var chunks =
        new DocumentChunker(200, 40)
            .chunk(
                """
                # 认证模块
                登录使用 JWT。刷新令牌保存 30 天。所有失败请求写入审计日志。
                绝对不允许在日志中输出明文密钥或完整令牌内容。

                ## 失败处理
                401 表示令牌过期，需要重新登录。403 表示越权，必须拒绝并记录。
                超时重试最多三次，指数退避。
                """);
    assertTrue(chunks.size() >= 2);
    assertEquals("认证模块", chunks.getFirst().sectionTitle());
    assertTrue(
        chunks.stream().anyMatch(chunk -> "失败处理".equals(chunk.sectionTitle())),
        "后续章节应携带自己的标题");
    assertEquals(1, chunks.getFirst().startLine());
  }

  @Test
  void longDocumentProducesBoundedOverlappingChunks() {
    var paragraph = "line of evidence about checkout latency and retry policy. ";
    String text = paragraph.repeat(400);
    var chunks = new DocumentChunker(800, 120).chunk(text);
    assertTrue(chunks.size() > 3);
    assertTrue(
        chunks.stream().allMatch(chunk -> chunk.content().length() <= 900),
        "单块不得超过上限过多");
    assertTrue(
        chunks.stream()
            .anyMatch(chunk -> chunk.startLine() >= 1 && chunk.endLine() >= chunk.startLine()));
  }
}
