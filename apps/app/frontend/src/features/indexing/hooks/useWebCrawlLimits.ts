import { useQuery } from '@tanstack/react-query'
import { keys } from '@/services/queryKeys'
import { indexingApi, type WebCrawlLimits } from '../services/indexingApi'

// Deployment-wide constants (WebCrawlConnector.MAX_CRAWL_DEPTH / MAX_CRAWL_PAGES), so this is
// fetched once and never refetched or invalidated - same treatment as the file-type catalog.
//
// The fallback matters: it is what the form validates against for the few hundred milliseconds
// before the query resolves, and on an older backend that has no /webpage/limits endpoint at all.
// It is deliberately the CONSERVATIVE pre-change ceiling rather than the new one - offering a
// bound the server might reject is worse than offering less than it would allow, and the server
// now rejects out-of-range values with a message naming the real maximum either way.
//
// The DEFAULTS are the server's own (WebCrawlConnector.DEFAULT_MAX_DEPTH / DEFAULT_MAX_PAGES, 3
// and 100): both source dialogs start from them - the add dialog's empty form, and the settings
// of a crawl saved before it recorded a bound - so the two show what the crawl actually uses. The
// pages default said 200 here while the server read a missing bound as 100.
const FALLBACK: WebCrawlLimits = { maxDepth: 20, maxPages: 2000, defaultDepth: 3, defaultPages: 100 }

export function useWebCrawlLimits(): WebCrawlLimits {
  const { data } = useQuery({
    queryKey: keys.webCrawlLimits(),
    queryFn: indexingApi.getWebCrawlLimits,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
  })
  return data ?? FALLBACK
}
