import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { settingsApi } from '../services/settingsApi'

// One settings group, cached by prefix. `enabled=false` defers the fetch - for a caller that may not
// read the group at all (a role that cannot, a section a license leaves out).
export function useSettings(prefix: string, enabled = true) {
  return useQuery({
    queryKey: keys.settings(prefix),
    queryFn: () => settingsApi.getGroup(prefix),
    enabled,
  })
}
