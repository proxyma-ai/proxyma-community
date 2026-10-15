import { useQuery } from '@tanstack/react-query'
import { spaceFactApi } from '../services/spaceFactApi'
import { keys } from '@/services/queryKeys'
import { useSpaceFactPreference } from './useSpaceFactPreference'

// Half an hour, matching SpaceFactRefreshJob's own cadence on the backend. A tab left open all day
// therefore picks up each new fact roughly when it is generated, without either side polling the
// other more often than there is anything new to see.
const REFRESH_MS = 1_800_000

// Faster, and only while there is no fact at all. The backend no longer serves a seed before its
// first successful generation (see SpaceFactService), so a fresh deployment answers 204 until the
// refresh job succeeds - which that job now retries every two minutes rather than every thirty
// while the hero is empty. Polling at the half-hourly cadence through that window would leave a
// visitor looking at a blank subtitle long after the fact existed.
const EMPTY_REFRESH_MS = 60_000

// The hero's astronomy fact. Deliberately NOT part of the page's first paint: the hero renders
// with no subtitle and the sentence appears when this resolves, which is why ChatPage reserves no
// space for it and shows no spinner - a fact that arrives 200ms late is unremarkable, a layout
// that jumps is not.
//
// null - not an error - is the ordinary answer on a deployment with no reachable chat model, and
// the hero then shows the Proxima Centauri default (shownSpaceFact). retry: false, like
// useSuggestedTopics before it - a deployment whose fact endpoint is failing should quietly show
// the default, not retry a decoration three times on every page load.
//
// Asked only when the viewer has opted in - see useSpaceFactPreference.
export function useSpaceFact() {
  const enabled = useSpaceFactPreference(s => s.enabled)
  return useQuery({
    queryKey: keys.spaceFact(),
    enabled,
    queryFn: spaceFactApi.get,
    staleTime: REFRESH_MS,
    refetchInterval: q => (q.state.data ? REFRESH_MS : EMPTY_REFRESH_MS),
    retry: false,
  })
}
