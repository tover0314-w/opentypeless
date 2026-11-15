import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../../i18n'
import { useTauriEvents } from '../../../hooks/useTauriEvents'
import { useAppStore } from '../../../stores/appStore'
import { useCapsuleAlertStore } from '../../../stores/capsuleAlertStore'
import { UI_LANGUAGES } from '../../../lib/constants'
import { Capsule } from '../index'
import englishMessages from '../../../i18n/locales/en.json'

const listeners = vi.hoisted(() => new Map<string, (event: { payload: unknown }) => void>())

vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn((name: string, callback: (event: { payload: unknown }) => void) => {
    listeners.set(name, callback)
    return Promise.resolve(() => listeners.delete(name))
  }),
}))
vi.mock('../../../hooks/useCapsuleResize', () => ({ useCapsuleResize: vi.fn() }))
vi.mock('../../../hooks/useCapsuleClickThrough', () => ({ useCapsuleClickThrough: vi.fn() }))
vi.mock('../../../hooks/useUpdateEvents', () => ({ useCapsuleUpdateNotice: vi.fn() }))
vi.mock('../../../lib/tauri', () => ({}))

function Harness() {
  useTauriEvents()
  return <Capsule />
}

async function send(name: string, payload: unknown) {
  await act(async () => listeners.get(name)?.({ payload }))
}

beforeEach(async () => {
  listeners.clear()
  window.location.hash = '#capsule'
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: true,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  )
  useAppStore.setState(useAppStore.getInitialState())
  useCapsuleAlertStore.getState().dismiss()
  await i18n.changeLanguage('zh')
})

afterEach(async () => {
  cleanup()
  useCapsuleAlertStore.getState().dismiss()
  useAppStore.setState(useAppStore.getInitialState())
  listeners.clear()
  vi.unstubAllGlobals()
  window.location.hash = ''
  localStorage.removeItem('ui_language')
  await i18n.changeLanguage('en')
})

describe('capsule language integration', () => {
  it.each(Object.keys(englishMessages.capsule.errors))(
    'updates the existing %s error in all interface languages without resetting retry',
    async (code) => {
      render(<Harness />)
      await waitFor(() => expect(listeners.has('pipeline:error')).toBe(true))
      await send('pipeline:error', { code })
      act(() => useAppStore.getState().setRetryAvailable(true))
      const stored = useAppStore.getState().pipelineError
      for (const language of UI_LANGUAGES) {
        await send('config:patch', { ui_language: language.value })
        const alert = screen.getByRole('alert')
        expect(alert).toHaveTextContent(i18n.t(`capsule.errors.${code}`))
        expect(useAppStore.getState().pipelineError).toBe(stored)
        expect(useAppStore.getState().retryAvailable).toBe(true)
      }
    },
  )

  it('retains a specific recovery error across language switches and clears its message key on dismissal', async () => {
    render(<Harness />)
    await waitFor(() => expect(listeners.has('config:patch')).toBe(true))
    const messageKey = 'history.retryMissingAudio'
    act(() => {
      useAppStore.getState().setPipelineErrorKey('unknown')
      useAppStore.getState().setPipelineError(i18n.t(messageKey), messageKey)
    })
    for (const language of UI_LANGUAGES) {
      await send('config:patch', { ui_language: language.value })
      expect(screen.getByText(i18n.t(messageKey))).toBeVisible()
      expect(useAppStore.getState().pipelineErrorMessageKey).toBe(messageKey)
    }
    act(() => useAppStore.getState().setPipelineError(null))
    expect(useAppStore.getState().pipelineErrorMessageKey).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('retranslates an existing error on configuration changes without changing its identity', async () => {
    render(<Harness />)
    await waitFor(() => expect(listeners.has('pipeline:error')).toBe(true))
    await send('pipeline:error', { code: 'microphone_permission_required' })
    const originalError = useAppStore.getState().pipelineError
    expect(screen.getByText(i18n.t('capsule.errors.microphone_permission_required'))).toBeVisible()
    for (const language of UI_LANGUAGES) {
      await send('config:patch', { ui_language: language.value })
      expect(
        screen.getByText(i18n.t('capsule.errors.microphone_permission_required')),
      ).toBeVisible()
      expect(useAppStore.getState().pipelineError).toBe(originalError)
      expect(useAppStore.getState().pipelineErrorKey).toBe('microphone_permission_required')
    }
  })

  it('retranslates the duration inside an existing session-ended notice', async () => {
    render(<Harness />)
    await waitFor(() => expect(listeners.has('recording:deadline-reached')).toBe(true))
    await send('recording:deadline-reached', { effectiveMaxSeconds: 120 })
    for (const language of UI_LANGUAGES) {
      await send('config:patch', { ui_language: language.value })
      const duration = i18n.t('recordingLimits.durationMinutes', { count: 2 })
      expect(
        screen.getByText(i18n.t('capsule.alerts.sessionEndedDesc', { duration })),
      ).toBeVisible()
    }
  })

  it('updates the long-hold guide and thinking state while preserving dictated text', async () => {
    render(<Harness />)
    await waitFor(() => expect(listeners.has('config:patch')).toBe(true))
    const transcript = '保留我说的原文'
    act(() => {
      useAppStore.getState().setFinalTranscript(transcript)
      useCapsuleAlertStore.getState().show('shortcut_guide')
    })
    for (const language of UI_LANGUAGES) {
      await send('config:patch', { ui_language: language.value })
      expect(
        screen.getByRole('rowheader', { name: i18n.t('capsule.alerts.guideDictate') }),
      ).toBeVisible()
      act(() => useAppStore.getState().setPipelineState('transcribing'))
      expect(screen.getByText(i18n.t('capsule.thinking'))).toBeVisible()
      expect(useAppStore.getState().finalTranscript).toBe(transcript)
      act(() => useAppStore.getState().setPipelineState('idle'))
    }
  })

  it('updates recording controls, Ask hints and translation hints in all interface languages', async () => {
    render(<Harness />)
    await waitFor(() => expect(listeners.has('config:patch')).toBe(true))
    act(() => {
      useAppStore.setState({
        pipelineState: 'ask_recording',
        recordingStyle: 'handsFree',
      })
      useCapsuleAlertStore.getState().show('ask_anything')
    })
    for (const language of UI_LANGUAGES) {
      await send('config:patch', { ui_language: language.value })
      expect(screen.getByRole('button', { name: i18n.t('capsule.finish') })).toBeVisible()
      expect(screen.getByRole('button', { name: i18n.t('capsule.cancelAsk') })).toBeVisible()
      expect(screen.getByText(i18n.t('capsule.alerts.askAnything'))).toBeVisible()
    }
    act(() => {
      useCapsuleAlertStore.getState().dismiss()
      useAppStore.setState({ pipelineState: 'recording', activeVoiceMode: 'translate' })
      useCapsuleAlertStore.getState().show('translating_to')
    })
    for (const language of UI_LANGUAGES) {
      await send('config:patch', { ui_language: language.value })
      expect(screen.getByText(i18n.t('capsule.alerts.translationLabel'))).toBeVisible()
    }
  })
})
