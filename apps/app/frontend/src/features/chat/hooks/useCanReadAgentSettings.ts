import { FEATURES } from '@/config/features'
import { useAuth } from '@/features/auth/hooks/useAuth'

/**
 * Whether the reader may read the agent's settings - its provider and model. Everyone on Desktop
 * and the single-user server; administrators on Enterprise Server, where the chat settings are an
 * administrator's to read and asking for them as anyone else is refused. One rule, written once:
 * it was written out three times (CHAT-25), in the composer's model label, a deployed agent's waiting
 * row and the start links' "Set up a model".
 */
export function useCanReadAgentSettings(): boolean {
  const role = useAuth().user?.role
  return !FEATURES.auth || role === 'ADMIN' || role === 'SUPER_ADMIN'
}
