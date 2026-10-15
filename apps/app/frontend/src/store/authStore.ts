import { create } from 'zustand'

export interface AuthUser {
  id: string
  email: string
  displayName: string
  role: string
}

interface AuthState {
  user: AuthUser | null
  setUser: (u: AuthUser | null) => void
}

export const useAuthStore = create<AuthState>(set => ({
  user: null,
  setUser: user => set({ user }),
}))
