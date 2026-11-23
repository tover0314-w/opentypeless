import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from '../../../stores/appStore'
import { safeAlertWord, useCapsuleAlertStore } from '../../../stores/capsuleAlertStore'
import { Capsule } from '../index'
import { removeDictionaryEntry } from '../../../lib/tauri'
import { emit } from '@tauri-apps/api/event'

vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn().mockResolvedValue(() => {}),
  emit: vi.fn().mockResolvedValue(undefined),
  emitTo: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../../../lib/tauri', () => ({
  removeDictionaryEntry: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../../../hooks/useCapsuleResize', () => ({ useCapsuleResize: vi.fn() }))
vi.mock('../../../hooks/useCapsuleClickThrough', () => ({ useCapsuleClickThrough: vi.fn() }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))
vi.mock('@tauri-apps/plugin-clipboard-manager', () => ({ writeText: vi.fn() }))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) =>
      options ? `${key}:${Object.values(options).join(',')}` : key,
  }),
}))

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.clearAllMocks()
  useAppStore.setState(useAppStore.getInitialState())
  useCapsuleAlertStore.getState().dismiss()
})

describe('safeAlertWord', () => {
  it('keeps words up to 14 characters and shortens longer ones to 7…7 like Typeless', () => {
    expect(safeAlertWord('Kubernetes')).toBe('Kubernetes')
    expect(safeAlertWord('abcdefghijklmn')).toBe('abcdefghijklmn')
    expect(safeAlertWord('Supercalifragilistic')).toBe('Superca...ilistic')
    expect(safeAlertWord('一二三四五六七八九十甲乙丙丁戊')).toBe('一二三四五六七...九十甲乙丙丁戊')
  })
})

describe('dictionary auto-added capsule alert', () => {
  it('ignores an obsolete Undo event after another word replaces the reminder', () => {
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('dictionary_auto_added', 'OldWord', 12))
    const oldUndo = screen.getByRole('button', { name: 'capsule.alerts.undo' })
    act(() => {
      useCapsuleAlertStore.getState().show('dictionary_auto_added', 'NewWord', 13)
      fireEvent.click(oldUndo)
    })
    expect(removeDictionaryEntry).not.toHaveBeenCalled()
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({
      kind: 'dictionary_auto_added',
      entryId: 13,
    })
  })

  it('submits Undo once for two click events before busy state renders', async () => {
    let finishDelete: (() => void) | undefined
    vi.mocked(removeDictionaryEntry).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishDelete = resolve
        }),
    )
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('dictionary_auto_added', 'Term', 12))
    const undo = screen.getByRole('button', { name: 'capsule.alerts.undo' })
    act(() => {
      fireEvent.click(undo)
      fireEvent.click(undo)
    })
    expect(removeDictionaryEntry).toHaveBeenCalledOnce()
    await act(async () => finishDelete?.())
  })

  it('keeps a newer learned-word reminder when an older Undo completes', async () => {
    let finishDelete: (() => void) | undefined
    vi.mocked(removeDictionaryEntry).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishDelete = resolve
        }),
    )
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('dictionary_auto_added', 'OldWord', 12))
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.undo' }))
    act(() => useCapsuleAlertStore.getState().show('dictionary_auto_added', 'NewWord', 13))
    const newerId = useCapsuleAlertStore.getState().alert?.id
    await act(async () => finishDelete?.())
    expect(useCapsuleAlertStore.getState().alert?.id).toBe(newerId)
    expect(screen.getByText('capsule.alerts.dictionaryAutoAdded:NewWord')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'capsule.alerts.undo' })).toBeEnabled()
    expect(emit).toHaveBeenCalledWith('dictionary:changed')
  })

  it('names the word and hides after five seconds', async () => {
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('dictionary_auto_added', 'Jon Smyth', 9))
    expect(screen.getByText('capsule.alerts.dictionaryAutoAdded:Jon Smyth')).toBeTruthy()

    await act(async () => {
      vi.advanceTimersByTime(4999)
    })
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('dictionary_auto_added')
    await act(async () => {
      vi.advanceTimersByTime(1)
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('removes the learned word on Undo and confirms the deletion', async () => {
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('dictionary_auto_added', 'kubectl', 12))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.undo' }))
    })

    expect(removeDictionaryEntry).toHaveBeenCalledWith(12)
    expect(emit).toHaveBeenCalledWith('dictionary:changed')
    expect(screen.getByText('dictionary.deleteSuccess:kubectl')).toBeTruthy()
    await act(async () => {
      vi.advanceTimersByTime(2000)
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('keeps Undo available for retry when deleting the learned word fails', async () => {
    vi.mocked(removeDictionaryEntry).mockRejectedValueOnce(new Error('gone'))
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('dictionary_auto_added', 'kubectl', 12))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.undo' }))
    })

    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('dictionary_auto_added')
    expect(screen.getByRole('button', { name: 'capsule.alerts.undo' })).toBeEnabled()
    expect(emit).not.toHaveBeenCalledWith('dictionary:changed')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.undo' }))
    })
    expect(removeDictionaryEntry).toHaveBeenCalledTimes(2)
    expect(screen.getByText('dictionary.deleteSuccess:kubectl')).toBeTruthy()
    error.mockRestore()
  })
})
