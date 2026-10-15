import { useMemo } from 'react'

export interface ValidationRule {
  id: string
  label: string
  test: (password: string, confirm: string) => boolean
}

const REGISTER_RULES: ValidationRule[] = [
  { id: 'length',  label: 'At least 8 characters',        test: p => p.length >= 8 },
  { id: 'upper',   label: 'At least 1 uppercase letter',  test: p => /[A-Z]/.test(p) },
  { id: 'number',  label: 'At least 1 number',            test: p => /[0-9]/.test(p) },
  { id: 'special', label: 'At least 1 special character',  test: p => /[^A-Za-z0-9]/.test(p) },
  { id: 'match',   label: 'Passwords match',              test: (p, c) => p.length > 0 && p === c },
]

const SETUP_RULES: ValidationRule[] = [
  { id: 'length',  label: 'At least 12 characters',       test: p => p.length >= 12 },
  { id: 'upper',   label: 'At least 1 uppercase letter',  test: p => /[A-Z]/.test(p) },
  { id: 'number',  label: 'At least 1 number',            test: p => /[0-9]/.test(p) },
  { id: 'special', label: 'At least 1 special character',  test: p => /[^A-Za-z0-9]/.test(p) },
  { id: 'match',   label: 'Passwords match',              test: (p, c) => p.length > 0 && p === c },
]

export function usePasswordValidation(
  password: string,
  confirm: string,
  mode: 'register' | 'setup' = 'register',
) {
  const rules = mode === 'setup' ? SETUP_RULES : REGISTER_RULES

  const results = useMemo(
    () => rules.map(r => ({ ...r, pass: r.test(password, confirm) })),
    [password, confirm, rules],
  )

  const allPass = results.every(r => r.pass)

  return { results, allPass }
}
