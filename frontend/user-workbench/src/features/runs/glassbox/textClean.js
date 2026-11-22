/**
 * 清洗 Markdown 语法噪音，用于窄栏卡片、列表摘要等纯文本预览场景。
 * 完整正文仍交由 MarkdownView 原生渲染（支持标题、列表、代码块、表格）。
 */
export function stripMarkdownNoise(text) {
  if (!text) return '';
  return String(text)
    .replace(/```[\s\S]*?```/g, ' [代码块] ')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^\s*\|?(?:\s*:?-{2,}:?\s*\|)+\s*$/gm, '')
    .replace(/\|/g, ' · ')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/^\s*[-*•]\s+/gm, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function isHeadingOnly(line) {
  const trimmed = String(line || '').trim();
  if (!trimmed) return true;
  if (/^#{1,6}\s+/.test(trimmed)) return true;
  if (/^\|?(?:\s*:?-{2,}:?\s*\|)+\s*$/.test(trimmed)) return true;
  if (/^[-*_]{3,}$/.test(trimmed)) return true;
  return false;
}

/**
 * 为交接信封挑选更可读的“上游核心结论”：
 * 若后端提取的首行只是 Markdown 标题（例如 "## 一、已观察到的证据"），
 * 则优先从完整正文中寻找“结论”行或第一段实质内容，避免把章节标题当成结论。
 */
export function readableHandoffSummary(structuredSummary, sourceResult, maxLength = 200) {
  const rawSummary = String(structuredSummary || '').trim();
  if (rawSummary && !isHeadingOnly(rawSummary)) {
    const cleaned = stripMarkdownNoise(rawSummary);
    return cleaned.length > maxLength ? `${cleaned.slice(0, maxLength)}…` : cleaned;
  }

  const lines = String(sourceResult || '')
    .replace(/\r\n/g, '\n')
    .split('\n')
    .map(line => line.trim())
    .filter(Boolean);

  const conclusionLine = lines.find(
    line => !isHeadingOnly(line) && /(?:结论|总结|核心结论|最终结果|综上)/.test(line)
  );
  if (conclusionLine) {
    const cleaned = stripMarkdownNoise(conclusionLine);
    return cleaned.length > maxLength ? `${cleaned.slice(0, maxLength)}…` : cleaned;
  }

  const firstSubstantive = lines.find(line => !isHeadingOnly(line) && !line.startsWith('|'));
  const fallback = stripMarkdownNoise(firstSubstantive || rawSummary || sourceResult || '');
  if (!fallback) return '上游未记录交接正文。';
  return fallback.length > maxLength ? `${fallback.slice(0, maxLength)}…` : fallback;
}

/** 清理单条要点（如关键决策）前导的 ### / - 符号，同时保留行内格式交给 MarkdownView。 */
export function cleanBulletMarkdown(text) {
  return String(text || '')
    .trim()
    .replace(/^#{1,6}\s+/, '')
    .replace(/^[-*•]\s+/, '');
}
