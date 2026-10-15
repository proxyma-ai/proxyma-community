import { useAppConfig } from './useAppConfig'

/**
 * True on the public Enterprise sandbox (`sandbox` in /api/v1/config). The same cached config query
 * every other reader shares, so asking costs nothing.
 */
export function useIsSandbox(): boolean {
  const { data } = useAppConfig()
  return data?.sandbox === true
}
