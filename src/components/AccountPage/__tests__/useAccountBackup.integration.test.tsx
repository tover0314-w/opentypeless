import { act, cleanup, renderHook } from '@testing-library/react'
import { invoke } from '@tauri-apps/api/core'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from '../../../stores/appStore'
import { useAuthStore, type AuthUser } from '../../../stores/authStore'
import { downloadBackup, uploadBackup } from '../../../lib/api'
import { useAccountBackup } from '../useAccountBackup'
import nativeFixture from '../../../../src-tauri/tests/fixtures/backup_restore_response.json'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
vi.mock('../../../lib/api', () => ({ downloadBackup: vi.fn(), uploadBackup: vi.fn() }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('../../../stores/authStore', () => ({
  useAuthStore: Object.assign(vi.fn(), { getState: vi.fn() }),
}))

const owner = { id: 'synthetic-owner', email: 'user@example.invalid' } as AuthUser

beforeEach(() => {
  vi.resetAllMocks()
  useAppStore.setState(useAppStore.getInitialState())
  vi.mocked(useAuthStore).mockReturnValue(owner)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: owner } as ReturnType<
    typeof useAuthStore.getState
  >)
  vi.mocked(downloadBackup).mockResolvedValue({
    history: [],
    dictionary: { entries: [], correction_rules: [] },
  })
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === 'restore_backup_data' ? nativeFixture : null,
  )
})
afterEach(cleanup)

it('uploads one native aggregate snapshot rather than separately reading mutable stores', async () => {
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === 'get_cloud_backup_snapshot'
      ? { config: useAppStore.getState().config, ...nativeFixture }
      : null,
  )
  const { result } = renderHook(() => useAccountBackup())
  await act(async () => result.current.handleBackup())
  expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).toEqual([
    'get_cloud_backup_snapshot',
  ])
  expect(uploadBackup).toHaveBeenCalledWith(
    expect.objectContaining({
      history: nativeFixture.history.map((entry) => ({ ...entry, output_error: null })),
      dictionary: {
        entries: nativeFixture.dictionary,
        correction_rules: nativeFixture.correctionRules,
      },
    }),
  )
  expect(result.current.backupMsg).toBe('account.toast.backupOk')
})

it('does not start data restoration after malformed native preflight success', async () => {
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === 'validate_backup_data' ? true : nativeFixture,
  )
  const before = useAppStore.getState()
  const { result } = renderHook(() => useAccountBackup())
  await act(async () => result.current.handleRestore())
  expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).toEqual(['validate_backup_data'])
  expect(useAppStore.getState().history).toBe(before.history)
  expect(result.current.backupMsg).toBe('Invalid backup validation response')
})

it('applies the SQLite-produced response through real wrappers into all three stores', async () => {
  const { result } = renderHook(() => useAccountBackup())
  await act(async () => result.current.handleRestore())
  expect(useAppStore.getState().history).toEqual(nativeFixture.history)
  expect(useAppStore.getState().dictionary).toEqual(nativeFixture.dictionary)
  expect(useAppStore.getState().correctionRules).toEqual(nativeFixture.correctionRules)
  expect(result.current.backupMsg).toBe('account.toast.restoreOk')
  expect(result.current.backupLoading).toBe(false)
  expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).toEqual([
    'validate_backup_data',
    'restore_backup_data',
  ])
})

it('does not apply malformed native success and reports data restoration as unconfirmed', async () => {
  const before = useAppStore.getState()
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === 'restore_backup_data'
      ? { ...nativeFixture, dictionary: [{ ...nativeFixture.dictionary[0], token: 'secret' }] }
      : null,
  )
  const { result } = renderHook(() => useAccountBackup())
  await act(async () => result.current.handleRestore())
  expect(useAppStore.getState().history).toBe(before.history)
  expect(useAppStore.getState().dictionary).toBe(before.dictionary)
  expect(useAppStore.getState().correctionRules).toBe(before.correctionRules)
  expect(result.current.backupMsg).toContain('account.toast.restoreDataUnknown')
  expect(result.current.backupMsg).toContain('Invalid backup restoration response')
})

it('ignores a valid late response after the authenticated owner changes', async () => {
  let finish!: (response: typeof nativeFixture) => void
  vi.mocked(invoke).mockImplementation((command) =>
    command === 'restore_backup_data'
      ? new Promise((resolve) => (finish = resolve))
      : Promise.resolve(null),
  )
  const { result, rerender } = renderHook(() => useAccountBackup())
  let pending!: Promise<void>
  await act(async () => {
    pending = result.current.handleRestore()
    await Promise.resolve()
    await Promise.resolve()
  })
  expect(invoke).toHaveBeenCalledWith('restore_backup_data', expect.any(Object))
  const replacement = { ...owner, id: 'replacement-owner' }
  vi.mocked(useAuthStore).mockReturnValue(replacement)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
    typeof useAuthStore.getState
  >)
  rerender()
  const before = useAppStore.getState()
  await act(async () => {
    finish(nativeFixture)
    await pending
  })
  expect(useAppStore.getState().history).toBe(before.history)
  expect(useAppStore.getState().dictionary).toBe(before.dictionary)
  expect(result.current.backupMsg).toBeNull()
})
