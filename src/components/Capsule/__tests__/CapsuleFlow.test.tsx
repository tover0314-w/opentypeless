import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { useAppStore } from '../../../stores/appStore'
import { useCapsuleAlertStore } from '../../../stores/capsuleAlertStore'
import {
  abortAskDictation,
  abortRecording,
  setActiveTranslationTarget,
  stopAskFlow,
} from '../../../lib/tauri'
import { Capsule } from '../index'

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
  useReducedMotion: () => true,
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

function shell(container: HTMLElement) {
  return container.querySelector('[data-capsule-view]') as HTMLElement
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  useAppStore.setState(useAppStore.getInitialState())
  useCapsuleAlertStore.setState({ alert: null })
})

describe('Capsule bar views', () => {
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

  it('has no right-click menu, like the Typeless floating bar', () => {
    const { container } = render(<Capsule />)
    fireEvent.contextMenu(shell(container))
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
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('transcript_cancelled')
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

  it('labels translation and switches the active target without restarting', async () => {
    useAppStore.setState({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...useAppStore.getState().config,
        translation: { targets: ['en', 'ja'], active_target: 'en' },
      },
    })
    const { container } = render(<Capsule />)

    expect(container.querySelector('[data-capsule-glow="translate"]')).not.toBeNull()

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
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.translationTarget en' }))
    expect(screen.getByRole('menu', { name: 'capsule.translationTargets' })).toBeInTheDocument()

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('menu', { name: 'capsule.translationTargets' })).toBeNull()
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

  it('nudges toward the keyboard finish once after clicking the check button', () => {
    localStorage.removeItem('capsule.pressToFinishShownAt')
    useAppStore.setState({ pipelineState: 'ask_recording' })
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

  it('offers to cancel a take that is still processing', () => {
    useCapsuleAlertStore.getState().show('previous_pending')
    render(<Capsule />)

    expect(screen.getByText('capsule.alerts.previousPendingTitle')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancel' }))
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
