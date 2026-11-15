import { loadSessionToken } from './cloud-session'

export async function loadSessionWithSignal(signal?: AbortSignal | null): Promise<string | null> {
  if (!signal) return loadSessionToken()
  signal.throwIfAborted()
  let onAbort: (() => void) | undefined
  const cancellation = new Promise<never>((_resolve, reject) => {
    onAbort = () => reject(signal.reason)
    signal.addEventListener('abort', onAbort, { once: true })
  })
  try {
    return await Promise.race([loadSessionToken(), cancellation])
  } finally {
    if (onAbort) signal.removeEventListener('abort', onAbort)
  }
}
