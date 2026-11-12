/**
 * THE MARK'S TURN, for the code that turns it frame by frame: 30 degrees a second, one turn in 12
 * seconds - the CSS token `--mark-turn` (proxyma-tokens.css), which the kit's `.mark-turn` class
 * and every other mark read. One speed everywhere (the maintainer, 2026-11-08: "the same speed
 * everywhere"). useIdleRotation turns at it, and the desktop launcher's splash
 * (SplashWindow.MARK_DEG_PER_MS) mirrors it; markTurn.test.ts holds the three to the token.
 */
export const IDLE_DEG_PER_S = 30
