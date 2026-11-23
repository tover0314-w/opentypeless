import { act, fireEvent, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { useLayoutEffect } from 'react'
import { useFnShortcutCapture } from '../useFnShortcutCapture'

const handlers = vi.hoisted(() => new Map<string, (event: { payload: unknown }) => void>())
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn(async (event: string, handler: (event: { payload: unknown }) => void) => {
    handlers.set(event, handler)
    return vi.fn()
  }),
}))

afterEach(() => {
  handlers.clear()
  vi.clearAllMocks()
})

describe('Fn shortcut capture', () => {
  it('keeps a new editor ready when an old startup rejects late', async () => {
    let rejectStartup!: (error: Error) => void
    vi.mocked(invoke).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          rejectStartup = reject
        }),
    )
    const edge = vi.fn()
    const editor = renderHook(({ enabled }) => useFnShortcutCapture(enabled, edge), {
      initialProps: { enabled: true },
    })
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      await waitFor(() => expect(rejectStartup).toBeTypeOf('function'))
      editor.rerender({ enabled: false })
      editor.rerender({ enabled: true })
      await waitFor(() => expect(editor.result.current).toBe('ready'))
      const starts = vi
        .mocked(invoke)
        .mock.calls.filter(([command]) => command === 'start_shortcut_capture')
      const currentId = (starts[1][1] as { captureId: string }).captureId
      await act(async () => rejectStartup(new Error('Synthetic old startup failure')))
      expect(editor.result.current).toBe('ready')
      expect(log).not.toHaveBeenCalled()
      act(() =>
        handlers.get('shortcut-capture:fn')?.({
          payload: { captureId: currentId, pressed: true },
        }),
      )
      expect(edge).toHaveBeenCalledExactlyOnceWith(true)
    } finally {
      editor.unmount()
      log.mockRestore()
    }
  })

  it('reports subscription failure without starting the native collector', async () => {
    vi.mocked(listen).mockRejectedValueOnce(new Error('Synthetic subscription failure'))
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const editor = renderHook(() => useFnShortcutCapture(true, vi.fn()))
    try {
      await waitFor(() => expect(editor.result.current).toBe('error'))
      expect(invoke).not.toHaveBeenCalledWith('start_shortcut_capture', expect.anything())
      expect(invoke).toHaveBeenCalledWith('stop_shortcut_capture', expect.anything())
      expect(log).toHaveBeenCalledExactlyOnceWith('Failed to start local shortcut capture')
    } finally {
      editor.unmount()
      log.mockRestore()
    }
  })

  it('does not commit an old ready state when capture is enabled again', async () => {
    const committed: string[] = []
    const editor = renderHook(
      ({ enabled }) => {
        const status = useFnShortcutCapture(enabled, vi.fn())
        useLayoutEffect(() => {
          if (enabled) committed.push(status)
        }, [enabled, status])
        return status
      },
      { initialProps: { enabled: true } },
    )
    try {
      await waitFor(() => expect(editor.result.current).toBe('ready'))
      editor.rerender({ enabled: false })
      const previousCount = committed.length
      editor.rerender({ enabled: true })
      expect(committed[previousCount]).toBe('pending')
    } finally {
      editor.unmount()
    }
  })

  it('does not accept Fn events before native startup has completed', async () => {
    let finish!: () => void
    vi.mocked(invoke).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const edge = vi.fn()
    const editor = renderHook(() => useFnShortcutCapture(true, edge))
    try {
      await waitFor(() => expect(finish).toBeTypeOf('function'))
      const captureId = (vi.mocked(invoke).mock.calls[0][1] as { captureId: string }).captureId
      const handler = handlers.get('shortcut-capture:fn')!
      act(() => handler({ payload: { captureId, pressed: true } }))
      expect(edge).not.toHaveBeenCalled()
      await act(async () => finish())
      act(() => handler({ payload: { captureId, pressed: true } }))
      expect(edge).toHaveBeenCalledExactlyOnceWith(true)
    } finally {
      editor.unmount()
    }
  })

  it('revokes events when native capture startup fails', async () => {
    vi.mocked(invoke).mockRejectedValueOnce(new Error('Synthetic capture startup failure'))
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const edge = vi.fn()
    const editor = renderHook(() => useFnShortcutCapture(true, edge))
    try {
      await waitFor(() => expect(log).toHaveBeenCalled())
      expect(editor.result.current).toBe('error')
      const captureId = (vi.mocked(invoke).mock.calls[0][1] as { captureId: string }).captureId
      act(() => handlers.get('shortcut-capture:fn')?.({ payload: { captureId, pressed: true } }))
      expect(edge).not.toHaveBeenCalled()
    } finally {
      editor.unmount()
      log.mockRestore()
    }
  })

  it('revokes capture on window blur before the component unmounts', async () => {
    const edge = vi.fn()
    const editor = renderHook(() => useFnShortcutCapture(true, edge))
    await waitFor(() =>
      expect(invoke).toHaveBeenCalledWith('start_shortcut_capture', expect.anything()),
    )
    const captureId = (vi.mocked(invoke).mock.calls[0][1] as { captureId: string }).captureId
    const handler = handlers.get('shortcut-capture:fn')!
    fireEvent.blur(window)
    expect(editor.result.current).toBe('pending')
    act(() => handler({ payload: { captureId, pressed: true } }))
    expect(edge).not.toHaveBeenCalled()
    await waitFor(() => expect(invoke).toHaveBeenCalledWith('stop_shortcut_capture', { captureId }))
    editor.unmount()
  })

  it('accepts only events owned by the current editor and stops on unmount', async () => {
    const edge = vi.fn()
    const editor = renderHook(() => useFnShortcutCapture(true, edge))
    await waitFor(() =>
      expect(invoke).toHaveBeenCalledWith('start_shortcut_capture', expect.anything()),
    )
    const captureId = (vi.mocked(invoke).mock.calls[0][1] as { captureId: string }).captureId
    const handler = handlers.get('shortcut-capture:fn')!
    act(() => {
      handler({ payload: { captureId: crypto.randomUUID(), pressed: true } })
      handler({ payload: { captureId, pressed: 'yes' } })
      handler({ payload: { captureId, pressed: true } })
    })
    expect(edge).toHaveBeenCalledExactlyOnceWith(true)
    editor.unmount()
    act(() => handler({ payload: { captureId, pressed: false } }))
    expect(edge).toHaveBeenCalledOnce()
    await waitFor(() => expect(invoke).toHaveBeenCalledWith('stop_shortcut_capture', { captureId }))
  })

  it('stops a late native startup after the editor is closed', async () => {
    let finish!: () => void
    vi.mocked(invoke).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const editor = renderHook(() => useFnShortcutCapture(true, vi.fn()))
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    const captureId = (vi.mocked(invoke).mock.calls[0][1] as { captureId: string }).captureId
    editor.unmount()
    await act(async () => finish())
    await waitFor(() => expect(invoke).toHaveBeenCalledWith('stop_shortcut_capture', { captureId }))
  })

  it('does not subscribe or start native capture when disabled', async () => {
    renderHook(() => useFnShortcutCapture(false, vi.fn()))
    await act(async () => {})
    expect(invoke).not.toHaveBeenCalled()
    expect(handlers.size).toBe(0)
  })
})
