import { useEffect, useState } from 'react';

function readVisibility() {
  if (typeof document === 'undefined') return true;
  return document.visibilityState !== 'hidden';
}

/**
 * 标签页可见性。隐藏时调用方应抑制轮询：后台标签页里继续拉取只会和前台请求抢带宽，
 * 也可能让用户在切回标签页前看到过期的中间态。
 *
 * <p>无 `document`（服务端渲染或非 DOM 测试环境）时按可见处理，保持向后兼容。
 */
export default function useDocumentVisible() {
  const [visible, setVisible] = useState(readVisibility);

  useEffect(() => {
    if (typeof document === 'undefined') return undefined;
    const onChange = () => setVisible(readVisibility());
    document.addEventListener('visibilitychange', onChange);
    return () => document.removeEventListener('visibilitychange', onChange);
  }, []);

  return visible;
}
