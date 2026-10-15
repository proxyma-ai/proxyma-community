import { useAppConfig } from './useAppConfig'
import { ENV } from '@/config/env'

/**
 * How a file source is added on THIS deployment: by uploading bytes, or by naming a folder the
 * user already has on disk.
 *
 * <p>THIS USED TO BE `FEATURES.fileUpload`, A COMPILE-TIME CONSTANT, and the move is the whole
 * point rather than a refactor. It read `ENV.appMode === 'server'`, which was right for exactly as
 * long as "built as the server edition" and "takes uploads" were the same deployments. The home
 * server is built from the DESKTOP bundle - it is the desktop application packaged for a machine
 * with no screen - and it takes uploads, because "pick a folder" inside a container means a bind
 * mount the operator has to get right before a single document can be indexed.
 *
 * <p>One bundle now serves two answers, so the answer has to come down the wire. It is the same
 * reasoning `homeServer` and `sandbox` follow, and the same reasoning that removed `FEATURES.orgMode`: a
 * FEATURES map is the first place anyone looks for "does this build do X", and it can only
 * honestly hold things a BUILD decides.
 *
 * <p><b>The fallback is the old build-time answer, not `false`.</b> While the query is in flight,
 * or against a backend too old to report the field, `VITE_APP_MODE === 'server'` is exactly what
 * this component tree assumed before - so an older deployment behaves as it always did rather than
 * losing its upload button to a missing key. A home server on a matching backend gets `true` from
 * the server; a home server on an old one gets the old behaviour, which is the honest degradation.
 *
 * <p>Shares the one config query (useAppConfig), so it adds no request.
 */
export function useFileUpload(): boolean {
  const { data } = useAppConfig()
  return data?.fileUpload ?? ENV.appMode === 'server'
}
