import { act, cleanup, render, renderHook, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useInteractionSounds } from '../useInteractionSounds'
import { playInteractionCue } from '../../lib/interactionSounds'
import { useAppStore } from '../../stores/appStore'
import { ErrorBoundary } from '../../components/ErrorBoundary'

vi.mock('../../lib/interactionSounds', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/interactionSounds')>()),
  playInteractionCue: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(playInteractionCue).mockClear()
  useAppStore.setState(useAppStore.getInitialState())
  useAppStore.getState().updateConfig({ interaction_sounds: true })
})

afterEach(cleanup)

describe('interaction sound hook', () => {
  it('keeps the recording surface usable after optional cue playback fails', () => {
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    function RecordingSurface() {
      useInteractionSounds()
      const state = useAppStore((store) => store.pipelineState)
      return <div data-testid="recording-surface">{state}</div>
    }
    try {
      render(
        <ErrorBoundary>
          <RecordingSurface />
        </ErrorBoundary>,
      )
      expect(screen.getByTestId('recording-surface')).toHaveTextContent('idle')
      vi.mocked(playInteractionCue).mockImplementationOnce(() => {
        throw new Error('Synthetic cue initialization failure')
      })
      act(() => useAppStore.setState({ pipelineState: 'recording' }))
      expect(screen.getByTestId('recording-surface')).toHaveTextContent('recording')
      expect(screen.queryByRole('button')).toBeNull()
      expect(useAppStore.getState().pipelineState).toBe('recording')
      expect(errorLog).not.toHaveBeenCalledWith('Application rendering failed')
      act(() => useAppStore.setState({ pipelineState: 'idle' }))
      expect(screen.getByTestId('recording-surface')).toHaveTextContent('idle')
      expect(vi.mocked(playInteractionCue).mock.calls).toEqual([['start'], ['end']])
    } finally {
      errorLog.mockRestore()
    }
  })

  it('does not replay a start cue when mounting during an existing recording', () => {
    useAppStore.setState({ pipelineState: 'recording' })
    renderHook(() => useInteractionSounds())
    expect(playInteractionCue).not.toHaveBeenCalled()
  })

  it('plays each recording edge once and stays silent during processing', () => {
    renderHook(() => useInteractionSounds())
    act(() => useAppStore.setState({ pipelineState: 'preparing' }))
    expect(playInteractionCue).not.toHaveBeenCalled()
    act(() => useAppStore.setState({ pipelineState: 'recording' }))
    act(() => useAppStore.setState({ pipelineState: 'recording' }))
    expect(playInteractionCue).toHaveBeenCalledExactlyOnceWith('start')
    act(() => useAppStore.setState({ pipelineState: 'transcribing' }))
    act(() => useAppStore.setState({ pipelineState: 'polishing' }))
    act(() => useAppStore.setState({ pipelineState: 'idle' }))
    expect(vi.mocked(playInteractionCue).mock.calls).toEqual([['start'], ['end']])
  })

  it('does not replay muted edges when sounds are enabled again', () => {
    useAppStore.getState().updateConfig({ interaction_sounds: false })
    renderHook(() => useInteractionSounds())
    act(() => useAppStore.setState({ pipelineState: 'recording' }))
    act(() => useAppStore.getState().updateConfig({ interaction_sounds: true }))
    expect(playInteractionCue).not.toHaveBeenCalled()
    act(() => useAppStore.setState({ pipelineState: 'idle' }))
    expect(playInteractionCue).toHaveBeenCalledExactlyOnceWith('end')
  })

  it('honors disabling sounds before the recording ends', () => {
    renderHook(() => useInteractionSounds())
    act(() => useAppStore.setState({ pipelineState: 'recording' }))
    act(() => useAppStore.getState().updateConfig({ interaction_sounds: false }))
    act(() => useAppStore.setState({ pipelineState: 'idle' }))
    expect(playInteractionCue).toHaveBeenCalledExactlyOnceWith('start')
  })

  it('does not react to state changes after unmount', () => {
    const { unmount } = renderHook(() => useInteractionSounds())
    unmount()
    act(() => useAppStore.setState({ pipelineState: 'recording' }))
    expect(playInteractionCue).not.toHaveBeenCalled()
  })
})
