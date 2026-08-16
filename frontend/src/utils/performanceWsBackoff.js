const FAILURE_LIMIT = 3
const FAILURE_DECAY_MS = 60_000
const COOLDOWN_MS = 5 * 60_000

let failureCount = 0
let lastFailureAt = 0
let disabledUntil = 0

export function canAttemptPerformanceWs () {
  return Date.now() >= disabledUntil
}

export function notePerformanceWsOpen () {
  failureCount = 0
  lastFailureAt = 0
  disabledUntil = 0
}

export function notePerformanceWsFailure () {
  const now = Date.now()
  if (lastFailureAt && now - lastFailureAt > FAILURE_DECAY_MS) {
    failureCount = 0
  }
  lastFailureAt = now
  failureCount += 1
  if (failureCount >= FAILURE_LIMIT) {
    disabledUntil = now + COOLDOWN_MS
  }
  return {
    failureCount,
    disabled: disabledUntil > now,
    disabledUntil
  }
}

export function performanceWsCooldownMs () {
  return Math.max(0, disabledUntil - Date.now())
}
