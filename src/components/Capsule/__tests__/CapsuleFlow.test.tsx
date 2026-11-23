import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { writeText } from '@tauri-apps/plugin-clipboard-manager'
import { useAppStore } from '../../../stores/appStore'
import { useCapsuleAlertStore } from '../../../stores/capsuleAlertStore'
import {
  abortAskDictation,
  abortRecording,
  setActiveTranslationTarget,
  stopAskFlow,
} from '../../../lib/tauri'
import { Capsule } from '../index'
import { openCapsuleSettings } from '../../../lib/capsuleNavigation'
import { showCapsuleContextMenu } from '../../../lib/capsuleContextMenu'

vi.mock('../../../lib/capsuleContextMenu', () => ({
  showCapsuleContextMenu: vi.fn().mockResolvedValue(undefined),
  disposeCapsuleContextMenu: vi.fn().mockResolvedValue(undefined),
}))

const motionPreferences = vi.hoisted(() => ({ reduced: true }))

vi.mock('../../../hooks/useSceneShortcutNotice', () => ({
  useSceneShortcutNotice: vi.fn(),
}))

vi.mock('../../../lib/capsuleNavigation', () => ({
  openCapsuleSettings: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  motion: new Proxy(
    {},
    {
      get:
        (_target, tag: string) =>
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        ({ children, layout, transition, ...props }: any) =>
          React.createElement(tag, props, children),
    },
  ),
  useReducedMotion: () => motionPreferences.reduced,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) =>
      options?.language ? `${key}:${options.language}` : key,
  }),
}))

vi.mock('../../../hooks/useCapsuleResize', () => ({
  useCapsuleResize: () => ({ width: 460, height: 56 }),
}))

vi.mock('@tauri-apps/plugin-clipboard-manager', () => ({
  writeText: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('../../../lib/tauri', () => ({
  abortAskDictation: vi.fn().mockResolvedValue(undefined),
  abortRecording: vi.fn().mockResolvedValue(undefined),
  setActiveTranslationTarget: vi.fn().mockResolvedValue({
    targets: ['en', 'ja'],
    active_target: 'ja',
  }),
  setCapsuleAutoHide: vi.fn().mockResolvedValue(undefined),
  stopAskFlow: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('../../../hooks/useUpdateEvents', () => ({
  useCapsuleUpdateNotice: vi.fn(),
}))

function shell(container: HTMLElement) {
  return container.querySelector('[data-capsule-view]') as HTMLElement
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  motionPreferences.reduced = true
  useAppStore.setState(useAppStore.getInitialState())
  useCapsuleAlertStore.getState().dismiss()
})

describe('Capsule bar views', () => {
  it('keeps the project glass surface and opens a native context menu', () => {
    const { container } = render(<Capsule />)
    expect(shell(container)).toHaveAttribute('data-capsule-material', 'glass')
    fireEvent.contextMenu(shell(container))
    expect(showCapsuleContextMenu).toHaveBeenCalledOnce()
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it.each(['dictate', 'ask'] as const)(
    'keeps private stop failure details out of logs in %s mode',
    async (mode) => {
      const log = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        const failure = new Error('synthetic-private-stop-text-and-token')
        if (mode === 'ask') vi.mocked(stopAskFlow).mockRejectedValueOnce(failure)
        else vi.mocked(invoke).mockRejectedValueOnce(failure)
        useAppStore.setState({
          pipelineState: mode === 'ask' ? 'ask_recording' : 'recording',
          activeVoiceMode: mode,
          recordingStyle: 'handsFree',
        })
        render(<Capsule />)
        fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
        await waitFor(() => expect(log).toHaveBeenCalled())
        expect(log).toHaveBeenCalledExactlyOnceWith(
          mode === 'ask' ? 'Failed to stop Ask flow' : 'Failed to stop recording',
        )
      } finally {
        log.mockRestore()
      }
    },
  )

  it.each(['dictate', 'ask'] as const)(
    'keeps private cancellation failure details out of logs in %s mode',
    async (mode) => {
      const log = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        const abort = mode === 'ask' ? abortAskDictation : abortRecording
        vi.mocked(abort).mockRejectedValueOnce(
          new Error('synthetic-private-recording-text-and-token'),
        )
        useAppStore.setState({
          pipelineState: mode === 'ask' ? 'ask_recording' : 'recording',
          activeVoiceMode: mode,
        })
        render(<Capsule />)
        fireEvent.click(
          screen.getByRole('button', {
            name: mode === 'ask' ? 'capsule.cancelAsk' : 'capsule.cancelRecording',
          }),
        )
        await waitFor(() => expect(log).toHaveBeenCalled())
        expect(log).toHaveBeenCalledExactlyOnceWith('Failed to cancel recording')
        expect(useCapsuleAlertStore.getState().alert?.kind).not.toBe('transcript_cancelled')
      } finally {
        log.mockRestore()
      }
    },
  )

  it('does not log private errors when opening translation settings fails', async () => {
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      vi.mocked(openCapsuleSettings).mockRejectedValueOnce(
        new Error('synthetic-private-token private settings path'),
      )
      useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'translate' })
      useCapsuleAlertStore.getState().show('translating_to')
      render(<Capsule />)
      fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
      fireEvent.click(screen.getByRole('menuitem', { name: 'translationTargets.addLanguage' }))
      await waitFor(() => expect(errorLog).toHaveBeenCalled())
      expect(openCapsuleSettings).toHaveBeenCalledWith('#/models?section=translation')
      expect(errorLog.mock.calls.flat().map(String).join(' ')).not.toContain(
        'synthetic-private-token',
      )
    } finally {
      errorLog.mockRestore()
    }
  })
  it.each(['recording', 'ask_recording'] as const)(
    'does not finish or cancel a replacement %s session from old button events',
    (pipelineState) => {
      localStorage.removeItem('capsule.pressToFinishShownAt')
      const deadline = {
        recordingKind:
          pipelineState === 'ask_recording' ? ('ask' as const) : ('dictation' as const),
        sessionId: 1,
        startedAtUnixMs: Date.now(),
        deadlineAtUnixMs: Date.now() + 30000,
        effectiveMaxSeconds: 30,
      }
      useAppStore.setState({
        pipelineState,
        recordingStyle: 'handsFree',
        recordingDeadline: deadline,
      })
      render(<Capsule />)
      const finish = screen.getByRole('button', { name: 'capsule.finish' })
      const cancel = screen.getByRole('button', {
        name: pipelineState === 'ask_recording' ? 'capsule.cancelAsk' : 'capsule.cancelRecording',
      })
      act(() => {
        useAppStore.getState().setRecordingDeadline({ ...deadline, sessionId: 2 })
        fireEvent.click(finish)
        fireEvent.click(cancel)
      })
      expect(stopAskFlow).not.toHaveBeenCalled()
      expect(invoke).not.toHaveBeenCalledWith('stop_recording')
      expect(abortRecording).not.toHaveBeenCalled()
      expect(abortAskDictation).not.toHaveBeenCalled()
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
    },
  )

  it.each(['recording', 'ask_recording'] as const)(
    'ignores an old %s Cancel event after the pipeline becomes idle',
    (pipelineState) => {
      useAppStore.setState({ pipelineState, recordingStyle: 'handsFree' })
      render(<Capsule />)
      const cancel = screen.getByRole('button', {
        name: pipelineState === 'ask_recording' ? 'capsule.cancelAsk' : 'capsule.cancelRecording',
      })
      act(() => {
        useAppStore.getState().setPipelineState('idle')
        fireEvent.click(cancel)
      })
      expect(abortRecording).not.toHaveBeenCalled()
      expect(abortAskDictation).not.toHaveBeenCalled()
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
    },
  )

  it('keeps cancellation available after recording advances to processing', async () => {
    useAppStore.setState({ pipelineState: 'recording', recordingStyle: 'handsFree' })
    render(<Capsule />)
    const cancel = screen.getByRole('button', { name: 'capsule.cancelRecording' })
    await act(async () => {
      useAppStore.getState().setPipelineState('polishing')
      fireEvent.click(cancel)
    })
    expect(abortRecording).toHaveBeenCalledOnce()
  })

  it.each(['recording', 'ask_recording'] as const)(
    'ignores an old %s Finish click after the state has become idle',
    (pipelineState) => {
      localStorage.removeItem('capsule.pressToFinishShownAt')
      useAppStore.setState({ pipelineState, recordingStyle: 'handsFree' })
      render(<Capsule />)
      const finish = screen.getByRole('button', { name: 'capsule.finish' })
      act(() => {
        useAppStore.getState().setPipelineState('idle')
        fireEvent.click(finish)
      })
      expect(stopAskFlow).not.toHaveBeenCalled()
      expect(invoke).not.toHaveBeenCalledWith('stop_recording')
      expect(
        useCapsuleAlertStore.getState().queue.some((alert) => alert.kind === 'press_to_finish'),
      ).toBe(false)
    },
  )

  it('ignores an old copy event after a newer transcript replaces the reminder', () => {
    useCapsuleAlertStore.getState().show('copy_last_transcript', 'Old synthetic text')
    render(<Capsule />)
    const oldCopy = screen.getByRole('button', { name: 'capsule.alerts.copy' })
    act(() => {
      useCapsuleAlertStore.getState().show('copy_last_transcript', 'New synthetic text')
      fireEvent.click(oldCopy)
    })
    expect(writeText).not.toHaveBeenCalled()
    expect(useCapsuleAlertStore.getState().alert?.text).toBe('New synthetic text')
  })

  it('does not split an emoji at the transcript preview boundary', () => {
    const text = `${'文'.repeat(31)}😀more text`
    useCapsuleAlertStore.getState().show('copy_last_transcript', text)
    render(<Capsule />)
    expect(screen.getByText(`“${'文'.repeat(31)}😀…”`)).toBeInTheDocument()
  })

  it.each(['👨‍👩‍👧‍👦', '👍🏽', 'e\u0301'])(
    'keeps the grapheme %s intact at the transcript preview boundary',
    (grapheme) => {
      useCapsuleAlertStore
        .getState()
        .show('copy_last_transcript', `${'文'.repeat(31)}${grapheme}more`)
      render(<Capsule />)
      expect(screen.getByText(`“${'文'.repeat(31)}${grapheme}…”`)).toBeInTheDocument()
    },
  )

  it('closes a stale pending reminder without cancelling anything while idle', async () => {
    useAppStore.setState({ pipelineState: 'idle' })
    useCapsuleAlertStore.getState().show('previous_pending')
    render(<Capsule />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'capsule.cancel' })))
    expect(abortRecording).not.toHaveBeenCalled()
    expect(abortAskDictation).not.toHaveBeenCalled()
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it.each(['preparing', 'recording', 'ask_recording'] as const)(
    'does not discard a new %s session from an obsolete pending reminder',
    async (pipelineState) => {
      useAppStore.setState({ pipelineState: 'polishing' })
      useCapsuleAlertStore.getState().show('previous_pending')
      render(<Capsule />)
      act(() => useAppStore.setState({ pipelineState }))
      await act(async () => fireEvent.click(screen.getByRole('button', { name: 'capsule.cancel' })))
      expect(abortRecording).not.toHaveBeenCalled()
      expect(abortAskDictation).not.toHaveBeenCalled()
      expect(useAppStore.getState().pipelineState).toBe(pipelineState)
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
    },
  )

  it('discards the current Ask flow instead of dictation from a queued pending reminder', async () => {
    useAppStore.setState({ pipelineState: 'ask_thinking', activeVoiceMode: 'ask' })
    useCapsuleAlertStore.getState().show('previous_pending')
    render(<Capsule />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'capsule.cancel' })))
    expect(abortAskDictation).toHaveBeenCalledOnce()
    expect(abortRecording).not.toHaveBeenCalled()
    expect(useCapsuleAlertStore.getState().alert?.kind).not.toBe('previous_pending')
  })

  it('submits one copy per pending request and allows retry after failure', async () => {
    let failCopy: ((error: Error) => void) | undefined
    vi.mocked(writeText).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          failCopy = reject
        }),
    )
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      useCapsuleAlertStore.getState().show('copy_last_transcript', 'Synthetic transcript')
      render(<Capsule />)
      const copy = screen.getByRole('button', { name: 'capsule.alerts.copy' })
      fireEvent.click(copy)
      fireEvent.click(copy)
      expect(writeText).toHaveBeenCalledOnce()
      await act(async () => failCopy?.(new Error('Synthetic copy failure')))
      expect(screen.queryByText('capsule.alerts.copied')).toBeNull()
      await act(async () => fireEvent.click(copy))
      expect(writeText).toHaveBeenCalledTimes(2)
      expect(screen.getByText('capsule.alerts.copied')).toBeInTheDocument()
    } finally {
      warning.mockRestore()
    }
  })

  it('does not mark a replacement transcript copied when an older copy completes', async () => {
    let finishCopy: (() => void) | undefined
    vi.mocked(writeText).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishCopy = resolve
        }),
    )
    useCapsuleAlertStore.getState().show('copy_last_transcript', 'Old synthetic transcript')
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.copy' }))
    act(() =>
      useCapsuleAlertStore.getState().show('copy_last_transcript', 'New synthetic transcript'),
    )
    await act(async () => finishCopy?.())
    expect(screen.getByRole('button', { name: 'capsule.alerts.copy' })).toBeInTheDocument()
    expect(screen.queryByText('capsule.alerts.copied')).toBeNull()
    await act(async () =>
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.copy' })),
    )
    expect(writeText).toHaveBeenLastCalledWith('New synthetic transcript')
  })

  beforeEach(() => {
    useAppStore.setState({
      pipelineState: 'idle',
      pipelineError: null,
      translationTargetMenuOpen: false,
      activeVoiceMode: null,
      recordingStyle: 'handsFree',
      partialTranscript: '',
    })
  })

  it('shows a slim idle bar and does not start dictation when clicked', () => {
    const { container } = render(<Capsule />)
    expect(shell(container).dataset.capsuleView).toBe('idle')
    expect(shell(container).style.width).toBe('40px')
    expect(shell(container).style.height).toBe('6px')

    const pointerUp = new Event('pointerup', { bubbles: true })
    Object.defineProperty(pointerUp, 'button', { value: 0 })
    fireEvent(shell(container), pointerUp)

    expect(invoke).not.toHaveBeenCalledWith('start_recording')
  })

  it('keeps an auto-hidden idle bar invisible but in place', () => {
    useAppStore.setState({
      config: { ...useAppStore.getState().config, capsule_auto_hide: true },
    })
    const { container } = render(<Capsule />)
    expect(shell(container).dataset.capsuleView).toBe('idle')
    expect(shell(container).hasAttribute('data-idle-hidden')).toBe(true)
  })

  it('does not reveal or activate an auto-hidden idle bar on pointer hover', () => {
    useAppStore.setState({
      config: { ...useAppStore.getState().config, capsule_auto_hide: true },
    })
    const { container } = render(<Capsule />)
    const bar = shell(container)
    fireEvent.pointerEnter(bar)
    fireEvent.pointerMove(bar, { clientX: 20, clientY: 3 })
    fireEvent.mouseEnter(bar)

    expect(bar.dataset.capsuleView).toBe('idle')
    expect(bar.hasAttribute('data-idle-hidden')).toBe(true)
    expect(bar.style.width).toBe('40px')
    expect(bar.style.height).toBe('6px')
    expect(useAppStore.getState().pipelineState).toBe('idle')
    expect(invoke).not.toHaveBeenCalledWith('start_recording')
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('prevents the browser menu and preserves the native shortcut menu', () => {
    const { container } = render(<Capsule />)
    const contextMenu = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
    fireEvent(shell(container), contextMenu)
    expect(contextMenu.defaultPrevented).toBe(true)
    expect(showCapsuleContextMenu).toHaveBeenCalledOnce()
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('renders the starting state while the microphone warms up', () => {
    useAppStore.setState({ pipelineState: 'preparing' })
    const { container } = render(<Capsule />)
    expect(shell(container).dataset.capsuleView).toBe('starting')
    expect(shell(container).style.width).toBe('54px')
    expect(shell(container).style.height).toBe('12px')
  })

  it('shows only the waveform for push-to-talk recordings', () => {
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'dictate',
      recordingStyle: 'pushToTalk',
    })
    const { container } = render(<Capsule />)
    expect(shell(container).dataset.capsuleStyle).toBe('pushToTalk')
    expect(shell(container).style.width).toBe('70px')
    expect(screen.queryByRole('button', { name: 'capsule.finish' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'capsule.cancelRecording' })).toBeNull()
  })

  it('shows cancel and finish controls while recording', async () => {
    useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'dictate' })
    const { container } = render(<Capsule />)
    expect(shell(container).style.width).toBe('116px')

    fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(invoke).toHaveBeenCalledWith('stop_recording')

    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancelRecording' }))
    await waitFor(() => expect(abortRecording).toHaveBeenCalledTimes(1))
    await waitFor(() =>
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('transcript_cancelled'),
    )
  })

  it('does not report cancellation before the native command acknowledges it', async () => {
    let finishCancel!: () => void
    vi.mocked(abortRecording).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishCancel = resolve
      }),
    )
    useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'dictate' })
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancelRecording' }))
    expect(useCapsuleAlertStore.getState().alert?.kind).not.toBe('transcript_cancelled')
    await act(async () => finishCancel())
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('transcript_cancelled')
  })

  it('reports confirmed cancellation after idle clears the recording deadline', async () => {
    let finishCancel!: () => void
    vi.mocked(abortRecording).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishCancel = resolve
      }),
    )
    useAppStore.setState({
      pipelineState: 'recording',
      recordingDeadline: {
        recordingKind: 'dictation',
        sessionId: 1,
        startedAtUnixMs: Date.now(),
        deadlineAtUnixMs: Date.now() + 30000,
        effectiveMaxSeconds: 30,
      },
    })
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancelRecording' }))
    act(() => useAppStore.setState({ pipelineState: 'idle', recordingDeadline: null }))
    await act(async () => finishCancel())
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('transcript_cancelled')
  })

  it('ignores repeated finish clicks and keeps controls after a rejected stop', async () => {
    let failStop: (error: Error) => void = () => {}
    vi.mocked(invoke).mockReturnValueOnce(
      new Promise((_resolve, reject) => {
        failStop = reject
      }),
    )
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      localStorage.removeItem('capsule.pressToFinishShownAt')
      useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'dictate' })
      render(<Capsule />)
      const finish = screen.getByRole('button', { name: 'capsule.finish' })
      fireEvent.click(finish)
      fireEvent.click(finish)
      const stopCalls = vi
        .mocked(invoke)
        .mock.calls.filter(([command]) => command === 'stop_recording')
      await act(async () => failStop(new Error('Stop rejected')))
      expect(stopCalls).toHaveLength(1)
      expect(useCapsuleAlertStore.getState().alert?.kind).not.toBe('press_to_finish')
      expect(warning).toHaveBeenCalledWith('Failed to stop recording')
      expect(useAppStore.getState().pipelineState).toBe('recording')
      expect(screen.getByRole('button', { name: 'capsule.cancelRecording' })).toBeInTheDocument()
    } finally {
      warning.mockRestore()
    }
  })

  it('keeps button pointer events from reaching the draggable shell', () => {
    useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'dictate' })
    render(<Capsule />)
    const cancel = screen.getByRole('button', { name: 'capsule.cancelRecording' })
    const pointerUp = new Event('pointerup', { bubbles: true })
    Object.defineProperty(pointerUp, 'button', { value: 0 })
    fireEvent(cancel, pointerUp)
    expect(invoke).not.toHaveBeenCalledWith('stop_recording')
  })

  it('withdraws the cancellation notice when cancelling a recording fails', async () => {
    vi.mocked(abortRecording).mockRejectedValueOnce(new Error('Native command unavailable'))
    useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'dictate' })
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancelRecording' }))
    await waitFor(() =>
      expect(useCapsuleAlertStore.getState().alert?.kind).not.toBe('transcript_cancelled'),
    )
    expect(useAppStore.getState().pipelineState).toBe('recording')
    expect(screen.getByRole('button', { name: 'capsule.cancelRecording' })).toBeInTheDocument()
  })

  it.each(['dictate', 'ask'] as const)(
    'submits cancellation once while the same %s session is pending',
    async (mode) => {
      const abort = mode === 'ask' ? abortAskDictation : abortRecording
      let finishAbort: (() => void) | undefined
      vi.mocked(abort).mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            finishAbort = resolve
          }),
      )
      useAppStore.setState({
        pipelineState: mode === 'ask' ? 'ask_recording' : 'recording',
        activeVoiceMode: mode,
      })
      render(<Capsule />)
      const cancel = screen.getByRole('button', {
        name: mode === 'ask' ? 'capsule.cancelAsk' : 'capsule.cancelRecording',
      })
      fireEvent.click(cancel)
      const noticeId = useCapsuleAlertStore.getState().alert?.id
      fireEvent.click(cancel)
      expect(abort).toHaveBeenCalledOnce()
      expect(useCapsuleAlertStore.getState().alert?.id).toBe(noticeId)
      await act(async () => finishAbort?.())
    },
  )

  it('does not dismiss a newer cancellation when an older command fails', async () => {
    let failCancel: (error: Error) => void = () => {}
    vi.mocked(abortRecording).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        failCancel = reject
      }),
    )
    useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'dictate' })
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancelRecording' }))
    act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 42))
    await act(async () => failCancel(new Error('late failure')))
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({
      kind: 'transcript_cancelled',
      entryId: 42,
    })
  })

  it('keeps the pending-take alert while discarding and allows retry after failure', async () => {
    useAppStore.setState({ pipelineState: 'polishing' })
    let failDiscard: ((error: Error) => void) | undefined
    vi.mocked(abortRecording).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          failDiscard = reject
        }),
    )
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      useCapsuleAlertStore.getState().show('previous_pending')
      render(<Capsule />)
      const discard = screen.getByRole('button', { name: 'capsule.cancel' })
      fireEvent.click(discard)
      expect(screen.getByText('capsule.alerts.previousPendingTitle')).toBeInTheDocument()
      fireEvent.click(discard)
      expect(abortRecording).toHaveBeenCalledOnce()
      await act(async () => failDiscard?.(new Error('Synthetic discard failure')))
      expect(screen.getByText('capsule.alerts.previousPendingTitle')).toBeInTheDocument()
      await act(async () => fireEvent.click(discard))
      expect(abortRecording).toHaveBeenCalledTimes(2)
      expect(screen.queryByText('capsule.alerts.previousPendingTitle')).toBeNull()
    } finally {
      warning.mockRestore()
    }
  })

  it('does not close a newer pending-take reminder when an older discard succeeds', async () => {
    useAppStore.setState({ pipelineState: 'polishing' })
    let finishDiscard: (() => void) | undefined
    vi.mocked(abortRecording).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishDiscard = resolve
        }),
    )
    useCapsuleAlertStore.getState().show('previous_pending')
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancel' }))
    act(() => useCapsuleAlertStore.getState().show('previous_pending'))
    const newerId = useCapsuleAlertStore.getState().alert?.id
    await act(async () => finishDiscard?.())
    expect(useCapsuleAlertStore.getState().alert?.id).toBe(newerId)
    expect(screen.getByText('capsule.alerts.previousPendingTitle')).toBeInTheDocument()
  })

  it('ignores an obsolete discard event after the pending reminder is replaced', () => {
    useAppStore.setState({ pipelineState: 'polishing' })
    useCapsuleAlertStore.getState().show('previous_pending')
    render(<Capsule />)
    const oldDiscard = screen.getByRole('button', { name: 'capsule.cancel' })
    act(() => {
      useCapsuleAlertStore.getState().show('previous_pending')
      fireEvent.click(oldDiscard)
    })
    expect(abortRecording).not.toHaveBeenCalled()
    expect(abortAskDictation).not.toHaveBeenCalled()
    expect(screen.getByText('capsule.alerts.previousPendingTitle')).toBeInTheDocument()
  })

  it('does not transfer an old discard busy state to a replacement reminder', async () => {
    useAppStore.setState({ pipelineState: 'polishing' })
    let finishOld: (() => void) | undefined
    let finishNew: (() => void) | undefined
    vi.mocked(abortRecording)
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            finishOld = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            finishNew = resolve
          }),
      )
    useCapsuleAlertStore.getState().show('previous_pending')
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancel' }))
    expect(screen.getByRole('button', { name: 'capsule.cancel' })).toBeDisabled()
    act(() => useCapsuleAlertStore.getState().show('previous_pending'))
    expect(screen.getByRole('button', { name: 'capsule.cancel' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancel' }))
    const currentId = useCapsuleAlertStore.getState().alert?.id
    await act(async () => finishOld?.())
    expect(screen.getByRole('button', { name: 'capsule.cancel' })).toBeDisabled()
    expect(useCapsuleAlertStore.getState().alert?.id).toBe(currentId)
    await act(async () => finishNew?.())
    expect(screen.queryByText('capsule.alerts.previousPendingTitle')).toBeNull()
  })

  it.each(['dictate', 'ask'] as const)(
    'does not finish the %s session while cancellation is pending',
    async (mode) => {
      const abort = mode === 'ask' ? abortAskDictation : abortRecording
      let finishAbort: (() => void) | undefined
      vi.mocked(abort).mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            finishAbort = resolve
          }),
      )
      useAppStore.setState({
        pipelineState: mode === 'ask' ? 'ask_recording' : 'recording',
        activeVoiceMode: mode,
      })
      render(<Capsule />)
      fireEvent.click(
        screen.getByRole('button', {
          name: mode === 'ask' ? 'capsule.cancelAsk' : 'capsule.cancelRecording',
        }),
      )
      fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
      expect(stopAskFlow).not.toHaveBeenCalled()
      expect(invoke).not.toHaveBeenCalledWith('stop_recording')
      await act(async () => finishAbort?.())
    },
  )

  it.each(['dictate', 'ask'] as const)(
    'keeps the new %s cancellation protected when an older session completes',
    async (mode) => {
      const abort = mode === 'ask' ? abortAskDictation : abortRecording
      let finishOld: (() => void) | undefined
      let finishNew: (() => void) | undefined
      vi.mocked(abort)
        .mockImplementationOnce(
          () =>
            new Promise<void>((resolve) => {
              finishOld = resolve
            }),
        )
        .mockImplementationOnce(
          () =>
            new Promise<void>((resolve) => {
              finishNew = resolve
            }),
        )
      const deadline = {
        recordingKind: mode === 'ask' ? ('ask' as const) : ('dictation' as const),
        sessionId: 1,
        startedAtUnixMs: Date.now(),
        deadlineAtUnixMs: Date.now() + 30000,
        effectiveMaxSeconds: 30,
      }
      useAppStore.setState({
        pipelineState: mode === 'ask' ? 'ask_recording' : 'recording',
        activeVoiceMode: mode,
        recordingDeadline: deadline,
      })
      render(<Capsule />)
      const cancel = screen.getByRole('button', {
        name: mode === 'ask' ? 'capsule.cancelAsk' : 'capsule.cancelRecording',
      })
      fireEvent.click(cancel)
      act(() => useAppStore.setState({ recordingDeadline: { ...deadline, sessionId: 2 } }))
      fireEvent.click(cancel)
      expect(abort).toHaveBeenCalledTimes(2)
      const newNotice = useCapsuleAlertStore.getState().alert?.id
      await act(async () => finishOld?.())
      fireEvent.click(cancel)
      expect(abort).toHaveBeenCalledTimes(2)
      expect(useCapsuleAlertStore.getState().alert?.id).toBe(newNotice)
      await act(async () => finishNew?.())
    },
  )

  it('allows another cancellation attempt after the previous request fails', async () => {
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      vi.mocked(abortRecording).mockRejectedValueOnce(new Error('Synthetic cancel failure'))
      useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'dictate' })
      render(<Capsule />)
      const cancel = screen.getByRole('button', { name: 'capsule.cancelRecording' })
      await act(async () => fireEvent.click(cancel))
      expect(useCapsuleAlertStore.getState().alert?.kind).not.toBe('transcript_cancelled')
      await act(async () => fireEvent.click(cancel))
      expect(abortRecording).toHaveBeenCalledTimes(2)
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('transcript_cancelled')
    } finally {
      warning.mockRestore()
    }
  })

  it('marks Ask recording with a green glow and stops Ask from the finish button', () => {
    useAppStore.setState({ pipelineState: 'ask_recording', recordingStyle: 'pushToTalk' })
    const { container } = render(<Capsule />)

    expect(container.querySelector('[data-capsule-glow="ask"]')).not.toBeNull()
    expect(shell(container).dataset.capsuleStyle).toBe('handsFree')

    fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(stopAskFlow).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancelAsk' }))
    expect(abortAskDictation).toHaveBeenCalledTimes(1)
  })

  it('submits Ask finish only once while the same session stop is pending', async () => {
    let finishStop: (() => void) | undefined
    vi.mocked(stopAskFlow).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishStop = resolve
        }),
    )
    useAppStore.setState({ pipelineState: 'ask_recording' })
    render(<Capsule />)
    const finish = screen.getByRole('button', { name: 'capsule.finish' })
    fireEvent.click(finish)
    fireEvent.click(finish)
    expect(stopAskFlow).toHaveBeenCalledOnce()
    await act(async () => finishStop?.())
  })

  it('does not let an older Ask stop block completion of a new session', async () => {
    let finishOld: (() => void) | undefined
    let finishNew: (() => void) | undefined
    vi.mocked(stopAskFlow)
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            finishOld = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            finishNew = resolve
          }),
      )
    const deadline = {
      recordingKind: 'ask' as const,
      sessionId: 1,
      startedAtUnixMs: Date.now(),
      deadlineAtUnixMs: Date.now() + 30000,
      effectiveMaxSeconds: 30,
    }
    useAppStore.setState({ pipelineState: 'ask_recording', recordingDeadline: deadline })
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
    act(() => useAppStore.setState({ recordingDeadline: { ...deadline, sessionId: 2 } }))
    fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(stopAskFlow).toHaveBeenCalledTimes(2)
    await act(async () => finishOld?.())
    fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(stopAskFlow).toHaveBeenCalledTimes(2)
    await act(async () => finishNew?.())
  })

  it('shows Thinking with a progress fill while processing', () => {
    useAppStore.setState({ pipelineState: 'polishing' })
    const { container } = render(<Capsule />)
    expect(shell(container).dataset.capsuleView).toBe('processing')
    expect(shell(container).style.width).toBe('92px')
    expect(screen.getByText('capsule.thinking')).toBeInTheDocument()
  })

  it('keeps showing Thinking while the text is being inserted', () => {
    useAppStore.setState({ pipelineState: 'outputting' })
    const { container } = render(<Capsule />)
    expect(shell(container).dataset.capsuleView).toBe('processing')
  })

  it('explains errors in an alert above the dotted bar', () => {
    useAppStore.setState({ pipelineError: 'Microphone failed' })
    const { container } = render(<Capsule />)
    expect(shell(container).dataset.capsuleView).toBe('error')
    expect(shell(container).style.width).toBe('70px')
    expect(screen.getByRole('alert').textContent).toBe('Microphone failed')
  })

  it('warns about a slow connection when processing takes long', () => {
    vi.useFakeTimers()
    try {
      useAppStore.setState({ pipelineState: 'transcribing' })
      render(<Capsule />)
      act(() => {
        vi.advanceTimersByTime(5_200)
      })
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
    } finally {
      vi.useRealTimers()
    }
  })

  it.each([-3_600_000, 3_600_000])(
    'keeps normal-motion progress stable when system time changes by %s milliseconds',
    (clockChange) => {
      vi.useFakeTimers()
      motionPreferences.reduced = false
      try {
        useAppStore.setState({ pipelineState: 'transcribing' })
        const { container } = render(<Capsule />)
        const progress = shell(container).querySelector('[aria-hidden="true"]') as HTMLElement
        act(() => vi.advanceTimersByTime(1000))
        expect(progress.style.width).toBe('80%')
        vi.setSystemTime(Date.now() + clockChange)
        act(() => vi.advanceTimersByTime(1000))
        expect(progress.style.width).toBe('90%')
      } finally {
        vi.useRealTimers()
      }
    },
  )

  it('keeps reduced-motion progress stopped while still reporting a slow request', () => {
    vi.useFakeTimers()
    try {
      motionPreferences.reduced = true
      useAppStore.setState({ pipelineState: 'transcribing' })
      const { container } = render(<Capsule />)
      const progress = shell(container).querySelector('[aria-hidden="true"]') as HTMLElement
      expect(progress.style.width).toBe('0%')
      act(() => vi.advanceTimersByTime(5200))
      expect(progress.style.width).toBe('0%')
      expect(screen.getByText('capsule.thinking')).toBeInTheDocument()
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
    } finally {
      vi.useRealTimers()
    }
  })

  it.each([-3_600_000, 3_600_000])(
    'keeps the slow-processing clock stable when system time changes by %s milliseconds',
    (clockChange) => {
      vi.useFakeTimers()
      try {
        useAppStore.setState({ pipelineState: 'transcribing' })
        render(<Capsule />)
        act(() => vi.advanceTimersByTime(1000))
        vi.setSystemTime(Date.now() + clockChange)
        act(() => vi.advanceTimersByTime(1000))
        expect(
          useCapsuleAlertStore.getState().queue.some((alert) => alert.kind === 'weak_network'),
        ).toBe(false)
        act(() => vi.advanceTimersByTime(3200))
        expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
      } finally {
        vi.useRealTimers()
      }
    },
  )

  it('clears the slow-network reminder and restarts its clock for the next processing run', () => {
    vi.useFakeTimers()
    try {
      useAppStore.setState({ pipelineState: 'transcribing' })
      render(<Capsule />)
      act(() => vi.advanceTimersByTime(5200))
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
      act(() => useAppStore.setState({ pipelineState: 'idle' }))
      expect(
        useCapsuleAlertStore.getState().queue.some((alert) => alert.kind === 'weak_network'),
      ).toBe(false)
      act(() => vi.advanceTimersByTime(6000))
      expect(
        useCapsuleAlertStore.getState().queue.some((alert) => alert.kind === 'weak_network'),
      ).toBe(false)
      act(() => useAppStore.setState({ pipelineState: 'ask_thinking' }))
      act(() => vi.advanceTimersByTime(1000))
      expect(
        useCapsuleAlertStore.getState().queue.some((alert) => alert.kind === 'weak_network'),
      ).toBe(false)
      act(() => vi.advanceTimersByTime(4200))
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not dismiss a newer slow-network reminder when an older Thinking view ends', () => {
    vi.useFakeTimers()
    try {
      useAppStore.setState({ pipelineState: 'polishing' })
      render(<Capsule />)
      act(() => vi.advanceTimersByTime(5200))
      act(() => useCapsuleAlertStore.getState().show('weak_network'))
      const newerId = useCapsuleAlertStore.getState().alert?.id
      act(() => useAppStore.setState({ pipelineState: 'idle' }))
      expect(useCapsuleAlertStore.getState().alert?.id).toBe(newerId)
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
    } finally {
      vi.useRealTimers()
    }
  })

  it('restarts Thinking timing when a new session replaces another processing session', () => {
    vi.useFakeTimers()
    try {
      const deadline = {
        recordingKind: 'dictation' as const,
        sessionId: 1,
        startedAtUnixMs: Date.now(),
        deadlineAtUnixMs: Date.now() + 30000,
        effectiveMaxSeconds: 30,
      }
      useAppStore.setState({ pipelineState: 'polishing', recordingDeadline: deadline })
      render(<Capsule />)
      act(() => vi.advanceTimersByTime(5200))
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
      act(() => useAppStore.setState({ recordingDeadline: { ...deadline, sessionId: 2 } }))
      expect(
        useCapsuleAlertStore.getState().queue.some((alert) => alert.kind === 'weak_network'),
      ).toBe(false)
      act(() => vi.advanceTimersByTime(1000))
      expect(
        useCapsuleAlertStore.getState().queue.some((alert) => alert.kind === 'weak_network'),
      ).toBe(false)
      act(() => vi.advanceTimersByTime(4200))
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
    } finally {
      vi.useRealTimers()
    }
  })

  it('labels translation and switches the active target without restarting', async () => {
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    useCapsuleAlertStore.getState().show('translating_to')
    const { container } = render(<Capsule />)

    expect(container.querySelector('[data-capsule-glow="translate"]')).not.toBeNull()
    expect(shell(container).style.width).toBe('116px')

    fireEvent.click(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))

    await waitFor(() => expect(setActiveTranslationTarget).toHaveBeenCalledWith('ja'))
    expect(invoke).not.toHaveBeenCalledWith('stop_recording')
    expect(useAppStore.getState().config.translation.active_target).toBe('ja')
  })

  it('closes the translation target menu on Escape', () => {
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    expect(screen.getByRole('menu', { name: 'capsule.translationTargets' })).toBeInTheDocument()

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('menu', { name: 'capsule.translationTargets' })).toBeNull()
  })

  it('moves keyboard focus into translation targets and supports arrow navigation', async () => {
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    const trigger = screen.getByRole('button', { name: 'capsule.translationTarget en' })
    trigger.focus()
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    await waitFor(() =>
      expect(screen.getByRole('menuitemradio', { name: 'English' })).toHaveFocus(),
    )
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowUp' })
    expect(screen.getByRole('menuitemradio', { name: '日本語' })).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'Home' })
    expect(screen.getByRole('menuitem', { name: 'translationTargets.addLanguage' })).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'End' })
    expect(screen.getByRole('menuitemradio', { name: 'English' })).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    await waitFor(() => expect(trigger).toHaveFocus())
  })

  it('does not steal focus back after Escape when the user focuses another control', () => {
    vi.useFakeTimers()
    try {
      useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'translate' })
      useCapsuleAlertStore.getState().show('translating_to')
      render(<Capsule />)
      const trigger = screen.getByRole('button', { name: 'capsule.translationTarget en' })
      trigger.focus()
      fireEvent.keyDown(trigger, { key: 'ArrowDown' })
      act(() => vi.runOnlyPendingTimers())
      fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
      const outside = document.createElement('button')
      document.body.append(outside)
      try {
        outside.focus()
        act(() => vi.runOnlyPendingTimers())
        expect(outside).toHaveFocus()
      } finally {
        outside.remove()
      }
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not let a delayed keyboard-open focus action affect a reopened hover menu', () => {
    vi.useFakeTimers()
    try {
      useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'translate' })
      useCapsuleAlertStore.getState().show('translating_to')
      render(<Capsule />)
      const trigger = screen.getByRole('button', { name: 'capsule.translationTarget en' })
      trigger.focus()
      fireEvent.keyDown(trigger, { key: 'ArrowDown' })
      const outside = document.createElement('button')
      document.body.append(outside)
      try {
        outside.focus()
        fireEvent.mouseEnter(trigger)
        act(() => vi.runOnlyPendingTimers())
        expect(outside).toHaveFocus()
      } finally {
        outside.remove()
      }
    } finally {
      vi.useRealTimers()
    }
  })

  it('opens translation targets on hover and keeps the menu while entering it', () => {
    useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'translate' })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    const trigger = screen.getByRole('button', { name: 'capsule.translationTarget en' })
    fireEvent.mouseEnter(trigger)
    fireEvent.click(trigger)
    const menu = screen.getByRole('menu', { name: 'capsule.translationTargets' })
    fireEvent.mouseLeave(trigger, { relatedTarget: menu })
    expect(menu).toBeInTheDocument()
    fireEvent.mouseLeave(menu, { relatedTarget: document.body })
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('keeps the current translation target and explains a failed switch', async () => {
    vi.mocked(setActiveTranslationTarget).mockRejectedValueOnce(
      new Error('synthetic-private-token private spoken words'),
    )
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(useAppStore.getState().config.translation.active_target).toBe('en')
    expect(screen.getByRole('menu')).toBeInTheDocument()
    expect(invoke).not.toHaveBeenCalledWith('stop_recording')
    expect(errorLog.mock.calls.flat().map(String).join(' ')).not.toContain(
      'synthetic-private-token',
    )
    errorLog.mockRestore()
  })

  it('closes the original target menu when a versioned saved echo arrives before its receipt', async () => {
    const config = {
      ...structuredClone(useAppStore.getInitialState().config),
      managed_stt_capability_state: null,
      translation: { targets: ['en', 'ja'], active_target: 'en' },
    }
    const configEpoch = '11111111-1111-4111-8111-111111111111'
    useAppStore
      .getState()
      .initializeConfigCommit({ configEpoch, revision: 1, saveId: null, config })
    const deadline = {
      sessionId: 7,
      recordingKind: 'dictation' as const,
      startedAtUnixMs: 1000,
      deadlineAtUnixMs: 31000,
      effectiveMaxSeconds: 30,
    }
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      recordingDeadline: deadline,
    })
    vi.mocked(setActiveTranslationTarget).mockImplementationOnce(async () => {
      const translation = { targets: ['en', 'ja'], active_target: 'ja' }
      useAppStore.getState().setRecordingDeadline({ ...deadline })
      act(() =>
        useAppStore.getState().applyConfigCommit({
          configEpoch,
          revision: 2,
          saveId: null,
          config: { ...config, translation, target_lang: 'ja' },
        }),
      )
      return translation
    })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    expect(setActiveTranslationTarget).toHaveBeenCalledWith('ja', 7, configEpoch)
    expect(useAppStore.getState().config.translation.active_target).toBe('ja')
    expect(useAppStore.getState().configCommit?.revision).toBe(2)
  })

  it('does not close a new recording target menu with an earlier recording receipt', async () => {
    const config = {
      ...structuredClone(useAppStore.getInitialState().config),
      managed_stt_capability_state: null,
      translation: { targets: ['en', 'ja'], active_target: 'en' },
    }
    const configEpoch = '11111111-1111-4111-8111-111111111111'
    useAppStore
      .getState()
      .initializeConfigCommit({ configEpoch, revision: 1, saveId: null, config })
    const deadline = {
      sessionId: 7,
      recordingKind: 'dictation' as const,
      startedAtUnixMs: 1000,
      deadlineAtUnixMs: 31000,
      effectiveMaxSeconds: 30,
    }
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      recordingDeadline: deadline,
    })
    vi.mocked(setActiveTranslationTarget).mockImplementationOnce(async () => {
      const translation = { targets: ['en', 'ja'], active_target: 'ja' }
      useAppStore.getState().setRecordingDeadline({ ...deadline, sessionId: 8 })
      useAppStore.getState().applyConfigCommit({
        configEpoch,
        revision: 2,
        saveId: null,
        config: { ...config, translation, target_lang: 'ja' },
      })
      return translation
    })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
    await act(async () => {})
    expect(screen.getByRole('menu')).toBeInTheDocument()
    expect(useAppStore.getState().recordingDeadline?.sessionId).toBe(8)
  })

  it('does not overwrite newer translation settings with a late switch response', async () => {
    let finish!: (value: { targets: string[]; active_target: string }) => void
    vi.mocked(setActiveTranslationTarget).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
    const newer = { targets: ['en', 'de'], active_target: 'de' }
    act(() => useAppStore.getState().updateConfig({ translation: newer, target_lang: 'de' }))
    await act(async () => finish({ targets: ['en', 'ja'], active_target: 'ja' }))
    expect(useAppStore.getState().config.translation).toEqual(newer)
    expect(useAppStore.getState().config.target_lang).toBe('de')
  })

  it('syncs a saved language without closing a reopened menu', async () => {
    let finish!: (value: { targets: string[]; active_target: string }) => void
    vi.mocked(setActiveTranslationTarget).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    const trigger = screen.getByRole('button', { name: 'capsule.translationTarget en' })
    fireEvent.mouseEnter(trigger)
    fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.mouseEnter(trigger)
    const menu = screen.getByRole('menu')
    await act(async () => finish({ targets: ['en', 'ja'], active_target: 'ja' }))
    expect(useAppStore.getState().config.translation.active_target).toBe('ja')
    expect(menu).toBeInTheDocument()
    expect(useAppStore.getState().translationTargetMenuOpen).toBe(true)
  })

  it('does not show an old switch error after newer translation settings arrive', async () => {
    vi.mocked(setActiveTranslationTarget).mockRejectedValueOnce(new Error('Synthetic failure'))
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    act(() => {
      useAppStore.getState().updateConfig({
        translation: { targets: ['en', 'de'], active_target: 'de' },
        target_lang: 'de',
      })
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('does not show a previous recording switch failure in a new recording with unchanged targets', async () => {
    const deadline = {
      sessionId: 7,
      recordingKind: 'dictation' as const,
      startedAtUnixMs: 1000,
      deadlineAtUnixMs: 31000,
      effectiveMaxSeconds: 30,
    }
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      recordingDeadline: deadline,
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    vi.mocked(setActiveTranslationTarget).mockImplementationOnce(async () => {
      useAppStore.getState().setRecordingDeadline({ ...deadline, sessionId: 8 })
      throw new Error('Synthetic old recording failure')
    })
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      useCapsuleAlertStore.getState().show('translating_to')
      render(<Capsule />)
      fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
      fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
      await act(async () => {})
      expect(screen.queryByRole('alert')).toBeNull()
      expect(log).not.toHaveBeenCalled()
      expect(useAppStore.getState().recordingDeadline?.sessionId).toBe(8)
    } finally {
      log.mockRestore()
    }
  })

  it('does not apply a late switch response after the capsule unmounts', async () => {
    let finish!: (value: { targets: string[]; active_target: string }) => void
    vi.mocked(setActiveTranslationTarget).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    useCapsuleAlertStore.getState().show('translating_to')
    const { unmount } = render(<Capsule />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
    unmount()
    await act(async () => finish({ targets: ['en', 'ja'], active_target: 'ja' }))
    expect(useAppStore.getState().config.translation.active_target).toBe('en')
  })

  it('retires a displayed target switch error when a new recording uses the same targets', async () => {
    const deadline = {
      sessionId: 7,
      recordingKind: 'dictation' as const,
      startedAtUnixMs: 1000,
      deadlineAtUnixMs: 31000,
      effectiveMaxSeconds: 30,
    }
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      recordingDeadline: deadline,
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    vi.mocked(setActiveTranslationTarget).mockRejectedValueOnce(new Error('Synthetic failure'))
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      useCapsuleAlertStore.getState().show('translating_to')
      render(<Capsule />)
      fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
      fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
      await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
      act(() => useAppStore.getState().setRecordingDeadline({ ...deadline }))
      expect(screen.getByRole('alert')).toBeInTheDocument()
      act(() => useAppStore.getState().setRecordingDeadline({ ...deadline, sessionId: 8 }))
      expect(screen.queryByRole('alert')).toBeNull()
    } finally {
      log.mockRestore()
    }
  })

  it('lets a new recording choose a target while the previous recording request remains pending', async () => {
    const deadline = {
      sessionId: 7,
      recordingKind: 'dictation' as const,
      startedAtUnixMs: 1000,
      deadlineAtUnixMs: 31000,
      effectiveMaxSeconds: 30,
    }
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      recordingDeadline: deadline,
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    let finishOld!: (value: { targets: string[]; active_target: string }) => void
    let finishNew!: (value: { targets: string[]; active_target: string }) => void
    vi.mocked(setActiveTranslationTarget)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOld = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishNew = resolve
          }),
      )
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    fireEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
    act(() => useAppStore.getState().setRecordingDeadline({ ...deadline, sessionId: 8 }))
    const target = screen.getByRole('menuitemradio', { name: '日本語' })
    expect(target).toBeEnabled()
    fireEvent.click(target)
    expect(setActiveTranslationTarget).toHaveBeenCalledTimes(2)
    await act(async () => finishOld({ targets: ['en', 'ja'], active_target: 'ja' }))
    expect(screen.getByRole('menuitemradio', { name: '日本語' })).toBeDisabled()
    await act(async () => finishNew({ targets: ['en', 'ja'], active_target: 'ja' }))
    expect(useAppStore.getState().config.translation.active_target).toBe('ja')
  })

  it('closes the target menu when a higher-priority warning hides the translation notice', () => {
    useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'translate' })
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    expect(useAppStore.getState().translationTargetMenuOpen).toBe(true)
    act(() => useCapsuleAlertStore.getState().show('last_minute'))
    expect(useAppStore.getState().translationTargetMenuOpen).toBe(false)
    expect(screen.queryByRole('menu')).toBeNull()
  })
})

describe('Capsule alerts', () => {
  function setHotkeys() {
    const config = useAppStore.getState().config
    useAppStore.setState({
      config: {
        ...config,
        hotkeys: {
          ...config.hotkeys,
          dictation: { primary: 'LeftShift', modifiers: [] },
          dictationBindings: [{ primary: 'LeftShift', modifiers: [] }],
          translate: { primary: 'LeftShift', modifiers: ['Fn'] },
          translateBindings: [{ primary: 'LeftShift', modifiers: ['Fn'] }],
          ask: { primary: 'Space', modifiers: ['LeftShift'] },
          askBindings: [{ primary: 'Space', modifiers: ['LeftShift'] }],
        },
      },
    })
  }

  it('shows the Typeless shortcut table with one row per voice feature', () => {
    setHotkeys()
    useCapsuleAlertStore.getState().show('shortcut_guide')
    const { container } = render(<Capsule />)

    expect(screen.getByText('capsule.alerts.guideTitle')).toBeInTheDocument()
    const rows = container.querySelectorAll('[data-guide-row]')
    expect([...rows].map((row) => row.getAttribute('data-guide-row'))).toEqual([
      'dictate',
      'translate',
      'ask',
    ])
    expect(rows[1].textContent).toBe('capsule.alerts.guideTranslateFnLeft Shift')
    expect(rows[2].textContent).toBe('capsule.alerts.guideAskLeft ShiftSpace')
  })

  it('does not render shortcut teaching without an effective dictation key', () => {
    const config = useAppStore.getState().config
    useAppStore.setState({
      config: {
        ...config,
        hotkeys: {
          ...config.hotkeys,
          dictation: { primary: '', modifiers: [] },
          dictationBindings: [],
        },
      },
    })
    useCapsuleAlertStore.getState().show('shortcut_guide')
    render(<Capsule />)
    expect(screen.queryByText('capsule.alerts.guideTitle')).toBeNull()
    expect(document.querySelector('[data-capsule-alert="shortcut_guide"]')).toBeNull()
  })

  it('updates an open shortcut guide when persisted bindings change', () => {
    setHotkeys()
    useCapsuleAlertStore.getState().show('shortcut_guide')
    const { container } = render(<Capsule />)
    const hotkeys = useAppStore.getState().config.hotkeys
    act(() =>
      useAppStore.getState().applyPersistedConfigPatch({
        hotkeys: {
          ...hotkeys,
          dictation: { primary: 'K', modifiers: ['Ctrl'] },
          dictationBindings: [{ primary: 'K', modifiers: ['Ctrl'] }],
          translate: null,
          translateBindings: [],
          ask: null,
          askBindings: [],
        },
      }),
    )
    const rows = container.querySelectorAll('[data-guide-row]')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toHaveAttribute('data-guide-row', 'dictate')
    expect(rows[0]).toHaveTextContent('CtrlK')
    expect(rows[0]).not.toHaveTextContent('Left Shift')
    expect(screen.queryByText('capsule.alerts.guideTranslate')).toBeNull()
    expect(screen.queryByText('capsule.alerts.guideAsk')).toBeNull()
  })

  it('hides the shortcut table when the backend dismisses it on key release', async () => {
    useCapsuleAlertStore.getState().show('shortcut_guide')
    render(<Capsule />)
    expect(screen.getByText('capsule.alerts.guideTitle')).toBeInTheDocument()

    act(() => useCapsuleAlertStore.getState().dismiss('shortcut_guide'))
    expect(screen.queryByText('capsule.alerts.guideTitle')).toBeNull()
  })

  it('shows a Finish tooltip with the dictation key while hovering the check button', () => {
    setHotkeys()
    useAppStore.setState({ pipelineState: 'recording' })
    render(<Capsule />)

    fireEvent.pointerEnter(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(screen.getByRole('tooltip').textContent).toBe('capsule.finishLeft Shift')
    fireEvent.pointerLeave(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(screen.queryByRole('tooltip')).toBeNull()
  })

  it('associates the visible Finish tooltip with its button and removes stale descriptions', () => {
    setHotkeys()
    useAppStore.setState({ pipelineState: 'recording', recordingStyle: 'handsFree' })
    render(<Capsule />)
    const finish = screen.getByRole('button', { name: 'capsule.finish' })
    expect(finish).not.toHaveAttribute('aria-describedby')
    fireEvent.pointerEnter(finish)
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.id).not.toBe('')
    expect(finish).toHaveAttribute('aria-describedby', tooltip.id)
    fireEvent.pointerLeave(finish)
    expect(finish).not.toHaveAttribute('aria-describedby')
  })

  it('shows the existing Finish shortcut hint on keyboard focus and closes it on blur', () => {
    setHotkeys()
    useAppStore.setState({ pipelineState: 'recording', recordingStyle: 'handsFree' })
    render(<Capsule />)
    const finish = screen.getByRole('button', { name: 'capsule.finish' })
    act(() => finish.focus())
    expect(screen.getByRole('tooltip').textContent).toBe('capsule.finishLeft Shift')
    fireEvent.pointerLeave(finish)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    act(() => screen.getByRole('button', { name: 'capsule.cancelRecording' }).focus())
    expect(screen.queryByRole('tooltip')).toBeNull()
  })

  it('does not carry Finish hover into the next recording', () => {
    setHotkeys()
    useAppStore.setState({ pipelineState: 'recording', recordingStyle: 'handsFree' })
    render(<Capsule />)
    fireEvent.pointerEnter(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    act(() => useAppStore.setState({ pipelineState: 'polishing' }))
    act(() => useAppStore.setState({ pipelineState: 'recording' }))
    expect(screen.queryByRole('tooltip')).toBeNull()
    fireEvent.pointerEnter(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('shows the Ask shortcut rather than the dictation key in the Finish tooltip', () => {
    setHotkeys()
    useAppStore.setState({ pipelineState: 'ask_recording', activeVoiceMode: 'ask' })
    render(<Capsule />)
    fireEvent.pointerEnter(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(screen.getByRole('tooltip').textContent).toBe('capsule.finishLeft ShiftSpace')
  })

  it('shows the translation shortcut in the Finish tooltip', () => {
    setHotkeys()
    useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'translate' })
    render(<Capsule />)
    fireEvent.pointerEnter(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(screen.getByRole('tooltip').textContent).toBe('capsule.finishFnLeft Shift')
  })

  it.each(['ask', 'translate'] as const)(
    'does not show or consume dictation-only finish teaching in %s mode',
    (mode) => {
      localStorage.removeItem('capsule.pressToFinishShownAt')
      setHotkeys()
      useAppStore.setState({
        pipelineState: mode === 'ask' ? 'ask_recording' : 'recording',
        activeVoiceMode: mode,
      })
      render(<Capsule />)
      fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
      expect(
        useCapsuleAlertStore.getState().queue.some((alert) => alert.kind === 'press_to_finish'),
      ).toBe(false)
      expect(localStorage.getItem('capsule.pressToFinishShownAt')).toBeNull()
    },
  )

  it('nudges toward the keyboard finish once after clicking the check button', () => {
    localStorage.removeItem('capsule.pressToFinishShownAt')
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'dictate',
      recordingStyle: 'handsFree',
    })
    render(<Capsule />)

    fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('press_to_finish')

    act(() => useCapsuleAlertStore.getState().dismiss())
    fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('greets a newly onboarded user with the dictation key and dismisses on Got it', () => {
    setHotkeys()
    useCapsuleAlertStore.getState().show('ready')
    const { container } = render(<Capsule />)

    expect(screen.getByText('capsule.alerts.readyTitle')).toBeInTheDocument()
    expect(container.querySelector('[data-capsule-alert="ready"]')?.textContent).toContain(
      'Left Shift',
    )
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.gotIt' }))
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('points at the Ask key when dictating over a selection', () => {
    setHotkeys()
    useCapsuleAlertStore.getState().show('selected_text_tip')
    const { container } = render(<Capsule />)

    expect(container.querySelector('[data-capsule-alert="selected_text_tip"]')?.textContent).toBe(
      'capsule.alerts.selectedTipPrefixLeft ShiftSpacecapsule.alerts.selectedTipSuffix',
    )
  })

  it('warns once a take enters its last minute', () => {
    useCapsuleAlertStore.getState().show('last_minute')
    render(<Capsule />)
    expect(screen.getByText('capsule.alerts.lastMinuteTitle')).toBeInTheDocument()
  })

  it('offers to cancel a take that is still processing', async () => {
    useAppStore.setState({ pipelineState: 'polishing' })
    useCapsuleAlertStore.getState().show('previous_pending')
    render(<Capsule />)

    expect(screen.getByText('capsule.alerts.previousPendingTitle')).toBeInTheDocument()
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'capsule.cancel' })))
    expect(abortRecording).toHaveBeenCalled()
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('offers to copy the last transcript when it could not be inserted', async () => {
    const { writeText } = await import('@tauri-apps/plugin-clipboard-manager')
    useCapsuleAlertStore.getState().show('copy_last_transcript', 'hello from the capsule')
    render(<Capsule />)

    expect(screen.getByText('capsule.alerts.copyLastTitle')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.copy' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('hello from the capsule'))
    expect(await screen.findByText('capsule.alerts.copied')).toBeInTheDocument()
  })
})
