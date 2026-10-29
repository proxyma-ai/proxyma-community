import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { chatApi } from '../services/chatApi'
import { soonestFirst } from '../utils/alarms'

/**
 * The alarms the conversation's agent set and that have not gone off, soonest first - the chip
 * beside the composer. Read again when a turn starts or ends and when an `alarm` call returns
 * (useChat), and once a minute while there are any, so one that went off leaves the list.
 */
export function useAlarms(conversationId: string | undefined) {
  const query = useQuery({
    queryKey: keys.alarms(conversationId ?? ''),
    queryFn: () => chatApi.getAlarms(conversationId!).then(soonestFirst),
    enabled: !!conversationId,
    retry: false,
    refetchInterval: q => ((q.state.data?.length ?? 0) > 0 ? 60_000 : false),
  })
  return { ...query, alarms: query.data ?? [] }
}
