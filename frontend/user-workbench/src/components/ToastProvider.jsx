import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { Alert, Snackbar } from '@mui/material';

const ToastContext = createContext(null);

/** 全站唯一的 Snackbar 通道，统一替代旧的命令式消息提示。 */
export function ToastProvider({ children }) {
  const [queue, setQueue] = useState([]);

  const push = useCallback((message, severity = 'info') => {
    if (!message) return;
    setQueue(previous => [
      ...previous,
      { id: `${Date.now()}-${Math.random()}`, message, severity }
    ]);
  }, []);

  const toast = useMemo(
    () => ({
      success: message => push(message, 'success'),
      error: message => push(message, 'error'),
      warning: message => push(message, 'warning'),
      info: message => push(message, 'info')
    }),
    [push]
  );

  const active = queue[0];

  return (
    <ToastContext.Provider value={toast}>
      {children}
      <Snackbar
        key={active?.id}
        open={Boolean(active)}
        autoHideDuration={3800}
        onClose={() => setQueue(previous => previous.slice(1))}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity={active?.severity || 'info'}
          variant="filled"
          onClose={() => setQueue(previous => previous.slice(1))}
          sx={{ borderRadius: 2.5, boxShadow: '0 4px 18px rgba(60,64,67,.24)' }}
        >
          {active?.message}
        </Alert>
      </Snackbar>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToast 必须在 ToastProvider 内使用');
  return context;
}
