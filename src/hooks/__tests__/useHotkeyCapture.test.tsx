import { act, cleanup, fireEvent, render, renderHook } from '@testing-library/react'
import { useLayoutEffect } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { pauseHotkey, resumeHotkey } from '../../lib/tauri'
import { queueShortcutCaptureSave, useHotkeyCapture } from '../useHotkeyCapture'

vi.mock('../../lib/tauri', () => ({
  pauseHotkey: vi.fn().mockResolvedValue(undefined),
  resumeHotkey: vi.fn().mockResolvedValue(undefined),
}))

afterEach(async () => {
  fireEvent.keyUp(window, { key: '1', code: 'Digit1' })
  fireEvent.keyUp(window, { key: '!', code: 'Digit1' })
  fireEvent.keyUp(window, { key: 'k' })
  fireEvent.keyUp(window, { key: 'Control' })
  fireEvent.keyUp(window, { key: 'Shift' })
  await act(async () => cleanup())
  vi.clearAllMocks()
})

describe('hotkey capture ownership', () => {
  it('keeps a replacement editor pending until its own native pause is acknowledged', async () => {
    let finishFirst!: () => void
    let finishSecond!: () => void
    vi.mocked(pauseHotkey)
      .mockReturnValueOnce(
        new Promise<void>((resolve) => {
          finishFirst = resolve
        }),
      )
      .mockReturnValueOnce(
        new Promise<void>((resolve) => {
          finishSecond = resolve
        }),
      )
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    first.unmount()
    const second = renderHook(() => useHotkeyCapture(true))
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      await act(async () => finishFirst())
      expect(second.result.current).toBe('pending')
      const save = vi.fn().mockResolvedValue('saved')
      const pendingSave = queueShortcutCaptureSave(save)
      await act(async () => finishSecond())
      await expect(pendingSave).resolves.toBe('saved')
      expect(second.result.current).toBe('ready')
    } finally {
      await act(async () => second.unmount())
      warning.mockRestore()
    }
  })

  it('rejects a save after the editor closes even while release keeps native capture paused', async () => {
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    const editor = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK' })
    await act(async () => editor.unmount())
    const save = vi.fn().mockResolvedValue('saved')
    try {
      await expect(queueShortcutCaptureSave(save)).rejects.toThrow('Shortcut capture is not ready')
      expect(save).not.toHaveBeenCalled()
    } finally {
      fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
      await act(async () => {})
      warning.mockRestore()
    }
  })

  it('rejects an old queued save after another editor acquires a new identity', async () => {
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    let finishSave!: () => void
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    const blockingSave = queueShortcutCaptureSave(
      () =>
        new Promise<void>((resolve) => {
          finishSave = resolve
        }),
    )
    await act(async () => {})
    const staleSave = vi.fn().mockResolvedValue('old binding')
    const staleResult = queueShortcutCaptureSave(staleSave).then(
      () => 'saved',
      () => 'rejected',
    )
    first.unmount()
    const second = renderHook(() => useHotkeyCapture(true))
    try {
      await act(async () => finishSave())
      await blockingSave
      expect(await staleResult).toBe('rejected')
      expect(staleSave).not.toHaveBeenCalled()
    } finally {
      await act(async () => second.unmount())
      warning.mockRestore()
    }
  })

  it('does not attribute a delayed pause to the next editor identity', async () => {
    let finishPause!: () => void
    vi.mocked(pauseHotkey).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishPause = resolve
      }),
    )
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    const firstIdentity = vi.mocked(pauseHotkey).mock.calls[0][0]
    first.unmount()
    const second = renderHook(() => useHotkeyCapture(true))
    try {
      await act(async () => finishPause())
      expect(pauseHotkey).toHaveBeenCalledTimes(2)
      expect(vi.mocked(pauseHotkey).mock.calls[1][0]).not.toBe(firstIdentity)
      expect(second.result.current).toBe('ready')
    } finally {
      await act(async () => second.unmount())
    }
  })

  it('claims a new native identity when an editor reopens before queued recovery starts', async () => {
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    const firstIdentity = vi.mocked(pauseHotkey).mock.calls.slice(-1)[0]?.[0]
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK' })
    let second: ReturnType<typeof renderHook> | undefined
    await act(async () => {
      first.unmount()
      second = renderHook(() => useHotkeyCapture(true))
    })
    expect(pauseHotkey).toHaveBeenCalledTimes(2)
    const secondIdentity = vi.mocked(pauseHotkey).mock.calls.slice(-1)[0]?.[0]
    expect(secondIdentity).not.toBe(firstIdentity)
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
    await act(async () => second?.unmount())
    expect(resumeHotkey).toHaveBeenLastCalledWith(secondIdentity)
  })

  it('uses one identity per capture lifetime and rotates it for the next editor', async () => {
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    const firstIdentity = vi.mocked(pauseHotkey).mock.calls[0][0]
    expect(firstIdentity).toMatch(/^[0-9a-f-]{36}$/i)
    await act(async () => first.unmount())
    expect(resumeHotkey).toHaveBeenLastCalledWith(firstIdentity)
    const second = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    const secondIdentity = vi.mocked(pauseHotkey).mock.calls.slice(-1)[0]?.[0]
    expect(secondIdentity).not.toBe(firstIdentity)
    await act(async () => second.unmount())
    expect(resumeHotkey).toHaveBeenLastCalledWith(secondIdentity)
  })

  it('retries recovery on focus after a failed resume without opening another editor', async () => {
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(resumeHotkey).mockRejectedValueOnce(new Error('Resume rejected'))
    try {
      const editor = renderHook(() => useHotkeyCapture(true))
      await act(async () => {})
      await act(async () => editor.unmount())
      expect(resumeHotkey).toHaveBeenCalledOnce()
      await act(async () => window.dispatchEvent(new Event('focus')))
      expect(resumeHotkey).toHaveBeenCalledTimes(2)
    } finally {
      warning.mockRestore()
    }
  })

  it('allows a new save after rejection and stays paused until the editor closes', async () => {
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    const editor = renderHook(() => useHotkeyCapture(true))
    const firstFailure = new Error('Synthetic first save failure')
    const successfulSave = vi.fn().mockResolvedValue('saved')
    try {
      await act(async () => {})
      await expect(queueShortcutCaptureSave(() => Promise.reject(firstFailure))).rejects.toBe(
        firstFailure,
      )
      expect(resumeHotkey).not.toHaveBeenCalled()
      await expect(queueShortcutCaptureSave(successfulSave)).resolves.toBe('saved')
      expect(successfulSave).toHaveBeenCalledOnce()
      expect(resumeHotkey).not.toHaveBeenCalled()
      await act(async () => editor.unmount())
      expect(resumeHotkey).toHaveBeenCalledOnce()
    } finally {
      editor.unmount()
      warning.mockRestore()
    }
  })

  it('finishes a pending save before resuming after the last editor closes', async () => {
    let finishSave!: () => void
    const editor = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    const pending = queueShortcutCaptureSave(
      () =>
        new Promise<void>((resolve) => {
          finishSave = resolve
        }),
    )
    await act(async () => {})
    expect(finishSave).toBeTypeOf('function')
    await act(async () => editor.unmount())
    expect(resumeHotkey).not.toHaveBeenCalled()
    await act(async () => finishSave())
    await pending
    expect(resumeHotkey).toHaveBeenCalledOnce()
  })

  it('does not log private native pause failure details', async () => {
    const failure = new Error('synthetic-private-native-detail')
    vi.mocked(pauseHotkey).mockRejectedValueOnce(failure)
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const editor = renderHook(() => useHotkeyCapture(true))
      await act(async () => {})
      expect(editor.result.current).toBe('error')
      expect(warning).toHaveBeenCalledWith('Failed to synchronize shortcut capture')
      expect(JSON.stringify(warning.mock.calls)).not.toContain(failure.message)
      await act(async () => editor.unmount())
      expect(resumeHotkey).toHaveBeenCalledOnce()
    } finally {
      warning.mockRestore()
    }
  })

  it('preserves a save rejection without logging its private details', async () => {
    const failure = new Error('synthetic-private-save-detail')
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    const editor = renderHook(() => useHotkeyCapture(true))
    try {
      await act(async () => {})
      await expect(queueShortcutCaptureSave(() => Promise.reject(failure))).rejects.toBe(failure)
      expect(warning).toHaveBeenCalledWith('Failed to save captured shortcut')
      expect(JSON.stringify(warning.mock.calls)).not.toContain(failure.message)
    } finally {
      await act(async () => editor.unmount())
      warning.mockRestore()
    }
  })

  it('repauses when a key is pressed while resume is still pending', async () => {
    let finishResume: () => void = () => {}
    vi.mocked(resumeHotkey).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishResume = resolve
      }),
    )
    const editor = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    editor.unmount()
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true })
    await act(async () => finishResume())
    expect(pauseHotkey).toHaveBeenCalledTimes(2)
    expect(resumeHotkey).toHaveBeenCalledOnce()
    fireEvent.keyUp(window, { key: 'k', ctrlKey: true })
    await act(async () => {})
    expect(resumeHotkey).toHaveBeenCalledOnce()
    fireEvent.keyUp(window, { key: 'Control' })
    await act(async () => {})
    expect(resumeHotkey).toHaveBeenCalledTimes(2)
  })

  it('does not save when native capture pause has failed', async () => {
    vi.mocked(pauseHotkey).mockRejectedValueOnce(new Error('Pause rejected'))
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const editor = renderHook(() => useHotkeyCapture(true))
      await act(async () => {})
      const save = vi.fn().mockResolvedValue(undefined)
      await expect(queueShortcutCaptureSave(save)).rejects.toThrow()
      expect(save).not.toHaveBeenCalled()
      await act(async () => editor.unmount())
    } finally {
      warning.mockRestore()
    }
  })

  it.each([
    { pressed: '!', released: '1', code: 'Digit1' },
    { pressed: '|', released: '\\', code: 'Backslash' },
    { pressed: '"', released: "'", code: 'Quote' },
    { pressed: '~', released: '`', code: 'Backquote' },
  ])(
    'drains physical $code after its Shift-modified character changes',
    async ({ pressed, released, code }) => {
      const editor = renderHook(() => useHotkeyCapture(true))
      await act(async () => {})
      fireEvent.keyDown(window, { key: pressed, code, shiftKey: true })
      await act(async () => editor.unmount())
      expect(resumeHotkey).not.toHaveBeenCalled()
      fireEvent.keyUp(window, { key: 'Shift' })
      await act(async () => {})
      expect(resumeHotkey).not.toHaveBeenCalled()
      fireEvent.keyUp(window, { key: released, code })
      await act(async () => {})
      expect(resumeHotkey).toHaveBeenCalledOnce()
    },
  )

  it('preserves the new editor key tracking when an older resume completes', async () => {
    let finishResume: () => void = () => {}
    vi.mocked(resumeHotkey).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishResume = resolve
      }),
    )
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    first.unmount()
    await act(async () => {})
    expect(resumeHotkey).toHaveBeenCalledOnce()

    const second = renderHook(() => useHotkeyCapture(true))
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true })
    await act(async () => finishResume())
    expect(second.result.current).toBe('ready')
    expect(pauseHotkey).toHaveBeenCalledTimes(2)
    await act(async () => second.unmount())
    expect(resumeHotkey).toHaveBeenCalledOnce()
    fireEvent.keyUp(window, { key: 'k', ctrlKey: true })
    await act(async () => {})
    expect(resumeHotkey).toHaveBeenCalledOnce()
    fireEvent.keyUp(window, { key: 'Control' })
    await act(async () => {})
    expect(resumeHotkey).toHaveBeenCalledTimes(2)
  })

  it('repauses for a new editor after an older release wait rejects', async () => {
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    let rejectResume!: (error: Error) => void
    vi.mocked(resumeHotkey).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        rejectResume = reject
      }),
    )
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    first.unmount()
    await act(async () => {})
    const second = renderHook(() => useHotkeyCapture(true))
    try {
      await act(async () => rejectResume(new Error('Synthetic release query unavailable')))
      expect(second.result.current).toBe('ready')
      expect(pauseHotkey).toHaveBeenCalledTimes(2)
      expect(resumeHotkey).toHaveBeenCalledOnce()
    } finally {
      await act(async () => second.unmount())
      warning.mockRestore()
    }
  })

  it('interrupts an older release wait before making the new editor ready', async () => {
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    let rejectResume!: (error: Error) => void
    vi.mocked(resumeHotkey).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        rejectResume = reject
      }),
    )
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    first.unmount()
    await act(async () => {})
    const second = renderHook(() => useHotkeyCapture(true))
    try {
      await act(async () => {})
      expect(pauseHotkey).toHaveBeenCalledTimes(2)
      expect(second.result.current).toBe('pending')
    } finally {
      await act(async () => rejectResume(new Error('hotkey registration superseded')))
      await act(async () => second.unmount())
      warning.mockRestore()
    }
  })

  it('resumes after the interrupting editor closes before the older resume settles', async () => {
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    let rejectResume!: (error: Error) => void
    vi.mocked(resumeHotkey).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        rejectResume = reject
      }),
    )
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    first.unmount()
    await act(async () => {})
    const second = renderHook(() => useHotkeyCapture(true))
    second.unmount()
    try {
      await act(async () => rejectResume(new Error('hotkey registration superseded')))
      expect(pauseHotkey).toHaveBeenCalledTimes(2)
      expect(resumeHotkey).toHaveBeenCalledTimes(2)
    } finally {
      warning.mockRestore()
    }
  })

  it('blocks saving when both interrupt pause and the subsequent pause fail', async () => {
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    let finishResume!: () => void
    vi.mocked(resumeHotkey).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishResume = resolve
      }),
    )
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    first.unmount()
    await act(async () => {})
    vi.mocked(pauseHotkey)
      .mockRejectedValueOnce(new Error('Synthetic interrupt failure'))
      .mockRejectedValueOnce(new Error('Synthetic pause failure'))
    const second = renderHook(() => useHotkeyCapture(true))
    try {
      await act(async () => finishResume())
      expect(second.result.current).toBe('error')
      const save = vi.fn().mockResolvedValue('saved')
      await expect(queueShortcutCaptureSave(save)).rejects.toThrow()
      expect(save).not.toHaveBeenCalled()
    } finally {
      await act(async () => second.unmount())
      warning.mockRestore()
    }
  })

  it('waits for the recorded key and its modifiers to be released before resuming', async () => {
    const editor = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
    await act(async () => editor.unmount())
    expect(resumeHotkey).not.toHaveBeenCalled()
    fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
    await act(async () => {})
    expect(resumeHotkey).not.toHaveBeenCalled()
    fireEvent.keyUp(window, { key: 'Shift', ctrlKey: true })
    await act(async () => {})
    expect(resumeHotkey).not.toHaveBeenCalled()
    fireEvent.keyUp(window, { key: 'Control' })
    await act(async () => {})
    expect(resumeHotkey).toHaveBeenCalledOnce()
  })

  it('does not commit stale readiness when capture is enabled again', async () => {
    const committed: string[] = []
    function Harness({ enabled }: { enabled: boolean }) {
      const status = useHotkeyCapture(enabled)
      useLayoutEffect(() => {
        if (enabled) committed.push(status)
      }, [enabled, status])
      return null
    }
    const { rerender } = render(<Harness enabled />)
    await act(async () => {})
    expect(committed[committed.length - 1]).toBe('ready')
    rerender(<Harness enabled={false} />)
    await act(async () => {})
    const previousCount = committed.length
    rerender(<Harness enabled />)
    expect(committed[previousCount]).toBe('pending')
  })

  it('updates an existing editor when a second editor successfully retries pause', async () => {
    vi.mocked(pauseHotkey).mockRejectedValueOnce(new Error('Pause rejected'))
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const first = renderHook(() => useHotkeyCapture(true))
      await act(async () => {})
      expect(first.result.current).toBe('error')
      const second = renderHook(() => useHotkeyCapture(true))
      await act(async () => {})
      expect(second.result.current).toBe('ready')
      expect(first.result.current).toBe('ready')
    } finally {
      await act(async () => cleanup())
      warning.mockRestore()
    }
  })

  it('attempts resume after a failed pause when the editor closes', async () => {
    const failure = new Error('Pause rejected')
    vi.mocked(pauseHotkey).mockRejectedValueOnce(failure)
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const editor = renderHook(() => useHotkeyCapture(true))
      await act(async () => {})
      expect(warning).toHaveBeenCalledWith('Failed to synchronize shortcut capture')
      await act(async () => editor.unmount())
      expect(resumeHotkey).toHaveBeenCalledTimes(1)
    } finally {
      warning.mockRestore()
    }
  })

  it('retries a failed resume after another editor releases its capture', async () => {
    vi.mocked(resumeHotkey).mockRejectedValueOnce(new Error('Resume rejected'))
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const first = renderHook(() => useHotkeyCapture(true))
      await act(async () => {})
      await act(async () => first.unmount())
      expect(resumeHotkey).toHaveBeenCalledTimes(1)
      const second = renderHook(() => useHotkeyCapture(true))
      await act(async () => {})
      expect(pauseHotkey).toHaveBeenCalledTimes(2)
      await act(async () => second.unmount())
      expect(resumeHotkey).toHaveBeenCalledTimes(2)
    } finally {
      warning.mockRestore()
    }
  })

  it('resumes only after every editor releases its capture', async () => {
    const first = renderHook(() => useHotkeyCapture(true))
    const second = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    expect(pauseHotkey).toHaveBeenCalledTimes(1)
    await act(async () => first.unmount())
    expect(resumeHotkey).not.toHaveBeenCalled()
    await act(async () => second.unmount())
    expect(resumeHotkey).toHaveBeenCalledTimes(1)
  })

  it('does not resume a newly opened editor after an older pause completes', async () => {
    let finishPause: () => void = () => {}
    vi.mocked(pauseHotkey).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishPause = resolve
      }),
    )
    const first = renderHook(() => useHotkeyCapture(true))
    await act(async () => {})
    first.unmount()
    const second = renderHook(() => useHotkeyCapture(true))
    await act(async () => finishPause())
    expect(resumeHotkey).not.toHaveBeenCalled()
    await act(async () => second.unmount())
    expect(resumeHotkey).toHaveBeenCalledTimes(1)
  })
})
