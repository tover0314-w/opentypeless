import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PermissionsStep } from '../PermissionsStep'

const mockConfig = {
  hotkey: 'Fn',
  output_mode: 'keyboard',
  insertion_strategy: 'auto',
  stt_provider: 'openai-whisper',
  hotkeys: {
    dictation: { primary: 'Fn', modifiers: [] },
  },
}

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) =>
      ({
        'onboarding.permissions.subtitle':
          'OpenTypeless asks for access only when a feature needs it.',
        'onboarding.permissions.microphone': 'Microphone',
        'onboarding.permissions.microphoneDesc': 'Capture your voice.',
        'onboarding.permissions.textOutput': 'Text output',
        'onboarding.permissions.textOutputDesc': 'Type into the app you are using.',
        'onboarding.permissions.browserApps': 'Browser apps',
        'onboarding.permissions.browserAppsDesc': 'Use Gmail, Docs, and Slack Web modes.',
        'onboarding.permissions.appleSpeech': 'Apple Speech',
        'onboarding.permissions.appleSpeechDesc': 'Use local macOS transcription.',
        'onboarding.permissions.fix': 'Fix',
        'onboarding.permissions.status.ready': 'Ready',
        'onboarding.permissions.status.needed': 'Needed',
        'onboarding.permissions.status.later': 'Later',
      })[key] ?? key,
  }),
}))

vi.mock('../../../stores/appStore', () => ({
  useAppStore: (selector: any) =>
    selector({
      config: mockConfig,
    }),
}))

vi.mock('../../../lib/tauri', () => ({
  checkAccessibilityPermission: vi.fn().mockResolvedValue(false),
  requestAccessibilityPermission: vi.fn().mockResolvedValue(true),
  waitForAccessibilityPermission: vi.fn().mockResolvedValue(true),
  resumeHotkey: vi.fn().mockResolvedValue(undefined),
}))

beforeEach(() => {
  Object.assign(mockConfig, {
    hotkey: 'Fn',
    output_mode: 'keyboard',
    insertion_strategy: 'auto',
    stt_provider: 'openai-whisper',
    hotkeys: {
      dictation: { primary: 'Fn', modifiers: [] },
    },
  })
  Object.defineProperty(window.navigator, 'platform', {
    value: 'MacIntel',
    configurable: true,
  })
})

afterEach(() => cleanup())

describe('PermissionsStep', () => {
  it('shows compact relevant permissions without a settings-style dashboard', async () => {
    render(<PermissionsStep />)

    expect(screen.getByText('Microphone')).toBeInTheDocument()
    expect(screen.getByText('Text output')).toBeInTheDocument()
    expect(screen.getByText('Browser apps')).toBeInTheDocument()
    expect(screen.queryByText('Apple Speech')).not.toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Fix' })).toBeInTheDocument()
  })

  it('shows Apple Speech only when that provider is selected', () => {
    Object.assign(mockConfig, { stt_provider: 'apple-speech' })

    render(<PermissionsStep />)

    expect(screen.getByText('Apple Speech')).toBeInTheDocument()
  })

  it('marks text output ready after granting Accessibility', async () => {
    const tauri = await import('../../../lib/tauri')

    render(<PermissionsStep />)
    fireEvent.click(await screen.findByRole('button', { name: 'Fix' }))

    await waitFor(() => {
      expect(tauri.requestAccessibilityPermission).toHaveBeenCalled()
      expect(tauri.resumeHotkey).toHaveBeenCalled()
      expect(screen.getAllByText('Ready').length).toBeGreaterThan(0)
    })
  })

  it('offers the Accessibility fix for clipboard paste with a non-Fn shortcut', async () => {
    Object.assign(mockConfig, {
      hotkey: 'Ctrl+/',
      output_mode: 'clipboard',
      insertion_strategy: 'clipboardPaste',
      hotkeys: {
        dictation: { primary: '/', modifiers: ['Ctrl'] },
      },
    })

    render(<PermissionsStep />)

    expect(await screen.findByRole('button', { name: 'Fix' })).toBeInTheDocument()
  })

  it('does not request Accessibility for copy-only output with a non-Fn shortcut', async () => {
    Object.assign(mockConfig, {
      hotkey: 'Ctrl+/',
      output_mode: 'clipboard',
      insertion_strategy: 'clipboardCopyOnly',
      hotkeys: {
        dictation: { primary: '/', modifiers: ['Ctrl'] },
      },
    })

    render(<PermissionsStep />)

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Fix' })).not.toBeInTheDocument()
    })
  })
})
