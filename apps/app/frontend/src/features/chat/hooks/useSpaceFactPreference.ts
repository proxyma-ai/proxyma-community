import { create } from 'zustand'

// Whether the new-chat screen shows the astronomy fact. OFF unless this viewer turned it on, since
// 2026-09-30 (the review's F27, approved by the maintainer): on the one screen whose job is to get
// a question typed, trivia under the greeting was a second thing to read, and on a work tool it
// read as decoration.
//
// OPT-IN, NOT REMOVED - deliberately the conservative half of the finding. The fact was the
// maintainer's own choice (it replaced topic suggestions, see SpaceFactLine in ChatPage) and
// SpaceFactService carries real work to keep its links honest, so all of it stays and one switch
// brings it back. While it is off the page never asks for a fact, and SpaceFactRefreshJob only
// generates while somebody is loading one - so off also means no background model call.
//
// Per viewer, in this browser: a presentation preference, not a deployment setting. Storage can
// throw (private windows, blocked site data), so every access is guarded and the default wins.
const KEY = 'proxyma-space-fact'

function read(): boolean {
  try { return localStorage.getItem(KEY) === 'on' } catch { return false }
}

interface SpaceFactPreference {
  enabled: boolean
  setEnabled: (on: boolean) => void
}

export const useSpaceFactPreference = create<SpaceFactPreference>(set => ({
  enabled: read(),
  setEnabled: on => {
    try { if (on) localStorage.setItem(KEY, 'on'); else localStorage.removeItem(KEY) } catch { /* not persisted */ }
    set({ enabled: on })
  },
}))
