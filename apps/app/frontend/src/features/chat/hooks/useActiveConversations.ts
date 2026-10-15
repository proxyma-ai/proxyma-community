import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { chatApi } from '../services/chatApi'

const NO_IDS: string[] = []

/**
 * The caller's conversations with a turn running now, the most recently started first - the dots in
 * the history list and the Chat badge. Asked often while something is running, so a finished turn
 * clears within seconds, and rarely otherwise; a send refreshes it at once (useChat).
 *
 * The chat page's by default; `surface` asks for the ones another page holds (a key account's panel),
 * which the server lists only when named.
 */
export function useActiveConversations(surface?: string): string[] {
  const { data } = useQuery({
    queryKey: keys.activeConversations(surface),
    queryFn: () => chatApi.getActiveConversations(surface),
    refetchInterval: query => ((query.state.data?.length ?? 0) > 0 ? 3000 : 15000),
    refetchIntervalInBackground: false,
  })
  return data ?? NO_IDS
}
