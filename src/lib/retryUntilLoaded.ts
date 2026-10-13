/**
 * Calls `load` until it resolves, doubling the wait between attempts up to
 * `maxDelayMs`, and hands the first result to `onLoaded`. An attempt that has
 * not settled after `attemptTimeoutMs` counts as failed, so a lost IPC reply
 * cannot stall loading forever. Returns a cancel function for effect cleanup.
 *
 * The capsule and Ask windows are created before the Rust setup hook registers
 * ConfigManager, so their first get_config can fail with "state not managed".
 * Without a retry the capsule keeps the default config (capsule_auto_hide: true)
 * and stays hidden while idle until the next restart.
 */
export function retryUntilLoaded<T>(
  load: () => Promise<T>,
  onLoaded: (value: T) => void,
  onError: (error: unknown) => void = () => {},
  { initialDelayMs = 200, maxDelayMs = 2000, attemptTimeoutMs = 3000 } = {},
): () => void {
  let finished = false
  let retryTimer: ReturnType<typeof setTimeout> | undefined

  const attempt = (delay: number) => {
    let settled = false
    const fail = (error: unknown) => {
      if (settled || finished) return
      settled = true
      onError(error)
      retryTimer = setTimeout(() => attempt(Math.min(delay * 2, maxDelayMs)), delay)
    }
    const timeout = setTimeout(
      () => fail(new Error(`no response within ${attemptTimeoutMs}ms`)),
      attemptTimeoutMs,
    )

    load()
      .then((value) => {
        clearTimeout(timeout)
        // A slow earlier attempt may still deliver after a retry was scheduled.
        if (finished) return
        finished = true
        settled = true
        if (retryTimer) clearTimeout(retryTimer)
        onLoaded(value)
      })
      .catch((error: unknown) => {
        clearTimeout(timeout)
        fail(error)
      })
  }

  attempt(initialDelayMs)

  return () => {
    finished = true
    if (retryTimer) clearTimeout(retryTimer)
  }
}
