import { useToastDock } from '@/store/toastDockStore'

/** A page's place for toasts in its own flow - see `toastDockStore`. Registered while mounted. */
export function ToastDock({ className }: { className?: string }) {
  const ref = useToastDock<HTMLDivElement>()
  return <div ref={ref} className={className} />
}
