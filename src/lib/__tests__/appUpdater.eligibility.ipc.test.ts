import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { clearMocks, mockIPC } from '@tauri-apps/api/mocks'
import { __resetUpdaterForTests, checkForUpdates, useUpdaterStore } from '../appUpdater'

beforeEach(__resetUpdaterForTests)
afterEach(() => {
  clearMocks()
  __resetUpdaterForTests()
})

describe('update eligibility through the actual Tauri JS wrapper', () => {
  it.each([
    { response: true, expectedKind: 'development' },
    ...[null, undefined, 0, '', 'false', {}, []].map((response) => ({
      response,
      expectedKind: 'other',
    })),
  ])('rejects an ineligible response: $response', async ({ response, expectedKind }) => {
    const commands: string[] = []
    mockIPC(async (command) => {
      commands.push(command)
      if (command === 'is_development_build') return response
      if (command === 'set_tray_update_status') return
      throw new Error(`Unexpected IPC: ${command}`)
    })

    await expect(checkForUpdates()).resolves.toBe(false)
    expect(commands).toEqual([
      'set_tray_update_status',
      'is_development_build',
      'set_tray_update_status',
    ])
    expect(useUpdaterStore.getState()).toMatchObject({
      phase: 'error',
      error: { kind: expectedKind },
    })
  })

  it('allows an explicit production response to reach the updater plugin', async () => {
    const commands: string[] = []
    mockIPC(async (command) => {
      commands.push(command)
      if (command === 'is_development_build') return false
      if (command === 'set_tray_update_status') return
      if (command === 'plugin:updater|check') return null
      throw new Error(`Unexpected IPC: ${command}`)
    })

    await expect(checkForUpdates()).resolves.toBe(false)
    expect(commands).toEqual([
      'set_tray_update_status',
      'is_development_build',
      'plugin:updater|check',
      'set_tray_update_status',
    ])
    expect(useUpdaterStore.getState().phase).toBe('up-to-date')
  })
})
