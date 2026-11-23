import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useConfigSync } from '../useConfigSync'
import { useAppStore } from '../../stores/appStore'
import { getConfigCommit } from '../../lib/tauri'
import i18n from '../../i18n'

const listeners = vi.hoisted(() => new Map<string, (event: { payload: unknown }) => void>())
vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn(async (event: string, handler: (event: { payload: unknown }) => void) => {
    listeners.set(event, handler)
    return vi.fn<() => void>()
  }),
}))
vi.mock('../../lib/tauri', () => ({ getConfigCommit: vi.fn() }))
vi.mock('../../i18n', () => ({
  default: { language: 'en', changeLanguage: vi.fn().mockResolvedValue(undefined) },
}))

function commit(revision: number, ui_language = 'en') {
  return {
    configEpoch: '11111111-1111-4111-8111-111111111111',
    revision,
    saveId: null,
    config: {
      ...structuredClone(useAppStore.getInitialState().config),
      managed_stt_capability_state: null,
      ui_language,
    },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  listeners.clear()
  localStorage.clear()
  useAppStore.setState(useAppStore.getInitialState())
  vi.mocked(getConfigCommit).mockResolvedValue(commit(1))
})
afterEach(cleanup)

it('initializes from the versioned read and subscribes only to versioned commits', async () => {
  const { result } = renderHook(() => useConfigSync())
  await waitFor(() => expect(result.current).toBe('ready'))
  expect(useAppStore.getState().configCommit?.revision).toBe(1)
  expect(listeners.has('config:commit')).toBe(true)
  expect(listeners.has('config:patch')).toBe(false)
})

it('updates capsule visibility from a native commit without overwriting unrelated drafts', async () => {
  const { result } = renderHook(() => useConfigSync())
  await waitFor(() => expect(result.current).toBe('ready'))
  act(() => useAppStore.getState().updateConfig({ llm_model: 'synthetic-draft-model' }))
  const incoming = commit(2)
  incoming.config.capsule_auto_hide = !commit(1).config.capsule_auto_hide
  act(() => listeners.get('config:commit')?.({ payload: incoming }))
  expect(useAppStore.getState().config.capsule_auto_hide).toBe(incoming.config.capsule_auto_hide)
  expect(useAppStore.getState().savedConfig?.capsule_auto_hide).toBe(
    incoming.config.capsule_auto_hide,
  )
  expect(useAppStore.getState().config.llm_model).toBe('synthetic-draft-model')
})

it('keeps native configuration synchronization ready when the language cache cannot be written', async () => {
  const cache = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('Synthetic storage restriction', 'SecurityError')
  })
  const log = vi.spyOn(console, 'error').mockImplementation(() => {})
  try {
    const { result } = renderHook(() => useConfigSync())
    await waitFor(() => expect(result.current).toBe('ready'))
    act(() => listeners.get('config:commit')?.({ payload: commit(2, 'ja') }))
    expect(useAppStore.getState().configCommit?.revision).toBe(2)
    expect(i18n.changeLanguage).toHaveBeenLastCalledWith('ja')
    expect(log).toHaveBeenCalledWith('Failed to cache configuration language')
  } finally {
    cache.mockRestore()
    log.mockRestore()
  }
})

it('ignores stale language events and follows the merged draft language', async () => {
  const { result } = renderHook(() => useConfigSync())
  await waitFor(() => expect(result.current).toBe('ready'))
  act(() => useAppStore.getState().updateConfig({ ui_language: 'ja' }))
  act(() => listeners.get('config:commit')?.({ payload: commit(2, 'fr') }))
  expect(i18n.changeLanguage).toHaveBeenLastCalledWith('ja')
  expect(localStorage.getItem('ui_language')).toBe('ja')
  vi.mocked(i18n.changeLanguage).mockClear()
  act(() => listeners.get('config:commit')?.({ payload: commit(1, 'de') }))
  expect(i18n.changeLanguage).not.toHaveBeenCalled()
  expect(localStorage.getItem('ui_language')).toBe('ja')
})

it('keeps a language rerender from reconnecting or resetting its watermark', async () => {
  const { result, rerender } = renderHook(() => useConfigSync())
  await waitFor(() => expect(result.current).toBe('ready'))
  act(() => listeners.get('config:commit')?.({ payload: commit(2) }))
  rerender()
  expect(getConfigCommit).toHaveBeenCalledOnce()
  expect(useAppStore.getState().configCommit?.revision).toBe(2)
})

it('keeps listening when an initialization read is superseded by a newer same-process commit', async () => {
  useAppStore.getState().initializeConfigCommit(commit(1))
  let complete!: (value: ReturnType<typeof commit>) => void
  vi.mocked(getConfigCommit).mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    }),
  )
  const { result } = renderHook(() => useConfigSync())
  await waitFor(() => expect(getConfigCommit).toHaveBeenCalledOnce())
  act(() => useAppStore.getState().applyConfigCommit(commit(3, 'ja')))
  await act(async () => complete(commit(2)))
  await waitFor(() => expect(result.current).toBe('ready'))
  expect(useAppStore.getState().configCommit?.revision).toBe(3)
  act(() => listeners.get('config:commit')?.({ payload: commit(4, 'fr') }))
  expect(useAppStore.getState().configCommit?.revision).toBe(4)
  expect(localStorage.getItem('ui_language')).toBe('fr')
})

it('ignores read results after unmount without changing configuration or language', async () => {
  let complete!: (value: ReturnType<typeof commit>) => void
  vi.mocked(getConfigCommit).mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    }),
  )
  const { unmount } = renderHook(() => useConfigSync())
  await waitFor(() => expect(getConfigCommit).toHaveBeenCalledOnce())
  const signal = vi.mocked(getConfigCommit).mock.calls[0][0]
  expect(signal?.aborted).toBe(false)
  unmount()
  expect(signal?.aborted).toBe(true)
  await act(async () => complete(commit(2, 'ja')))
  expect(useAppStore.getState().configCommit).toBeNull()
  expect(i18n.changeLanguage).not.toHaveBeenCalled()
  expect(localStorage.getItem('ui_language')).toBeNull()
})

it('does not restore an old epoch when another handshake replaces it during initialization', async () => {
  useAppStore.getState().initializeConfigCommit(commit(1))
  let complete!: (value: ReturnType<typeof commit>) => void
  vi.mocked(getConfigCommit).mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    }),
  )
  const replacementEpoch = '22222222-2222-4222-8222-222222222222'
  const log = vi.spyOn(console, 'error').mockImplementation(() => {})
  try {
    const { result } = renderHook(() => useConfigSync())
    await waitFor(() => expect(getConfigCommit).toHaveBeenCalledOnce())
    act(() =>
      useAppStore.getState().initializeConfigCommit({
        ...commit(0, 'ja'),
        configEpoch: replacementEpoch,
      }),
    )
    await act(async () => complete(commit(99, 'de')))
    await waitFor(() => expect(result.current).toBe('error'))
    expect(useAppStore.getState().configCommit?.configEpoch).toBe(replacementEpoch)
    expect(useAppStore.getState().config.ui_language).toBe('ja')
    expect(localStorage.getItem('ui_language')).not.toBe('de')
  } finally {
    log.mockRestore()
  }
})

it('accepts a new epoch from an explicit read if no other handshake superseded that read', async () => {
  useAppStore.getState().initializeConfigCommit(commit(5))
  const replacementEpoch = '22222222-2222-4222-8222-222222222222'
  vi.mocked(getConfigCommit).mockResolvedValueOnce({
    ...commit(0, 'ja'),
    configEpoch: replacementEpoch,
  })
  const { result } = renderHook(() => useConfigSync())
  await waitFor(() => expect(result.current).toBe('ready'))
  expect(useAppStore.getState().configCommit?.configEpoch).toBe(replacementEpoch)
})

it('reports a fixed initialization error without accepting events afterwards', async () => {
  const log = vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.mocked(getConfigCommit).mockRejectedValueOnce(new Error('synthetic-private-detail'))
  try {
    const { result } = renderHook(() => useConfigSync())
    await waitFor(() => expect(result.current).toBe('error'))
    act(() => listeners.get('config:commit')?.({ payload: commit(2) }))
    expect(useAppStore.getState().configCommit).toBeNull()
    expect(log).toHaveBeenCalledExactlyOnceWith('Configuration synchronization failed: read')
  } finally {
    log.mockRestore()
  }
})

it('rejects a malformed older snapshot even if a newer watermark already exists', async () => {
  useAppStore.getState().initializeConfigCommit(commit(3))
  const malformed = commit(2)
  malformed.config.llm_api_key = 'synthetic-secret'
  vi.mocked(getConfigCommit).mockResolvedValueOnce(malformed)
  const log = vi.spyOn(console, 'error').mockImplementation(() => {})
  try {
    const { result } = renderHook(() => useConfigSync())
    await waitFor(() => expect(result.current).toBe('error'))
    expect(useAppStore.getState().configCommit?.revision).toBe(3)
    expect(log).toHaveBeenCalledExactlyOnceWith(
      'Configuration synchronization failed: initialization',
    )
  } finally {
    log.mockRestore()
  }
})
