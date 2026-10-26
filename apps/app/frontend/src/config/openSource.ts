/**
 * Where Proxyma Community's source code is, and the license it is under - the ONE place the app
 * names either (Settings > About, SourceCodeSection.desktop.tsx).
 *
 * THIS IS AN OBLIGATION, NOT A COURTESY. Proxyma Community is AGPL-3.0-only, and section 13 of that
 * license requires a program that users reach over a network - the Home Server is one - to offer
 * each of them its Corresponding Source. If you MODIFY Proxyma Community and let others use your
 * version over a network, point COMMUNITY_SOURCE_URL at the source of YOUR version: the link here
 * is how this build makes that offer.
 *
 * Proxyma Enterprise is not open source and its build shows neither (ee/, docs/architecture.html#oss-boundary).
 */
export const COMMUNITY_SOURCE_URL = 'https://github.com/proxyma-ai/proxyma-community'

/**
 * Where Proxyma Community is supported (the maintainer, 2026-10-26): GitHub Sponsors and Ko-fi, both
 * the maintainer's personal pages - there is no company. Settings > About offers them on Community
 * only (SponsorSection.desktop.tsx); the landing site's footer names the same two addresses.
 */
export const COMMUNITY_GITHUB_SPONSORS_URL = 'https://github.com/sponsors/lesyhau'
export const COMMUNITY_KOFI_URL = 'https://ko-fi.com/lesyhau'

/** The SPDX identifier of the license Proxyma Community is under. */
export const COMMUNITY_LICENSE_SPDX = 'AGPL-3.0-only'

/** The license's own text, at its publisher. The same text is LICENSE in the source and the installs. */
export const COMMUNITY_LICENSE_URL = 'https://www.gnu.org/licenses/agpl-3.0.html'

/**
 * Where the source of the running version is:
 *   - `release`: a release's own source, the public repository's tag v<version>;
 *   - `development`: a development build, whose own source is not published - the link is the
 *     repository, where each release's is;
 *   - `unknown`: the version is not known yet - the repository.
 */
export interface CommunitySource {
  kind: 'release' | 'development' | 'unknown'
  url: string
}

/**
 * The source of the version this build says it is (GET /config's `version`).
 *
 * The public repository is EXPORTED from the private one, one commit per release, tagged
 * v<version> (tools/oss/publish.sh) - so a release's own source is that tag, not the repository's
 * root, which moves on with the next release. A development build (0.36.1-dev12, from every push to
 * dev) is never exported: its own source is not public, and the offer must not say it is.
 */
export function communitySource(version: string | null | undefined): CommunitySource {
  const v = version?.trim() ?? ''
  if (/^\d+\.\d+\.\d+$/.test(v)) return { kind: 'release', url: `${COMMUNITY_SOURCE_URL}/tree/v${v}` }
  return { kind: v === '' ? 'unknown' : 'development', url: COMMUNITY_SOURCE_URL }
}
