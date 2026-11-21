import { createContext, useContext, useEffect, useMemo, useState } from 'react';

const ChromeContext = createContext(null);

/**
 * 顶栏上下文：页面声明面包屑与运行状态，避免把页面数据上浮到应用根节点。
 */
export function PageChromeProvider({ children }) {
  const [page, setPage] = useState({ trail: [], status: null, model: null });
  const value = useMemo(() => ({ page, setPage }), [page]);
  return <ChromeContext.Provider value={value}>{children}</ChromeContext.Provider>;
}

export function usePageChrome(page) {
  const context = useContext(ChromeContext);
  const setPage = context?.setPage;
  const key = `${(page?.trail ?? []).map(item => item.label).join('|')}:${page?.status ?? ''}:${
    page?.model?.label ?? ''
  }`;

  useEffect(() => {
    if (!setPage) return undefined;
    setPage({
      trail: page?.trail ?? [],
      status: page?.status ?? null,
      model: page?.model ?? null
    });
    return () => setPage({ trail: [], status: null, model: null });
    // key 已覆盖需要同步的可见内容；setPage 来自 useState，引用稳定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, setPage]);

  return context?.page ?? page;
}

export function useChrome() {
  return useContext(ChromeContext);
}
