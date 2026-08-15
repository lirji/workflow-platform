// 调色板单一来源:theme.ts 的 token 与需要行内色值的组件都从这里取,避免散落硬编码。
export const colors = {
  primary: '#315EFB',
  primarySoft: '#EEF3FF',
  success: '#16A36A',
  warning: '#D97706',
  error: '#D92D20',
  bgLayout: '#F5F7FA',
  bgSubtle: '#F7F9FC',
  border: '#E6EAF0',
  text: '#172033',
  textSecondary: '#667085',
  textTertiary: '#98A2B3',
} as const
