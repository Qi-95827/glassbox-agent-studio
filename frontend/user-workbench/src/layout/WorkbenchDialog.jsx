import React, { useId } from 'react';
import { Button, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, useMediaQuery } from '@mui/material';
import CloseRounded from '@mui/icons-material/CloseRounded';

export default function WorkbenchDialog({
  title, open, onCancel, onOk, footer, width = 640, okText = '确认', cancelText = '取消', confirmLoading, children
}) {
  const titleId = useId();
  const fullScreen = useMediaQuery('(max-width:599px)');
  return <Dialog open={open} onClose={onCancel} fullScreen={fullScreen} fullWidth maxWidth={false}
    aria-labelledby={titleId} slotProps={{ paper: { sx: { maxWidth: fullScreen ? '100%' : width } } }}>
    <DialogTitle id={titleId} sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 2 }}>
      {title}
      <IconButton onClick={onCancel} aria-label="关闭" size="small"><CloseRounded /></IconButton>
    </DialogTitle>
    <DialogContent dividers sx={{ px: { xs: 2, sm: 3 }, overflowWrap: 'anywhere' }}>{children}</DialogContent>
    {footer !== null && onOk && <DialogActions sx={{ px: 3, py: 1.5 }}>
      <Button onClick={onCancel}>{cancelText}</Button>
      <Button variant="contained" onClick={onOk} loading={confirmLoading}>{okText}</Button>
    </DialogActions>}
  </Dialog>;
}
