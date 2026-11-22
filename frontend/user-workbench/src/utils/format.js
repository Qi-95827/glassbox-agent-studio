/** 展示层格式化工具；只做排版，不补造后端没有的数据。 */

export function dateTime(value, locale = 'zh-CN') {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString(locale, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

export function clock(value, locale = 'zh-CN') {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleTimeString(locale, { hour12: false });
}

/** 毫秒 → 简洁耗时，例如 12.4s、1m 04s。 */
export function duration(ms) {
  if (ms == null || Number.isNaN(Number(ms))) return '—';
  const value = Math.max(0, Number(ms));
  if (value < 1000) return `${Math.round(value)}ms`;
  if (value < 60000) return `${(value / 1000).toFixed(1)}s`;
  const minutes = Math.floor(value / 60000);
  const seconds = Math.round((value % 60000) / 1000);
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
}

/** 节点耗时：运行中节点以快照生成时间作为观测终点。 */
export function nodeDuration(node, generatedAt) {
  if (!node?.startedAt) return null;
  const end = node.finishedAt || (node.status === 'RUNNING' ? generatedAt : null);
  if (!end) return null;
  return new Date(end).getTime() - new Date(node.startedAt).getTime();
}

export function timeAgo(value, locale = 'zh-CN') {
  if (!value) return '—';
  const diff = Date.now() - new Date(value).getTime();
  if (Number.isNaN(diff)) return '—';
  const english = String(locale).toLowerCase().startsWith('en');
  if (diff < 60000) return english ? 'just now' : '刚刚';
  if (diff < 3600000) {
    const minutes = Math.floor(diff / 60000);
    return english ? `${minutes} min ago` : `${minutes} 分钟前`;
  }
  if (diff < 86400000) {
    const hours = Math.floor(diff / 3600000);
    return english ? `${hours} hr ago` : `${hours} 小时前`;
  }
  return dateTime(value, locale);
}

export function shortId(value, length = 8) {
  if (!value) return '—';
  return String(value).slice(0, length);
}

export function oneLine(value, max = 80) {
  if (!value) return '';
  const text = String(value).replace(/\s+/g, ' ').trim();
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

export function parseJson(value, fallback = null) {
  if (value == null) return fallback;
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

/** 兼容数据库列名风格字段：任务列表统一映射为干净的 UI 模型。 */
export function parseFileScopes(task) {
  const parsed = parseJson(task?.FILE_SCOPES_JSON, null);
  if (Array.isArray(parsed) && parsed.every(item => typeof item === 'string')) return parsed;
  return task?.FILE_SCOPE ? [task.FILE_SCOPE] : [];
}

export function splitLines(value) {
  return String(value ?? '')
    .split('\n')
    .map(line => line.trim())
    .filter(Boolean);
}

export function numberOrNull(value) {
  return value === '' || value == null ? null : Number(value);
}

export function gobble(value, max = 120) {
  return String(value ?? '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, max);
}
