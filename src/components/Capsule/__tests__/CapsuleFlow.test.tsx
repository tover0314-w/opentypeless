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
      contextMenuOpen: false,
      contextMenuReady: false,
      translationTargetMenuOpen: false,
      activeVoiceMode: null,
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

  it('renders the starting state while the microphone warms up', () => {
    useAppStore.setState({ pipelineState: 'preparing' })
    const { container } = render(<Capsule />)
    expect(shell(container).dataset.capsuleView).toBe('starting')
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

  it('labels Ask recording and stops Ask from the finish button', () => {
    useAppStore.setState({ pipelineState: 'ask_recording' })
    const { container } = render(<Capsule />)

    expect(screen.getByText('capsule.modeAsk')).toBeInTheDocument()
    expect(container.querySelector('[data-capsule-glow="ask"]')).not.toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'capsule.finish' }))
    expect(stopAskFlow).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'capsule.cancelAsk' }))
    expect(abortAskDictation).toHaveBeenCalledTimes(1)
  })

  it('shows Thinking with a progress fill while processing', () => {
    useAppStore.setState({ pipelineState: 'polishing' })
    const { container } = render(<Capsule />)
    expect(shell(container).dataset.capsuleView).toBe('processing')
    expect(screen.getByText('capsule.thinking')).toBeInTheDocument()
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

    expect(screen.getByText('capsule.modeTranslating:EN')).toBeInTheDocument()
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
  it('shows the shortcut guide with every configured shortcut', () => {
    useCapsuleAlertStore.getState().show('shortcut_guide')
    render(<Capsule />)

    expect(screen.getByText('capsule.alerts.guideTitle')).toBeInTheDocument()
    expect(screen.getByText('capsule.alerts.guideDictate')).toBeInTheDocument()
    expect(screen.getByText('capsule.alerts.guideAsk')).toBeInTheDocument()
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
