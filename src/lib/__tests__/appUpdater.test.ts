import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { mockCheck, mockRelaunch, mockEmit, mockIsDev, mockTray } = vi.hoisted(() => ({
  mockCheck: vi.fn(),
  mockRelaunch: vi.fn(),
  mockEmit: vi.fn(),
  mockIsDev: vi.fn(),
  mockTray: vi.fn(),
}))

vi.mock('@tauri-apps/plugin-updater', () => ({ check: mockCheck }))
vi.mock('@tauri-apps/plugin-process', () => ({ relaunch: mockRelaunch }))
vi.mock('@tauri-apps/api/event', () => ({ emit: mockEmit }))
vi.mock('../tauri', () => ({ isDevelopmentBuild: mockIsDev, setTrayUpdateStatus: mockTray }))

import {
  __resetUpdaterForTests,
  checkAndDownloadSilently,
  checkForUpdates,
  classifyUpdateError,
  dismissUpdateNotice,
  installAndRestart,
  useUpdaterStore,
} from '../appUpdater'
import { UPDATE_READY_EVENT } from '../appUpdaterEvents'

type Listener = (event: { event: string; data: Record<string, number> }) => void

function fakeUpdate(version = '1.2.0', chunks = [40, 60]) {
  return {
    version,
    download: vi.fn(async (onEvent: Listener) => {
      onEvent({ event: 'Started', data: { contentLength: 100 } })
      for (const chunkLength of chunks) onEvent({ event: 'Progress', data: { chunkLength } })
      onEvent({ event: 'Finished', data: {} })
    }),
    install: vi.fn().mockResolvedValue(undefined),
  }
}

beforeEach(() => {
  __resetUpdaterForTests()
  mockIsDev.mockResolvedValue(false)
  mockTray.mockResolvedValue(undefined)
  mockEmit.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

describe('classifyUpdateError', () => {
  it.each([
    ['LOCAL_UPDATE_BUNDLE_NOT_FOUND', 'development'],
    ['Read-only file system (os error 30)', 'read_only'],
    ['the signature verification failed', 'code_signing'],
    ['minisign error: invalid key', 'code_signing'],
    ['error sending request for url', 'network'],
    ['Could not fetch a valid release JSON from the remote', 'network'],
    ['operation timed out', 'network'],
    ['something odd', 'other'],
  ])('maps %s to %s', (message, kind) => {
    expect(classifyUpdateError(new Error(message)).kind).toBe(kind)
  })
})

describe('checkForUpdates', () => {
  it('does not let a failed download callback change the retry download progress', async () => {
    let retained!: Listener
    let retryProgress!: Listener
    let finishRetry!: () => void
    const failed = { ...fakeUpdate(), close: vi.fn().mockResolvedValue(undefined) }
    failed.download.mockImplementationOnce(async (listener) => {
      retained = listener
      listener({ event: 'Started', data: { contentLength: 100 } })
      throw new Error('network download failed')
    })
    mockCheck.mockResolvedValueOnce(failed)
    await expect(checkForUpdates()).resolves.toBe(false)

    const retry = fakeUpdate('1.3.0')
    retry.download.mockImplementationOnce(
      (listener) =>
        new Promise<void>((resolve) => {
          retryProgress = listener
          finishRetry = resolve
          listener({ event: 'Started', data: { contentLength: 200 } })
          listener({ event: 'Progress', data: { chunkLength: 50 } })
        }),
    )
    mockCheck.mockResolvedValueOnce(retry)
    const request = checkForUpdates()
    await vi.waitFor(() => expect(useUpdaterStore.getState().progress).toBe(25))
    retained({ event: 'Progress', data: { chunkLength: 80 } })
    expect(useUpdaterStore.getState()).toMatchObject({
      phase: 'downloading',
      latestVersion: '1.3.0',
      progress: 25,
    })
    retryProgress({ event: 'Progress', data: { chunkLength: 50 } })
    expect(useUpdaterStore.getState().progress).toBe(50)
    finishRetry()
    await expect(request).resolves.toBe(true)
    expect(useUpdaterStore.getState().progress).toBe(100)
  })

  it.each(['success', 'failure'] as const)(
    'ignores retained progress callbacks after download %s',
    async (outcome) => {
      let retained!: Listener
      const update = {
        ...fakeUpdate(),
        close: vi.fn().mockResolvedValue(undefined),
      }
      update.download.mockImplementationOnce(async (listener) => {
        retained = listener
        listener({ event: 'Started', data: { contentLength: 100 } })
        listener({ event: 'Progress', data: { chunkLength: 20 } })
        if (outcome === 'failure') throw new Error('network download failed')
      })
      mockCheck.mockResolvedValueOnce(update)
      await expect(checkForUpdates()).resolves.toBe(outcome === 'success')
      const settled = useUpdaterStore.getState()
      retained({ event: 'Progress', data: { chunkLength: 50 } })
      expect(useUpdaterStore.getState()).toEqual(settled)
    },
  )

  it.each(['reject', 'throw'] as const)(
    'preserves the download error when resource cleanup fails with %s',
    async (failure) => {
      const close = vi.fn(() => {
        if (failure === 'throw') throw new Error('private cleanup detail')
        return Promise.reject(new Error('private cleanup detail'))
      })
      const update = { ...fakeUpdate(), close }
      update.download.mockRejectedValueOnce(new Error('network download failed'))
      mockCheck.mockResolvedValueOnce(update)

      await expect(checkForUpdates()).resolves.toBe(false)

      expect(close).toHaveBeenCalledOnce()
      expect(useUpdaterStore.getState()).toMatchObject({
        phase: 'error',
        error: { kind: 'network', message: 'network download failed' },
      })
      await expect(installAndRestart()).rejects.toThrow('No downloaded update')
      expect(update.install).not.toHaveBeenCalled()
      expect(mockRelaunch).not.toHaveBeenCalled()

      mockCheck.mockResolvedValueOnce(fakeUpdate())
      await expect(checkForUpdates()).resolves.toBe(true)
    },
  )

  it('releases a failed download resource before retrying the update check', async () => {
    const failed = fakeUpdate()
    const close = vi.fn().mockResolvedValue(undefined)
    const update = { ...failed, close }
    update.download.mockRejectedValueOnce(new Error('network download failed'))
    mockCheck.mockResolvedValueOnce(update)

    await expect(checkForUpdates()).resolves.toBe(false)

    expect(close).toHaveBeenCalledOnce()
    expect(useUpdaterStore.getState()).toMatchObject({
      phase: 'error',
      error: { kind: 'network', message: 'network download failed' },
    })
    await expect(installAndRestart()).rejects.toThrow('No downloaded update')
    expect(update.install).not.toHaveBeenCalled()
    expect(mockRelaunch).not.toHaveBeenCalled()

    const retry = fakeUpdate()
    mockCheck.mockResolvedValueOnce(retry)
    await expect(checkForUpdates()).resolves.toBe(true)
    expect(retry.download).toHaveBeenCalledOnce()
    expect(close).toHaveBeenCalledOnce()
  })

  it('reports up to date when the feed has nothing newer', async () => {
    mockCheck.mockResolvedValueOnce(null)
    await expect(checkForUpdates()).resolves.toBe(false)
    expect(useUpdaterStore.getState().phase).toBe('up-to-date')
    expect(mockTray).toHaveBeenLastCalledWith('idle')
  })

  it('downloads with progress and mirrors status to the tray', async () => {
    const update = fakeUpdate()
    const seen: (number | null)[] = []
    const unsubscribe = useUpdaterStore.subscribe((s) => seen.push(s.progress))
    mockCheck.mockResolvedValueOnce(update)

    await expect(checkForUpdates()).resolves.toBe(true)
    unsubscribe()

    expect(useUpdaterStore.getState()).toMatchObject({
      phase: 'downloaded',
      latestVersion: '1.2.0',
      progress: 100,
    })
    expect(seen).toContain(40)
    expect(seen).toContain(99)
    expect(mockTray.mock.calls.map(([status]) => status)).toEqual([
      'checking',
      'downloading',
      'downloaded',
    ])
  })

  it('joins a check already in flight instead of starting another', async () => {
    let resolve: (value: null) => void = () => {}
    mockCheck.mockReturnValueOnce(new Promise((r) => (resolve = r)))
    const first = checkForUpdates()
    const second = checkForUpdates()
    resolve(null)
    await Promise.all([first, second])
    expect(mockCheck).toHaveBeenCalledTimes(1)
  })

  it('refuses to self-update a development build', async () => {
    mockIsDev.mockResolvedValueOnce(true)
    await expect(checkForUpdates()).resolves.toBe(false)
    expect(mockCheck).not.toHaveBeenCalled()
    expect(useUpdaterStore.getState().error?.kind).toBe('development')
  })

  it('does not contact the updater when build eligibility cannot be determined', async () => {
    mockIsDev.mockRejectedValueOnce(new Error('Native build check unavailable'))
    await expect(checkForUpdates()).resolves.toBe(false)
    expect(mockCheck).not.toHaveBeenCalled()
    expect(useUpdaterStore.getState()).toMatchObject({
      phase: 'error',
      error: { message: 'Native build check unavailable' },
    })
  })

  it.each([null, undefined, 0, '', 'false', {}, []].map((response) => ({ response })))(
    'does not contact the updater for a malformed build eligibility response: $response',
    async ({ response }) => {
      mockIsDev.mockResolvedValueOnce(response)
      await expect(checkForUpdates()).resolves.toBe(false)
      expect(mockCheck).not.toHaveBeenCalled()
      expect(useUpdaterStore.getState()).toMatchObject({
        phase: 'error',
        error: { kind: 'other', message: 'Native build check returned an invalid response' },
      })
    },
  )

  it('records a classified error', async () => {
    mockCheck.mockRejectedValueOnce(new Error('error sending request'))
    await checkForUpdates()
    expect(useUpdaterStore.getState()).toMatchObject({
      phase: 'error',
      error: { kind: 'network' },
    })
  })
})

describe('checkAndDownloadSilently', () => {
  it('raises the notice once per downloaded version and tells the capsule when unfocused', async () => {
    vi.spyOn(document, 'hasFocus').mockReturnValue(false)
    mockCheck.mockResolvedValueOnce(fakeUpdate('1.3.0'))

    await checkAndDownloadSilently()
    expect(useUpdaterStore.getState().notifyVersion).toBe('1.3.0')
    expect(mockEmit).toHaveBeenCalledWith(UPDATE_READY_EVENT, '1.3.0')

    await checkAndDownloadSilently()
    expect(mockEmit).toHaveBeenCalledTimes(1)
    expect(mockCheck).toHaveBeenCalledTimes(1)
  })

  it('keeps the capsule quiet while the hub is focused', async () => {
    vi.spyOn(document, 'hasFocus').mockReturnValue(true)
    mockCheck.mockResolvedValueOnce(fakeUpdate('1.3.0'))
    await checkAndDownloadSilently()
    expect(useUpdaterStore.getState().notifyVersion).toBe('1.3.0')
    expect(mockEmit).not.toHaveBeenCalled()
  })

  it('does not re-raise a dismissed version', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    mockCheck.mockResolvedValueOnce(fakeUpdate('1.3.0'))
    await checkAndDownloadSilently()
    dismissUpdateNotice()
    expect(useUpdaterStore.getState()).toMatchObject({
      notifyVersion: null,
      dismissedVersion: '1.3.0',
    })
  })
})

describe('installAndRestart', () => {
  it.each(['resolve', 'reject', 'throw'] as const)(
    'restarts after successful installation when resource close will %s',
    async (outcome) => {
      const close = vi.fn(() => {
        if (outcome === 'throw') throw new Error('cleanup failed')
        if (outcome === 'reject') return Promise.reject(new Error('cleanup failed'))
        return Promise.resolve()
      })
      const update = { ...fakeUpdate(), close }
      mockCheck.mockResolvedValueOnce(update)
      mockRelaunch.mockRejectedValueOnce(new Error('relaunch unavailable'))
      await checkForUpdates()

      await expect(installAndRestart()).rejects.toThrow('relaunch unavailable')
      expect(close).toHaveBeenCalledOnce()
      await expect(installAndRestart()).resolves.toBeUndefined()
      expect(update.install).toHaveBeenCalledOnce()
      expect(close).toHaveBeenCalledOnce()
      expect(mockRelaunch).toHaveBeenCalledTimes(2)
    },
  )

  it('keeps an unsuccessful installation resource available until retry succeeds', async () => {
    const close = vi.fn().mockResolvedValue(undefined)
    const update = { ...fakeUpdate(), close }
    update.install.mockRejectedValueOnce(new Error('installation failed'))
    mockCheck.mockResolvedValueOnce(update)
    await checkForUpdates()

    await expect(installAndRestart()).rejects.toThrow('installation failed')
    expect(close).not.toHaveBeenCalled()
    expect(mockRelaunch).not.toHaveBeenCalled()
    await expect(installAndRestart()).resolves.toBeUndefined()
    expect(update.install).toHaveBeenCalledTimes(2)
    expect(close).toHaveBeenCalledOnce()
    expect(mockRelaunch).toHaveBeenCalledOnce()
  })

  it('retries only relaunch after installation succeeded but relaunch failed', async () => {
    const update = fakeUpdate()
    mockCheck.mockResolvedValueOnce(update)
    mockRelaunch.mockRejectedValueOnce(new Error('relaunch unavailable'))
    await checkForUpdates()
    await expect(installAndRestart()).rejects.toThrow('relaunch unavailable')
    await installAndRestart()
    expect(update.install).toHaveBeenCalledTimes(1)
    expect(mockRelaunch).toHaveBeenCalledTimes(2)
  })

  it('joins concurrent installation requests and relaunches only once', async () => {
    let finishInstall: () => void = () => {}
    const update = fakeUpdate()
    update.install.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishInstall = resolve
      }),
    )
    mockCheck.mockResolvedValueOnce(update)
    await checkForUpdates()
    const first = installAndRestart()
    const second = installAndRestart()
    expect(update.install).toHaveBeenCalledTimes(1)
    finishInstall()
    await Promise.all([first, second])
    expect(mockRelaunch).toHaveBeenCalledTimes(1)
  })

  it('allows retry after installation fails without relaunching the failed attempt', async () => {
    const update = fakeUpdate()
    update.install.mockRejectedValueOnce(new Error('installation failed'))
    mockCheck.mockResolvedValueOnce(update)
    await checkForUpdates()
    await expect(installAndRestart()).rejects.toThrow('installation failed')
    expect(mockRelaunch).not.toHaveBeenCalled()
    await installAndRestart()
    expect(update.install).toHaveBeenCalledTimes(2)
    expect(mockRelaunch).toHaveBeenCalledTimes(1)
  })

  it('installs the downloaded bundle then relaunches', async () => {
    const update = fakeUpdate()
    mockCheck.mockResolvedValueOnce(update)
    await checkForUpdates()
    await installAndRestart()
    expect(update.install).toHaveBeenCalled()
    expect(mockRelaunch).toHaveBeenCalled()
  })

  it('fails when nothing has been downloaded', async () => {
    await expect(installAndRestart()).rejects.toThrow('No downloaded update')
  })
})
