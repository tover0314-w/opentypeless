import { StrictMode } from 'react'
import { act, cleanup, render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useTauriEvents } from '../useTauriEvents'
import { useAppStore } from '../../stores/appStore'
import { useCloudServiceStore } from '../../stores/cloudServiceStore'

const eventListeners = vi.hoisted(() => new Map<string, (event: { payload: unknown }) => void>())
const invalidateCloudSessionOnce = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))

vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn((event: string, handler: (event: { payload: unknown }) => void) => {
    eventListeners.set(event, handler)
    return Promise.resolve(vi.fn())
  }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('../../i18n', () => ({
  default: {
    language: 'en',
    changeLanguage: vi.fn(),
  },
}))

vi.mock('../../lib/tauri', () => ({
  getHistory: vi.fn().mockResolvedValue([]),
}))

vi.mock('../../components/toast-service', () => ({
  toast: vi.fn(),
}))

vi.mock('../../lib/cloud-session', () => ({
  invalidateCloudSessionOnce,
}))

function HookHarness() {
  useTauriEvents()
  return null
}

describe('useTauriEvents', () => {
  beforeEach(() => {
    eventListeners.clear()
    useAppStore.setState({
      hotkeyRegistrationError: null,
      lastContext: null,
      activeVoiceMode: null,
    })
    useCloudServiceStore.setState({ incident: null })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('clears hotkey registration errors when the backend reports recovery', async () => {
    render(<HookHarness />)

    await waitFor(() => {
      expect(eventListeners.has('hotkey:registration-failed')).toBe(true)
    })

    act(() => {
      eventListeners.get('hotkey:registration-failed')?.({ payload: 'Shortcut occupied' })
    })
    expect(useAppStore.getState().hotkeyRegistrationError).toBe('Shortcut occupied')

    await waitFor(() => {
      expect(eventListeners.has('hotkey:registration-recovered')).toBe(true)
    })

    act(() => {
      eventListeners.get('hotkey:registration-recovered')?.({ payload: undefined })
    })
    expect(useAppStore.getState().hotkeyRegistrationError).toBeNull()
  })

  it('clears stale capsule errors when a new pipeline run starts preparing', async () => {
    useAppStore.setState({ pipelineError: 'Previous failure' })
    render(<HookHarness />)

    await waitFor(() => {
      expect(eventListeners.has('pipeline:state')).toBe(true)
    })

    act(() => {
      eventListeners.get('pipeline:state')?.({ payload: 'preparing' })
    })

    expect(useAppStore.getState().pipelineError).toBeNull()
  })

  it('forwards one Rust session-invalid event to the shared coordinator in Strict Mode', async () => {
    render(
      <StrictMode>
        <HookHarness />
      </StrictMode>,
    )

    await waitFor(() => {
      expect(eventListeners.has('auth:session-invalid')).toBe(true)
    })

    act(() => {
      eventListeners.get('auth:session-invalid')?.({ payload: undefined })
    })

    expect(invalidateCloudSessionOnce).toHaveBeenCalledTimes(1)
  })

  it('stores only the safe context summary emitted for the completed operation', async () => {
    render(<HookHarness />)

    await waitFor(() => {
      expect(eventListeners.has('pipeline:context')).toBe(true)
    })

    act(() => {
      eventListeners.get('pipeline:context')?.({
        payload: {
          profileId: 'chat.slack',
          family: 'work_chat',
          appLabel: 'Slack',
          iconKey: 'slack',
          overrideId: 'slack',
        },
      })
    })

    expect(useAppStore.getState().lastContext).toEqual({
      profileId: 'chat.slack',
      family: 'work_chat',
      appLabel: 'Slack',
      iconKey: 'slack',
      overrideId: 'slack',
    })
  })

  it('tracks the operation voice mode without inferring it from pipeline state', async () => {
    render(<HookHarness />)

    await waitFor(() => {
      expect(eventListeners.has('pipeline:voice_mode')).toBe(true)
    })

    act(() => {
      eventListeners.get('pipeline:voice_mode')?.({ payload: 'translate' })
    })
    expect(useAppStore.getState().activeVoiceMode).toBe('translate')

    act(() => {
      eventListeners.get('pipeline:voice_mode')?.({ payload: null })
    })
    expect(useAppStore.getState().activeVoiceMode).toBeNull()
  })

  it('shows a persistent incident only for a failing managed-cloud provider', async () => {
    useAppStore.setState((state) => ({
      config: { ...state.config, stt_provider: 'cloud', llm_provider: 'deepseek' },
    }))
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:error')).toBe(true))

    act(() => {
      eventListeners.get('pipeline:error')?.({
        payload: { code: 'stt_failed', details: 'HTTP 503' },
      })
    })

    expect(useCloudServiceStore.getState().incident).toMatchObject({
      kind: 'stt',
      code: 'stt_failed',
    })
  })

  it('does not mislabel BYOK failures or quota errors as a cloud outage', async () => {
    useAppStore.setState((state) => ({
      config: { ...state.config, stt_provider: 'openai-whisper', llm_provider: 'deepseek' },
    }))
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:error')).toBe(true))

    act(() => {
      eventListeners.get('pipeline:error')?.({ payload: { code: 'stt_failed' } })
    })
    expect(useCloudServiceStore.getState().incident).toBeNull()

    useAppStore.setState((state) => ({
      config: { ...state.config, stt_provider: 'cloud' },
    }))
    act(() => {
      eventListeners.get('pipeline:error')?.({ payload: { code: 'stt_quota_exceeded' } })
    })
    expect(useCloudServiceStore.getState().incident).toBeNull()
  })

  it('clears an old incident when the user starts a manual retry', async () => {
    useCloudServiceStore.setState({
      incident: { kind: 'llm', code: 'llm_failed', occurredAt: '2026-08-26T10:00:00.000Z' },
    })
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:state')).toBe(true))

    act(() => {
      eventListeners.get('pipeline:state')?.({ payload: 'preparing' })
    })
    expect(useCloudServiceStore.getState().incident).toBeNull()
  })
})
