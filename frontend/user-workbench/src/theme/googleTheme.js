import { createTheme } from '@mui/material/styles';

/**
 * Google Material Design 3 设计令牌。
 *
 * 全站颜色、圆角、字体和组件默认值都从这里派生，页面不得自行发明色值。
 * 表面层级以低对比底色 + 1px 描边表达，阴影只用于悬浮操作与选中态。
 */
export const googleTokens = {
  canvas: '#F8F9FA',
  sidebar: '#F0F4F9',
  surface: '#FFFFFF',
  hover: '#F1F3F4',
  outline: '#E0E3E7',
  outlineStrong: '#DADCE0',
  primary: '#1A73E8',
  primaryHover: '#1765CC',
  primarySoft: '#D3E3FD',
  primarySofter: '#E8F0FE',
  primaryText: '#041E49',
  text: '#1F1F1F',
  textSecondary: '#5F6368',
  textDisabled: '#9AA0A6',
  successBg: '#E6F4EA',
  successFg: '#137333',
  warningBg: '#FEF7E0',
  warningFg: '#B06000',
  errorBg: '#FCE8E6',
  errorFg: '#C5221F',
  neutralBg: '#F1F3F4',
  neutralFg: '#5F6368',
  infoBg: '#E8F0FE',
  infoFg: '#1967D2',
  font: "'Google Sans Text', 'Inter', 'Roboto', 'Noto Sans SC', -apple-system, BlinkMacSystemFont, sans-serif",
  mono: "'JetBrains Mono', 'Roboto Mono', 'Cascadia Code', monospace"
};

const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: googleTokens.primary,
      dark: googleTokens.primaryHover,
      light: googleTokens.primarySoft,
      contrastText: '#FFFFFF'
    },
    secondary: { main: '#5F6368' },
    background: { default: googleTokens.canvas, paper: googleTokens.surface },
    text: {
      primary: googleTokens.text,
      secondary: googleTokens.textSecondary,
      disabled: googleTokens.textDisabled
    },
    divider: googleTokens.outline,
    success: { main: googleTokens.successFg, light: googleTokens.successBg, dark: '#0D652D' },
    warning: { main: googleTokens.warningFg, light: googleTokens.warningBg, dark: '#8A5A00' },
    error: { main: googleTokens.errorFg, light: googleTokens.errorBg, dark: '#A50E0E' },
    info: { main: googleTokens.infoFg, light: googleTokens.infoBg, dark: '#041E49' },
    action: {
      hover: googleTokens.hover,
      selected: googleTokens.primarySofter,
      active: googleTokens.primary
    }
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: googleTokens.font,
    h4: { fontSize: 26, fontWeight: 500, lineHeight: 1.35, letterSpacing: '-0.01em' },
    h5: { fontSize: 22, fontWeight: 500, lineHeight: 1.4 },
    h6: { fontSize: 16, fontWeight: 500, lineHeight: 1.5 },
    subtitle1: { fontSize: 14, fontWeight: 600, lineHeight: 1.5 },
    subtitle2: { fontSize: 13, fontWeight: 600, lineHeight: 1.5 },
    body1: { fontSize: 14, fontWeight: 400, lineHeight: 1.55 },
    body2: { fontSize: 13, fontWeight: 400, lineHeight: 1.6 },
    caption: { fontSize: 12, fontWeight: 400, lineHeight: 1.5 },
    button: { textTransform: 'none', fontWeight: 500, fontSize: 14, letterSpacing: 0 },
    overline: { fontSize: 11, fontWeight: 500, letterSpacing: '0.06em' }
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          backgroundColor: googleTokens.canvas,
          color: googleTokens.text,
          WebkitFontSmoothing: 'antialiased'
        }
      }
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: {
          borderRadius: 9999,
          minHeight: 36,
          paddingInline: 18,
          textTransform: 'none',
          fontWeight: 500,
          boxShadow: 'none'
        },
        sizeSmall: { minHeight: 30, paddingInline: 12, fontSize: 12.5 },
        containedPrimary: {
          '&:hover': { backgroundColor: googleTokens.primaryHover, boxShadow: 'none' }
        },
        outlined: { borderColor: googleTokens.outlineStrong }
      }
    },
    MuiIconButton: {
      styleOverrides: {
        root: { borderRadius: 9999, color: googleTokens.textSecondary },
        sizeSmall: { padding: 6 }
      }
    },
    MuiPaper: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: { backgroundImage: 'none' },
        rounded: { borderRadius: 16 }
      }
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 16,
          backgroundImage: 'none'
        }
      }
    },
    MuiDialog: {
      styleOverrides: {
        paper: { borderRadius: 24, backgroundImage: 'none' }
      }
    },
    MuiDialogTitle: {
      styleOverrides: { root: { fontSize: 20, fontWeight: 500, padding: '22px 24px 10px' } }
    },
    MuiDialogContent: {
      styleOverrides: { root: { padding: '16px 24px' } }
    },
    MuiDialogActions: {
      styleOverrides: { root: { padding: '14px 20px 18px' } }
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          borderRadius: 12,
          backgroundColor: '#FFFFFF',
          '& .MuiOutlinedInput-notchedOutline': { borderColor: googleTokens.outlineStrong },
          '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: '#AEB4BC' },
          '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
            borderColor: googleTokens.primary,
            borderWidth: 2
          }
        },
        inputSizeSmall: { paddingTop: 9, paddingBottom: 9 }
      }
    },
    MuiInputLabel: {
      styleOverrides: { root: { fontSize: 14, color: googleTokens.textSecondary } }
    },
    MuiFormHelperText: {
      styleOverrides: { root: { marginLeft: 4, fontSize: 11.5 } }
    },
    MuiSelect: { styleOverrides: { select: { fontSize: 14 } } },
    MuiMenu: {
      styleOverrides: {
        paper: {
          borderRadius: 12,
          border: `1px solid ${googleTokens.outline}`,
          boxShadow: '0 6px 22px rgba(60,64,67,.16)',
          marginTop: 6
        }
      }
    },
    MuiMenuItem: {
      styleOverrides: { root: { fontSize: 13.5, marginInline: 6, borderRadius: 8 } }
    },
    MuiChip: {
      styleOverrides: {
        root: { borderRadius: 9999, fontWeight: 500 },
        sizeSmall: { height: 24, fontSize: 11.5 },
        label: { paddingInline: 9 }
      }
    },
    MuiTabs: {
      styleOverrides: {
        indicator: { height: 3, borderRadius: '3px 3px 0 0' }
      }
    },
    MuiTab: {
      styleOverrides: {
        root: {
          textTransform: 'none',
          fontSize: 13.5,
          fontWeight: 500,
          minHeight: 48,
          minWidth: 0,
          paddingInline: 14,
          color: googleTokens.textSecondary,
          '&.Mui-selected': { color: googleTokens.infoFg }
        }
      }
    },
    MuiAccordion: {
      defaultProps: { disableGutters: true, elevation: 0 },
      styleOverrides: {
        root: {
          border: `1px solid ${googleTokens.outline}`,
          borderRadius: 16,
          '&:before': { display: 'none' },
          '&.Mui-expanded': { margin: 0 }
        }
      }
    },
    MuiAccordionSummary: {
      styleOverrides: { root: { minHeight: 52, paddingInline: 18 } }
    },
    MuiAccordionDetails: {
      styleOverrides: { root: { padding: '0 18px 18px' } }
    },
    MuiAlert: {
      styleOverrides: {
        root: { borderRadius: 12, fontSize: 13, alignItems: 'flex-start' },
        message: { paddingTop: 1 }
      }
    },
    MuiAppBar: { defaultProps: { elevation: 0, color: 'inherit' } },
    MuiTooltip: {
      styleOverrides: {
        tooltip: {
          backgroundColor: '#3C4043',
          fontSize: 12,
          borderRadius: 8,
          padding: '6px 10px'
        }
      }
    },
    MuiTableCell: {
      styleOverrides: {
        root: { borderColor: googleTokens.outline, fontSize: 13, paddingBlock: 12 },
        head: {
          backgroundColor: googleTokens.canvas,
          color: googleTokens.textSecondary,
          fontSize: 12,
          fontWeight: 500
        }
      }
    },
    MuiToggleButtonGroup: {
      styleOverrides: {
        root: {
          backgroundColor: googleTokens.neutralBg,
          borderRadius: 9999,
          padding: 3,
          gap: 2,
          '& .MuiToggleButtonGroup-grouped': {
            border: 0,
            borderRadius: '9999px !important',
            margin: 0,
            paddingInline: 14,
            fontSize: 12.5,
            color: googleTokens.textSecondary,
            '&.Mui-selected': {
              backgroundColor: '#FFFFFF',
              color: googleTokens.primaryText,
              boxShadow: '0 1px 3px rgba(60,64,67,.22)'
            },
            '&.Mui-selected:hover': { backgroundColor: '#FFFFFF' }
          }
        }
      }
    },
    MuiToggleButton: { styleOverrides: { root: { textTransform: 'none' } } },
    MuiListItemButton: {
      styleOverrides: { root: { borderRadius: 9999 } }
    },
    MuiSwitch: {
      styleOverrides: { root: { padding: 8 } }
    },
    MuiSkeleton: { defaultProps: { animation: 'wave' } },
    MuiLinearProgress: {
      styleOverrides: { root: { borderRadius: 9999, height: 6 } }
    },
    MuiDivider: { styleOverrides: { root: { borderColor: googleTokens.outline } } },
    MuiBadge: {
      styleOverrides: { dot: { minWidth: 8, height: 8, borderRadius: 4 } }
    }
  }
});

export default theme;
