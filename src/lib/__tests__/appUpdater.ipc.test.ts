import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearMocks, mockIPC } from '@tauri-apps/api/mocks'
import {
  __resetUpdaterForTests,
  checkForUpdates,
  installAndRestart,
  useUpdaterStore,
} from '../appUpdater'

vi.mock('../tauri', () => ({
  isDevelopmentBuild: vi.fn().mockResolvedValue(false),
  setTrayUpdateStatus: vi.fn().mockResolvedValue(undefined),
}))

beforeEach(__resetUpdaterForTests)
afterEach(() => {
  clearMocks()
  __resetUpdaterForTests()
})

describe('updater plugin IPC integration', () => {
  it('passes bounded check and download timeouts through the actual plugin IPC', async () => {
    const requests: Array<{ command: string; payload: unknown }> = []
    mockIPC(async (command, payload) => {
      requests.push({ command, payload })
      if (command === 'check_secure_update') {
        return { rid: 81, currentVersion: '1.1.64', version: '1.2.0', rawJson: {} }
      }
      if (command === 'plugin:updater|download') {
        return 82
      }
      throw new Error(`Unexpected IPC: ${command}`)
    })

    await expect(checkForUpdates()).resolves.toBe(true)
    expect(requests).toMatchObject([
      { command: 'check_secure_update', payload: {} },
      { command: 'plugin:updater|download', payload: { rid: 81, timeout: 300_000 } },
    ])
  })

  it('installs the downloaded byte resource once and retries only a failed restart', async () => {
    const calls: string[] = []
    let restartAttempts = 0
    mockIPC(async (command, payload) => {
      calls.push(command)
      if (command === 'check_secure_update') {
        return { rid: 51, currentVersion: '1.1.64', version: '1.2.0', rawJson: {} }
      }
      if (command === 'plugin:updater|download') {
        expect(payload).toMatchObject({ rid: 51 })
        return 52
      }
      if (command === 'plugin:updater|install') {
        expect(payload).toEqual({ updateRid: 51, bytesRid: 52 })
        return
      }
      if (command === 'plugin:resources|close') {
        expect(payload).toEqual({ rid: 51 })
        return
      }
      if (command === 'plugin:process|restart') {
        restartAttempts += 1
        if (restartAttempts === 1) throw new Error('restart unavailable')
        return
      }
      throw new Error(`Unexpected IPC: ${command}`)
    })

    await expect(checkForUpdates()).resolves.toBe(true)
    await expect(installAndRestart()).rejects.toThrow('restart unavailable')
    await expect(installAndRestart()).resolves.toBeUndefined()
    expect(calls).toEqual([
      'check_secure_update',
      'plugin:updater|download',
      'plugin:updater|install',
      'plugin:resources|close',
      'plugin:process|restart',
      'plugin:process|restart',
    ])
  })

  it.each([false, true])(
    'closes the failed update resource and preserves its error when close fails: %s',
    async (closeFails) => {
      let attempts = 0
      const calls: Array<{ command: string; resource?: number }> = []
      mockIPC(async (command, payload) => {
        const resource =
          payload && 'rid' in payload ? (payload.rid as number | undefined) : undefined
        calls.push({ command, resource })
        if (command === 'check_secure_update') {
          attempts += 1
          return {
            rid: attempts === 1 ? 41 : 42,
            currentVersion: '1.1.64',
            version: '1.2.0',
            rawJson: {},
          }
        }
        if (command === 'plugin:updater|download') {
          if (resource === 41) throw new Error('network download failed')
          return 43
        }
        if (command === 'plugin:resources|close') {
          if (closeFails) throw new Error('private cleanup detail')
          return
        }
        throw new Error(`Unexpected IPC: ${command}`)
      })

      await expect(checkForUpdates()).resolves.toBe(false)
      expect(calls).toEqual([
        { command: 'check_secure_update', resource: undefined },
        { command: 'plugin:updater|download', resource: 41 },
        { command: 'plugin:resources|close', resource: 41 },
      ])
      expect(useUpdaterStore.getState()).toMatchObject({
        phase: 'error',
        error: { kind: 'network', message: 'network download failed' },
      })
      await expect(installAndRestart()).rejects.toThrow('No downloaded update')

      await expect(checkForUpdates()).resolves.toBe(true)
      expect(useUpdaterStore.getState().phase).toBe('downloaded')
      expect(calls.slice(3)).toEqual([
        { command: 'check_secure_update', resource: undefined },
        { command: 'plugin:updater|download', resource: 42 },
      ])
    },
  )
})
