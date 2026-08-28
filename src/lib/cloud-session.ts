import { invoke } from '@tauri-apps/api/core'

let invalidationHandler: (() => Promise<void>) | null = null
let invalidationPromise: Promise<void> | null = null

export async function persistSessionToken(token: string | null): Promise<void> {
  const previousToken = localStorage.getItem('session_token')
  if (token) localStorage.setItem('session_token', token)
  else localStorage.removeItem('session_token')

  try {
    if (!token) await invoke('clear_managed_stt_capability')
    await invoke('set_session_token', { token: token ?? '' })
  } catch (error) {
    if (previousToken) localStorage.setItem('session_token', previousToken)
    else localStorage.removeItem('session_token')
    throw error
  }
}

export function registerCloudSessionInvalidation(handler: () => Promise<void>): () => void {
  invalidationHandler = handler
  return () => {
    if (invalidationHandler === handler) invalidationHandler = null
  }
}

export function invalidateCloudSessionOnce(): Promise<void> {
  if (invalidationPromise) return invalidationPromise
  if (!invalidationHandler) return Promise.resolve()

  let result: Promise<void>
  try {
    result = invalidationHandler()
  } catch (error) {
    result = Promise.reject(error)
  }
  invalidationPromise = Promise.resolve(result)
    .then(() => undefined)
    .catch((error) => {
      invalidationPromise = null
      throw error
    })
  return invalidationPromise
}

export function markCloudSessionAuthenticated(): void {
  invalidationPromise = null
}

export function resetCloudSessionCoordinatorForTests(): void {
  invalidationHandler = null
  invalidationPromise = null
}
