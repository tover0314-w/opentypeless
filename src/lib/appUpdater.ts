import { create } from 'zustand'
import { type DownloadEvent, type Update } from '@tauri-apps/plugin-updater'
import { checkSecureUpdate } from './secureUpdater'
import { relaunch } from '@tauri-apps/plugin-process'
import { emit } from '@tauri-apps/api/event'
import { isDevelopmentBuild, setTrayUpdateStatus } from './tauri'
import { UPDATE_READY_EVENT } from './appUpdaterEvents'

export type UpdatePhase =
  | 'idle'
  | 'checking'
  | 'downloading'
  | 'downloaded'
  | 'up-to-date'
  | 'error'

export type UpdateErrorKind = 'read_only' | 'development' | 'code_signing' | 'network' | 'other'

export interface UpdateError {
  kind: UpdateErrorKind
  message: string
}

interface UpdaterState {
  phase: UpdatePhase
  latestVersion: string | null
  /** 0–100, or null while the download size is unknown. */
  progress: number | null
  error: UpdateError | null
  /** A silently downloaded update waiting for the "Restart" notice. */
  notifyVersion: string | null
  dismissedVersion: string | null
}

const INITIAL: UpdaterState = {
  phase: 'idle',
  latestVersion: null,
  progress: null,
  error: null,
  notifyVersion: null,
  dismissedVersion: null,
}

export const useUpdaterStore = create<UpdaterState>(() => ({ ...INITIAL }))

export { UPDATE_INSTALL_EVENT, UPDATE_READY_EVENT } from './appUpdaterEvents'

const DEVELOPMENT_BUILD = 'LOCAL_UPDATE_BUNDLE_NOT_FOUND'

// Typeless matches substrings of electron-updater messages; the Tauri updater reports the same
// failures as io / minisign / reqwest text.
export function classifyUpdateError(raw: unknown): UpdateError {
  const message = raw instanceof Error ? raw.message : String(raw ?? '')
  const lower = message.toLowerCase()
  if (message === DEVELOPMENT_BUILD) return { kind: 'development', message }
  if (lower.includes('read-only') || lower.includes('read only file system')) {
    return { kind: 'read_only', message }
  }
  if (lower.includes('signature') || lower.includes('minisign')) {
    return { kind: 'code_signing', message }
  }
  if (
    lower.includes('error sending request') ||
    lower.includes('could not fetch a valid release') ||
    lower.includes('dns') ||
    lower.includes('timed out') ||
    lower.includes('connection') ||
    lower.includes('network')
  ) {
    return { kind: 'network', message }
  }
  return { kind: 'other', message }
}

let pending: Update | null = null
let run: Promise<boolean> | null = null
let installation: Promise<void> | null = null
let installedUpdate: Update | null = null

function set(patch: Partial<UpdaterState>) {
  useUpdaterStore.setState(patch)
  if (!patch.phase) return
  const phase = patch.phase
  const tray =
    phase === 'checking' || phase === 'downloading' || phase === 'downloaded' ? phase : 'idle'
  setTrayUpdateStatus(tray).catch(() => {})
}

async function checkAndDownload(): Promise<boolean> {
  set({ phase: 'checking', error: null, progress: null })
  const developmentBuild = await isDevelopmentBuild()
  if (typeof developmentBuild !== 'boolean') {
    throw new Error('Native build check returned an invalid response')
  }
  if (developmentBuild) throw new Error(DEVELOPMENT_BUILD)
  const update = await checkSecureUpdate()
  if (!update) {
    set({ phase: 'up-to-date', latestVersion: null })
    return false
  }
  let total = 0
  let received = 0
  let acceptingProgress = true
  set({ phase: 'downloading', latestVersion: update.version, progress: 0 })
  try {
    await update.download(
      (event: DownloadEvent) => {
        if (!acceptingProgress) return
        if (event.event === 'Started') total = event.data.contentLength ?? 0
        if (event.event === 'Progress') {
          received += event.data.chunkLength
          set({ progress: total > 0 ? Math.min(99, Math.round((received / total) * 100)) : null })
        }
      },
      { timeout: 300_000 },
    )
  } catch (error) {
    acceptingProgress = false
    await Promise.resolve()
      .then(() => update.close())
      .catch(() => {})
    throw error
  }
  acceptingProgress = false
  pending = update
  set({ phase: 'downloaded', progress: 100 })
  return true
}

function start(): Promise<boolean> {
  if (run) return run
  if (pending && useUpdaterStore.getState().phase === 'downloaded') return Promise.resolve(true)
  run = checkAndDownload()
    .catch((error) => {
      set({ phase: 'error', error: classifyUpdateError(error), progress: null })
      return false
    })
    .finally(() => {
      run = null
    })
  return run
}

/**
 * Foreground check (About, tray, check-for-updates). Joins a run already in flight, so closing
 * the dialog — Typeless "Cancel" — leaves the download going and reopening shows its progress.
 */
export function checkForUpdates(): Promise<boolean> {
  return start()
}

/** Launch / window-focus check. Stays quiet unless a download finishes. */
export async function checkAndDownloadSilently(): Promise<void> {
  if (run) return
  const downloaded = await start()
  const { phase, notifyVersion, dismissedVersion } = useUpdaterStore.getState()
  if (phase === 'error') console.warn('Background update check failed')
  if (!downloaded || !pending) return
  const version = pending.version
  if (notifyVersion === version || dismissedVersion === version) return
  set({ notifyVersion: version })
  if (!document.hasFocus()) emit(UPDATE_READY_EVENT, version).catch(() => {})
}

export function installAndRestart(): Promise<void> {
  if (installation) return installation
  if (!pending) return Promise.reject(new Error('No downloaded update'))
  const update = pending
  installation = (async () => {
    if (installedUpdate !== update) {
      await update.install()
      installedUpdate = update
      await Promise.resolve()
        .then(() => update.close())
        .catch(() => {})
    }
    await relaunch()
  })().finally(() => {
    installation = null
  })
  return installation
}

export function dismissUpdateNotice() {
  const { notifyVersion } = useUpdaterStore.getState()
  useUpdaterStore.setState({ notifyVersion: null, dismissedVersion: notifyVersion })
}

export function __resetUpdaterForTests() {
  installedUpdate = null
  installation = null
  run = null
  pending = null
  useUpdaterStore.setState({ ...INITIAL })
}
