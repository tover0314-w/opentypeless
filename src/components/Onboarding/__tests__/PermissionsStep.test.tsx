import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PermissionsStep } from '../PermissionsStep'
import { checkAccessibilityPermission } from '../../../lib/tauri'

const mockConfig = {
  hotkey: 'Fn',
  output_mode: 'keyboard',
  insertion_strategy: 'auto',
  stt_provider: 'cloud',
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
    stt_provider: 'cloud',
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
  it('keeps a retry action after hotkey registration fails without requesting permission again', async () => {
    const tauri = await import('../../../lib/tauri')
    vi.mocked(tauri.resumeHotkey).mockRejectedValueOnce(new Error('Synthetic registration failure'))
    render(<PermissionsStep />)
    fireEvent.click(await screen.findByRole('button', { name: 'Fix' }))
    await screen.findByRole('alert')
    const requests = vi.mocked(tauri.requestAccessibilityPermission).mock.calls.length
    const registrations = vi.mocked(tauri.resumeHotkey).mock.calls.length
    vi.mocked(checkAccessibilityPermission).mockResolvedValueOnce(true)
    fireEvent.click(screen.getByRole('button', { name: 'Fix' }))
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
    expect(vi.mocked(tauri.requestAccessibilityPermission).mock.calls.length).toBe(requests)
    expect(vi.mocked(tauri.resumeHotkey).mock.calls.length).toBe(registrations + 1)
  })

  it('does not treat a malformed permission check as granted', async () => {
    vi.mocked(checkAccessibilityPermission).mockResolvedValueOnce('false' as unknown as boolean)
    render(<PermissionsStep />)
    expect(await screen.findByRole('button', { name: 'Fix' })).toBeInTheDocument()
    const row = screen.getByText('Text output').parentElement!.parentElement!
    expect(within(row).queryByText('Ready')).toBeNull()
  })

  it('shows readable permission explanations in source-backed padded cards', async () => {
    render(<PermissionsStep />)
    const description = screen.getByText('Type into the app you are using.')
    expect(description.className).not.toContain('truncate')
    expect(description.className).toContain('text-[14px]')
    const card = description.parentElement!.parentElement!
    expect(card.className).toContain('p-6')
    expect(card.className).toContain('rounded-[12px]')
    await screen.findByRole('button', { name: 'Fix' })
  })

  it('reports a failed permission request without claiming readiness', async () => {
    const tauri = await import('../../../lib/tauri')
    vi.mocked(tauri.requestAccessibilityPermission).mockRejectedValueOnce(
      new Error('Synthetic permission failure'),
    )
    render(<PermissionsStep />)
    fireEvent.click(await screen.findByRole('button', { name: 'Fix' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('common.saveFailed')
    expect(screen.getByRole('button', { name: 'Fix' })).toBeEnabled()
  })

  it('does not claim text output is ready while its permission check is pending', () => {
    vi.mocked(checkAccessibilityPermission).mockReturnValueOnce(new Promise(() => {}))
    render(<PermissionsStep />)
    const row = screen.getByText('Text output').closest('div')!.parentElement!
    expect(within(row).queryByText('Ready')).toBeNull()
    expect(within(row).queryByRole('button', { name: 'Fix' })).toBeNull()
  })

  it('ignores a late denied check after switching to copy-only output', async () => {
    let finishCheck: (trusted: boolean) => void = () => {}
    vi.mocked(checkAccessibilityPermission).mockReturnValueOnce(
      new Promise<boolean>((resolve) => {
        finishCheck = resolve
      }),
    )
    const { rerender } = render(<PermissionsStep />)
    Object.assign(mockConfig, {
      hotkey: 'Ctrl+/',
      insertion_strategy: 'clipboardCopyOnly',
      hotkeys: { dictation: { primary: '/', modifiers: ['Ctrl'] } },
    })
    rerender(<PermissionsStep />)
    await act(async () => finishCheck(false))
    expect(screen.queryByRole('button', { name: 'Fix' })).toBeNull()
  })

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
