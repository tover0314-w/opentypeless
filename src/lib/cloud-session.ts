import { invoke } from '@tauri-apps/api/core'

const LEGACY_SESSION_STORAGE_KEY = 'session_token'

let invalidationHandler: (() => Promise<void>) | null = null
let invalidationPromise: Promise<void> | null = null
let sessionToken: string | null = null
let sessionLoaded = false
let sessionLoadPromise: Promise<string | null> | null = null
let sessionMutationVersion = 0
let sessionWriteChain: Promise<void> = Promise.resolve()

function readLegacySessionToken(): string | null {
  try {
    const token = localStorage.getItem(LEGACY_SESSION_STORAGE_KEY)
    return token?.trim() ? token : null
  } catch {
    return null
  }
}

function removeLegacySessionToken(): void {
  try {
    localStorage.removeItem(LEGACY_SESSION_STORAGE_KEY)
  } catch {
    // Some test/webview contexts do not expose localStorage.
  }
}

export async function loadSessionToken(): Promise<string | null> {
  if (sessionLoaded) return sessionToken
  if (sessionLoadPromise) return sessionLoadPromise

  const loadVersion = sessionMutationVersion
  const load = (async () => {
    const legacyToken = readLegacySessionToken()
    const restoredToken = legacyToken
      ? await invoke<string | null>('set_session_token', { token: legacyToken }).then(
          () => legacyToken,
        )
      : await invoke<string | null>('get_session_token')
    const normalizedToken = restoredToken?.trim() ? restoredToken : null

    if (sessionMutationVersion === loadVersion) {
      sessionToken = normalizedToken
      sessionLoaded = true
      removeLegacySessionToken()
    }
    return sessionToken
  })()
  sessionLoadPromise = load
  try {
    return await load
  } finally {
    if (sessionLoadPromise === load) sessionLoadPromise = null
  }
}

export async function persistSessionToken(token: string | null): Promise<void> {
  const normalizedToken = token?.trim() ? token : null
  const mutationVersion = ++sessionMutationVersion
  const write = sessionWriteChain.then(async () => {
    let capabilityError: unknown = null
    if (!normalizedToken) {
      try {
        await invoke('clear_managed_stt_capability')
      } catch (error) {
        capabilityError = error
      }
    }
    await invoke('set_session_token', { token: normalizedToken ?? '' })

    if (sessionMutationVersion === mutationVersion) {
      sessionToken = normalizedToken
      sessionLoaded = true
      removeLegacySessionToken()
    }
    if (capabilityError) throw capabilityError
  })
  sessionWriteChain = write.catch(() => undefined)
  return write
}

export function clearSessionTokenFromMemory(): void {
  sessionMutationVersion += 1
  sessionToken = null
  sessionLoaded = true
  removeLegacySessionToken()
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
  sessionToken = null
  sessionLoaded = false
  sessionLoadPromise = null
  sessionMutationVersion = 0
  sessionWriteChain = Promise.resolve()
}
