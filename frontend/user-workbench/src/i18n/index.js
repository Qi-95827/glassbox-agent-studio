import zhCN from './locales/zh-CN.json';
import enUS from './locales/en-US.json';

export const SUPPORTED_LOCALES = ['zh-CN', 'en-US'];
export const DEFAULT_LOCALE = 'zh-CN';
export const LOCALE_STORAGE_KEY = 'agent_studio_locale';

const MESSAGES = { 'zh-CN': zhCN, 'en-US': enUS };

function initialModuleLocale() {
  if (typeof window === 'undefined') return DEFAULT_LOCALE;
  try {
    const saved = window.localStorage?.getItem(LOCALE_STORAGE_KEY);
    if (SUPPORTED_LOCALES.includes(saved)) return saved;
  } catch {
    // 隐私模式下无法读取偏好，按默认中文。
  }
  // 未挂载 Provider 的独立组件/单测保持中文默认，避免依赖浏览器语言。
  return DEFAULT_LOCALE;
}

let currentLocale = initialModuleLocale();
const listeners = new Set();

/** 读取持久化语言；未设置时按浏览器语言推断，默认回退简体中文。 */
export function detectInitialLocale() {
  if (typeof window === 'undefined') return DEFAULT_LOCALE;
  try {
    const saved = window.localStorage?.getItem(LOCALE_STORAGE_KEY);
    if (SUPPORTED_LOCALES.includes(saved)) return saved;
  } catch {
    // 隐私模式下 localStorage 可能不可用，继续按浏览器语言推断。
  }
  const navigatorLocale = window.navigator?.language || '';
  return navigatorLocale.toLowerCase().startsWith('en') ? 'en-US' : DEFAULT_LOCALE;
}

export function normalizeLocale(value) {
  return String(value || '')
    .toLowerCase()
    .startsWith('en')
    ? 'en-US'
    : DEFAULT_LOCALE;
}

/** 供 api/client.js 等非 React 模块同步读取当前语言。 */
export function getCurrentLocale() {
  return currentLocale;
}

export function setModuleLocale(locale) {
  currentLocale = normalizeLocale(locale);
  return currentLocale;
}

export function subscribeLocale(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function lookup(locale, key) {
  const catalog = MESSAGES[locale];
  if (!catalog) return undefined;
  // 支持扁平命名空间键（common.save）与嵌套 JSON 两种组织方式。
  if (typeof catalog[key] === 'string') return catalog[key];
  let node = catalog;
  for (const part of String(key).split('.')) {
    if (node == null || typeof node !== 'object') return undefined;
    node = node[part];
  }
  return typeof node === 'string' ? node : undefined;
}

/** 按 key 取文案并做模板插值；缺失时英文回退中文，再回退 key 本身。 */
export function translate(locale, key, params) {
  const normalized = normalizeLocale(locale);
  const template =
    lookup(normalized, key) ??
    (normalized !== DEFAULT_LOCALE ? lookup(DEFAULT_LOCALE, key) : undefined) ??
    String(key);
  if (!params || typeof params !== 'object') return template;
  return template.replace(/\{(\w+)\}/g, (match, name) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match
  );
}

/** 同步更新 html lang 与文档标题，便于屏幕阅读器、SEO 与视觉确认。 */
export function applyDocumentLocale(locale, t) {
  if (typeof document === 'undefined') return;
  const normalized = normalizeLocale(locale);
  document.documentElement.lang = normalized;
  const title = t('app.documentTitle');
  if (title) document.title = title;
}

/**
 * 组件级辅助函数：不依赖 Provider，默认使用模块当前语言。
 * 未包裹 I18nProvider 的独立组件单测因此仍可渲染中文。
 */
export function t(key, params) {
  return translate(getCurrentLocale(), key, params);
}
