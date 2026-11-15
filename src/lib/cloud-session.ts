import { invoke } from '@tauri-apps/api/core'

const LEGACY_SESSION_STORAGE_KEY = 'session_token'

let invalidationHandler: (() => Promise<void>) | null = null
let invalidationPromise: Promise<void> | null = null
let sessionToken: string | null = null
let sessionLoaded = false
let sessionLoadPromise: Promise<string | null> | null = null
let sessionMutationVersion = 0
let sessionWriteChain: Promise<void> = Promise.resolve()
let pendingSessionWrites = 0

export function captureCloudSessionOwner(): () => boolean {
  const version = sessionMutationVersion
  const stableAtCapture = pendingSessionWrites === 0
  return () => stableAtCapture && pendingSessionWrites === 0 && version === sessionMutationVersion
}

export function enqueueCloudSessionOperation(
  action: () => Promise<void>,
  requiresCurrentSession = false,
): Promise<void> {
  const version = sessionMutationVersion
  const operation = sessionWriteChain.then(() => {
    if (requiresCurrentSession && version !== sessionMutationVersion) return
    return action()
  })
  sessionWriteChain = operation.catch(() => undefined)
  return operation
}

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
  const load = sessionWriteChain.then(async () => {
    if (sessionMutationVersion !== loadVersion) return sessionToken
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
  })
  sessionWriteChain = load.then(
    () => undefined,
    () => undefined,
  )
  sessionLoadPromise = load
  try {
    return await load
  } finally {
    if (sessionLoadPromise === load) sessionLoadPromise = null
  }
}

export async function persistSessionToken(
  token: string | null,
  isCurrentOwner: () => boolean = () => true,
  writeToken?: (token: string, isCurrentMutation: () => boolean) => Promise<void>,
): Promise<void> {
  if (!isCurrentOwner()) throw new Error('Session token write cancelled')
  const normalizedToken = token?.trim() ? token : null
  const mutationVersion = ++sessionMutationVersion
  pendingSessionWrites += 1
  const write = sessionWriteChain.then(async () => {
    if (!isCurrentOwner()) throw new Error('Session token write cancelled')
    if (normalizedToken && sessionMutationVersion !== mutationVersion) {
      throw new Error('Session token write superseded')
    }
    let capabilityError: unknown = null
    if (!normalizedToken) {
      try {
        await invoke('clear_managed_stt_capability')
      } catch (error) {
        capabilityError = error
      }
    }
    if (writeToken)
      await writeToken(normalizedToken ?? '', () => sessionMutationVersion === mutationVersion)
    else {
      try {
        await invoke('set_session_token', { token: normalizedToken ?? '' })
      } catch (error) {
        if (normalizedToken && sessionMutationVersion === mutationVersion) {
          const actual = await invoke<{ token: string | null; sessionEpoch: string }>(
            'get_cloud_session_snapshot',
          ).catch(() => undefined)
          if (
            sessionMutationVersion === mutationVersion &&
            (!actual ||
              actual.token !== sessionToken ||
              typeof actual.sessionEpoch !== 'string' ||
              !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
                actual.sessionEpoch,
              ))
          ) {
            clearSessionTokenFromMemory()
            throw new CloudSessionDisabledError()
          }
        }
        throw error
      }
    }

    if (sessionMutationVersion === mutationVersion) {
      sessionToken = normalizedToken
      sessionLoaded = true
      removeLegacySessionToken()
    }
    if (capabilityError) throw capabilityError
  })
  const trackedWrite = write.finally(() => {
    pendingSessionWrites -= 1
  })
  sessionWriteChain = trackedWrite.catch(() => undefined)
  return trackedWrite
}

export async function persistOwnedSessionToken(
  token: string,
  isCurrentOwner: () => boolean,
  signal?: AbortSignal,
): Promise<void> {
  await persistSessionToken(token, isCurrentOwner, async (candidate, isCurrentMutation) => {
    const snapshot = await invoke<{ token: string | null; sessionEpoch: string }>(
      'get_cloud_session_snapshot',
    )
    if (!isCurrentOwner()) throw new Error('Session token write cancelled')
    if (!isCurrentMutation()) throw new Error('Session token write superseded')
    if (
      !snapshot ||
      (snapshot.token !== null && (typeof snapshot.token !== 'string' || !snapshot.token.trim())) ||
      typeof snapshot.sessionEpoch !== 'string' ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
        snapshot.sessionEpoch,
      )
    ) {
      throw new Error('Invalid cloud session snapshot')
    }
    let attemptId: string | undefined
    if (signal) {
      signal.throwIfAborted()
      attemptId = await invoke<string>('begin_cloud_session_commit')
      if (
        typeof attemptId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(attemptId)
      ) {
        throw new Error('Invalid cloud session commit attempt')
      }
    }
    let cancellationRequested = false
    const cancelAttempt = () => {
      if (!attemptId || cancellationRequested) return
      cancellationRequested = true
      void invoke('cancel_cloud_session_commit', { attemptId }).catch(() => {
        console.error('Failed to cancel cloud session commit')
      })
    }
    let committed: { token: string | null; sessionEpoch: string }
    let submitted = false
    try {
      signal?.addEventListener('abort', cancelAttempt, { once: true })
      if (signal?.aborted || !isCurrentOwner() || !isCurrentMutation()) {
        throw new Error('Session token write cancelled')
      }
      submitted = true
      committed = await invoke<{ token: string | null; sessionEpoch: string }>(
        'set_cloud_session_snapshot',
        {
          token: candidate,
          expectedSessionEpoch: snapshot.sessionEpoch,
          ...(attemptId ? { attemptId } : {}),
        },
      )
      if (
        committed?.token !== candidate ||
        typeof committed.sessionEpoch !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
          committed.sessionEpoch,
        )
      ) {
        throw new Error('Invalid cloud session commit result')
      }
    } catch (error) {
      if (submitted && isCurrentMutation()) {
        const actual = await invoke<{ token: string | null; sessionEpoch: string }>(
          'get_cloud_session_snapshot',
        ).catch(() => null)
        if (
          isCurrentMutation() &&
          (actual === null ||
            actual.token !== snapshot.token ||
            typeof actual.sessionEpoch !== 'string' ||
            !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
              actual.sessionEpoch,
            )) &&
          snapshot.token !== null
        ) {
          clearSessionTokenFromMemory()
          throw new CloudSessionDisabledError()
        }
      }
      throw error
    } finally {
      signal?.removeEventListener('abort', cancelAttempt)
      cancelAttempt()
    }
  })
}

export class CloudSessionDisabledError extends Error {
  constructor() {
    super('Cloud session disabled after an unverified commit')
    this.name = 'CloudSessionDisabledError'
  }
}

export async function invalidateOwnedCloudSession(payload: unknown): Promise<void> {
  if (
    !payload ||
    typeof payload !== 'object' ||
    Array.isArray(payload) ||
    !('sessionEpoch' in payload) ||
    typeof payload.sessionEpoch !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
      payload.sessionEpoch,
    ) ||
    pendingSessionWrites !== 0
  ) {
    return
  }
  const isCurrentOwner = captureCloudSessionOwner()
  const token = await loadSessionToken()
  if (!isCurrentOwner() || pendingSessionWrites !== 0 || !token) return
  const snapshot = await invoke<{ token: string | null; sessionEpoch: string }>(
    'get_cloud_session_snapshot',
  )
  if (
    isCurrentOwner() &&
    pendingSessionWrites === 0 &&
    snapshot?.token === sessionToken &&
    snapshot?.sessionEpoch === payload.sessionEpoch
  ) {
    await invalidateCloudSessionOnce()
  }
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
  const cleanup = Promise.resolve(result)
    .then(() => undefined)
    .catch((error) => {
      if (invalidationPromise === cleanup) invalidationPromise = null
      throw error
    })
  invalidationPromise = cleanup
  return cleanup
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
  pendingSessionWrites = 0
}
