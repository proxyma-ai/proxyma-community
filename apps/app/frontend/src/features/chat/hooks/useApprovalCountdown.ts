import { useEffect, useState } from 'react'

// Drives the live "4m 43s" countdown badge next to a pending tool-call approval's Accept/Accept
// All/Deny controls (see ToolApprovalPrompt). Built on a plain tick pattern: a
// 1s setInterval that recomputes remaining time from wall-clock `requestedAt` on every tick,
// rather than one setTimeout scheduled for the full duration (which would drift/misfire if the
// tab is backgrounded and throttled). `requestedAt` is the approval's own queued-at timestamp
// (set once in chatStore's addPendingApproval), not this hook's mount time, so a row reconstructed
// mid-flight still shows a correct remaining count instead of restarting from `totalSeconds`.
export function useApprovalCountdown(requestedAt: number, totalSeconds: number): number {
  const computeSecondsLeft = () =>
    Math.max(0, totalSeconds - Math.floor((Date.now() - requestedAt) / 1000))

  const [secondsLeft, setSecondsLeft] = useState(computeSecondsLeft)

  useEffect(() => {
    setSecondsLeft(computeSecondsLeft())
    const interval = setInterval(() => {
      setSecondsLeft(computeSecondsLeft())
    }, 1000)
    return () => clearInterval(interval)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestedAt, totalSeconds])

  return secondsLeft
}
