import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { emitTo } from '@tauri-apps/api/event'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useUseCaseVoiceHint } from '../useUseCaseVoiceHint'
import { useAppStore } from '../../stores/appStore'

vi.mock('@tauri-apps/api/event', () => ({ emitTo: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn().mockResolvedValue(undefined) }))

beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  useAppStore.setState(useAppStore.getInitialState())
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('use case voice hint', () => {
  it('opens the existing URL and hints once per application after one second', async () => {
    const { result } = renderHook(() => useUseCaseVoiceHint())
    await act(async () => result.current('gmail', 'https://mail.google.com/'))
    expect(openUrl).toHaveBeenCalledWith('https://mail.google.com/')
    expect(emitTo).not.toHaveBeenCalled()
    await act(async () => vi.advanceTimersByTime(1000))
    expect(emitTo).toHaveBeenCalledWith('capsule', 'capsule:alert', { kind: 'use_voice' })
    await act(async () => result.current('gmail', 'https://mail.google.com/'))
    await act(async () => vi.advanceTimersByTime(1000))
    expect(emitTo).toHaveBeenCalledTimes(1)
    await act(async () => result.current('slack', 'https://app.slack.com/'))
    await act(async () => vi.advanceTimersByTime(1000))
    expect(emitTo).toHaveBeenCalledTimes(2)
  })

  it('does not show a delayed hint if recording started', async () => {
    const { result } = renderHook(() => useUseCaseVoiceHint())
    await act(async () => result.current('gmail', 'https://mail.google.com/'))
    useAppStore.setState({ pipelineState: 'recording' })
    await act(async () => vi.advanceTimersByTime(1000))
    expect(emitTo).not.toHaveBeenCalled()
  })

  it('cancels a scheduled hint on unmount', async () => {
    const { result, unmount } = renderHook(() => useUseCaseVoiceHint())
    await act(async () => result.current('gmail', 'https://mail.google.com/'))
    unmount()
    await act(async () => vi.advanceTimersByTime(1000))
    expect(emitTo).not.toHaveBeenCalled()
  })

  it('does not schedule a hint when the URL cannot be opened', async () => {
    vi.mocked(openUrl).mockRejectedValueOnce(new Error('unavailable'))
    const { result } = renderHook(() => useUseCaseVoiceHint())
    await act(async () => result.current('gmail', 'https://mail.google.com/'))
    await act(async () => vi.advanceTimersByTime(1000))
    expect(emitTo).not.toHaveBeenCalled()
    expect(localStorage.getItem('capsule.useVoice.gmail')).toBeNull()
  })

  it('does not schedule after unmount while the opener is pending', async () => {
    let finishOpen: () => void = () => {}
    vi.mocked(openUrl).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishOpen = resolve
      }),
    )
    const { result, unmount } = renderHook(() => useUseCaseVoiceHint())
    const opening = result.current('gmail', 'https://mail.google.com/')
    unmount()
    await act(async () => {
      finishOpen()
      await opening
      vi.advanceTimersByTime(1000)
    })
    expect(emitTo).not.toHaveBeenCalled()
  })

  it('allows a later reminder when the first was skipped because of recording', async () => {
    const { result } = renderHook(() => useUseCaseVoiceHint())
    await act(async () => result.current('gmail', 'https://mail.google.com/'))
    useAppStore.setState({ pipelineState: 'recording' })
    await act(async () => vi.advanceTimersByTime(1000))
    useAppStore.setState({ pipelineState: 'idle' })
    await act(async () => result.current('gmail', 'https://mail.google.com/'))
    await act(async () => vi.advanceTimersByTime(1000))
    expect(emitTo).toHaveBeenCalledTimes(1)
  })
})
