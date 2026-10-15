import { FEATURES, type FeatureKey } from '@/config/features'

interface Props {
  feature: FeatureKey
  children: React.ReactNode
  fallback?: React.ReactNode
}

export function FeatureGuard({ feature, children, fallback = null }: Props) {
  return FEATURES[feature] ? <>{children}</> : <>{fallback}</>
}
