export const ENV = {
  appMode:    import.meta.env.VITE_APP_MODE as 'desktop' | 'server',
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL as string,
} as const
