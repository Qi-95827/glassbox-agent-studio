import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import {
  applyDocumentLocale,
  DEFAULT_LOCALE,
  detectInitialLocale,
  getCurrentLocale,
  LOCALE_STORAGE_KEY,
  normalizeLocale,
  setModuleLocale,
  SUPPORTED_LOCALES,
  translate
} from './index';
import { queryClient } from '../app/queryClient';

const I18nContext = createContext(null);

function persistLocale(locale) {
  try {
    window.localStorage?.setItem(LOCALE_STORAGE_KEY, locale);
  } catch {
    // 无法持久化时仅影响刷新后的默认语言，不影响当前会话。
  }
}

/** 全站语言上下文：持久化偏好、同步 HTML lang 与依赖语言的只读查询。 */
export function I18nProvider({ children, initialLocale }) {
  const [locale, setLocaleState] = useState(() =>
    normalizeLocale(initialLocale || detectInitialLocale())
  );

  const changeLocale = useCallback(next => {
    const normalized = normalizeLocale(next);
    setModuleLocale(normalized);
    persistLocale(normalized);
    setLocaleState(normalized);
    // 语言变化后重新读取依赖服务端本地化文案的只读查询。
    queryClient.invalidateQueries();
  }, []);

  const t = useCallback((key, params) => translate(locale, key, params), [locale]);

  useEffect(() => {
    setModuleLocale(locale);
    applyDocumentLocale(locale, t);
  }, [locale, t]);

  const value = useMemo(
    () => ({
      locale,
      setLocale: changeLocale,
      toggleLocale: () => changeLocale(locale === 'en-US' ? DEFAULT_LOCALE : 'en-US'),
      t,
      isEnglish: locale === 'en-US',
      supportedLocales: SUPPORTED_LOCALES
    }),
    [locale, changeLocale, t]
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

/**
 * 语言 Hook：Provider 之外优雅回退到模块语言与默认中文，保证独立组件单测零破坏。
 */
export function useI18n() {
  const context = useContext(I18nContext);
  const fallbackLocale = getCurrentLocale();
  const locale = context?.locale ?? fallbackLocale;
  const t = context?.t ?? ((key, params) => translate(locale, key, params));
  return {
    locale,
    setLocale: context?.setLocale ?? (() => {}),
    toggleLocale: context?.toggleLocale ?? (() => {}),
    t,
    isEnglish: locale === 'en-US',
    supportedLocales: SUPPORTED_LOCALES
  };
}

export default I18nProvider;
