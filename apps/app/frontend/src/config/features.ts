import { ENV } from './env'

export const FEATURES = {
  auth:        ENV.appMode === 'server',
  // `fileUpload` WAS HERE AND IS GONE, removed 2026-09-12. It read
  // `fileUpload: ENV.appMode === 'server'`, and it was true for exactly as long as "built as the
  // server edition" and "takes uploads" described the same deployments.
  //
  // The home server broke that. It is built from the DESKTOP bundle - the same application,
  // packaged for a machine with no screen - and it has to take uploads, because "pick a folder"
  // inside a container means a bind mount the operator has to get right before one document can be
  // indexed. One bundle, two answers, so the answer cannot be baked into the bundle.
  //
  // It lives on `/api/v1/config` now and is read through `useFileUpload()`. Same shape as the
  // `orgMode` removal below and for the same reason: a FEATURES map is the first place anyone looks
  // to answer "does this build do X", so it may only hold things a BUILD actually decides.
  // `orgMode` WAS HERE AND IS GONE, removed 2026-09-09. It read
  // `orgMode: ENV.appMode === 'desktop'` - which is backwards, since org mode is user management
  // and SSO and desktop has one user and no login - and it had no consumers at all, in either
  // sense: nothing gated on it, and nothing set it.
  //
  // Dead config is worse than no config when it reads as a fact. A `FEATURES` map is the first
  // place somebody looks to answer "does this build have org mode", and this entry answered
  // "yes, on desktop". The real answer is the backend's: `orgModeAllowed` on the license status
  // says whether the tier has accounts. (`orgMode` on /api/v1/config went on 2026-10-14 with the
  // setting behind it, which nothing read but the single-user Host check it switched off.)
  // SHARING IS A MULTI-USER IDEA and desktop has exactly one user. The share control, the
  // "shared"/"shared with you" badges and the "show shared sources from other users" toggle were
  // all rendered on desktop, where the only person who could ever see the result is the person
  // who pressed the button - so the toggle looked like it did nothing, because it did.
  //
  // Gated rather than deleted: the backend column, the PATCH and the read-only path all stay, and
  // server mode is unchanged. Desktop simply never offers it.
  sharing:     ENV.appMode === 'server',
  // Workflows are HIDDEN as of 0.1.1, in both editions. Not removed: the definitions on disk, the
  // engine and the run tables all stay, so turning them back on is this flag plus the backend's
  // `proxyma.features.workflows` - which must move with it, since that one gates the controllers
  // this UI would call and the catalogue the model is told about.
  //
  // Every surface below is gated on it: the Settings tab, the "/" list in the composer, "Create new
  // workflow" in the attach menu, and the step table in a thread.
  workflows:   false,
} as const

export type FeatureKey = keyof typeof FEATURES
