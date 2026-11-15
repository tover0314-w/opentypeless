import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MicrophoneSelect } from '../MicrophoneSelect'
import { useAppStore } from '../../../stores/appStore'
import {
  getMicrophonePreviewLevel,
  startMicrophonePreview,
  stopMicrophonePreview,
} from '../../../lib/tauri'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('../../../lib/tauri', () => ({
  listMicrophones: vi.fn().mockResolvedValue([
    { name: 'Built-in mic', isDefault: true, connection: 'builtIn' },
    { name: 'USB mic', isDefault: false, connection: 'usb' },
  ]),
  startMicrophonePreview: vi.fn().mockResolvedValue(1),
  getMicrophonePreviewLevel: vi.fn().mockResolvedValue(0.5),
  stopMicrophonePreview: vi.fn().mockResolvedValue(undefined),
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  useAppStore.setState(useAppStore.getInitialState())
  window.location.hash = ''
})

describe('microphone dialog', () => {
  it('lets only the settings instance consume a settings route request', async () => {
    window.location.hash = '#/settings?pane=general&dialog=microphone&open=shared'
    render(
      <>
        <MicrophoneSelect followSettingsRoute={false} />
        <MicrophoneSelect />
      </>,
    )
    await screen.findByRole('radio', { name: 'USB mic' })
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
    const triggers = screen.getAllByRole('button', { name: 'settings.microphone' })
    expect(triggers[0]).toHaveAttribute('aria-expanded', 'false')
    expect(triggers[1]).toHaveAttribute('aria-expanded', 'true')
    await waitFor(() => expect(startMicrophonePreview).toHaveBeenCalledOnce())
  })

  it('ignores a delayed level from the previous device after switching previews', async () => {
    let finishPreviousLevel: (level: number) => void = () => {}
    vi.mocked(startMicrophonePreview).mockResolvedValueOnce(41).mockResolvedValueOnce(42)
    vi.mocked(getMicrophonePreviewLevel)
      .mockReturnValueOnce(
        new Promise<number>((resolve) => {
          finishPreviousLevel = resolve
        }),
      )
      .mockResolvedValueOnce(0.8)
    render(<MicrophoneSelect />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    await waitFor(() => expect(getMicrophonePreviewLevel).toHaveBeenCalledWith(41))
    fireEvent.click(screen.getByRole('radio', { name: 'USB mic' }))
    await waitFor(() => expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '80'))
    await act(async () => finishPreviousLevel(0.1))
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '80')
    expect(stopMicrophonePreview).toHaveBeenCalledWith(41)
    fireEvent.click(screen.getByRole('button', { name: 'microphoneDialog.cancel' }))
    expect(stopMicrophonePreview).toHaveBeenCalledWith(42)
  })

  it('moves the draft and focus with arrow keys without saving the device', async () => {
    render(<MicrophoneSelect />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    const usb = await screen.findByRole('radio', { name: 'USB mic' })
    const system = screen.getByRole('radio', { name: 'settings.micAutoDetect' })
    system.focus()
    fireEvent.keyDown(system, { key: 'ArrowDown' })
    const builtIn = screen.getByRole('radio', { name: 'Built-in mic' })
    expect(builtIn).toHaveFocus()
    expect(builtIn).toHaveAttribute('aria-checked', 'true')
    fireEvent.keyDown(builtIn, { key: 'ArrowUp' })
    expect(system).toHaveFocus()
    fireEvent.keyDown(system, { key: 'ArrowUp' })
    expect(usb).toHaveFocus()
    expect(usb).toHaveAttribute('tabindex', '0')
    expect(system).toHaveAttribute('tabindex', '-1')
    expect(useAppStore.getState().config.microphone_device).toBe('')
    fireEvent.click(screen.getByRole('button', { name: 'microphoneDialog.cancel' }))
  })

  it('stops the preview and clears its meter when polling fails', async () => {
    vi.mocked(getMicrophonePreviewLevel).mockRejectedValueOnce(new Error('Device disconnected'))
    render(<MicrophoneSelect />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    await waitFor(() => {
      expect(screen.getByText('microphoneDialog.previewError')).toBeInTheDocument()
      expect(stopMicrophonePreview).toHaveBeenCalledWith(1)
      expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '0')
    })
  })

  it('releases an existing preview when dictation starts', async () => {
    render(<MicrophoneSelect />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    await waitFor(() => expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '50'))
    act(() => useAppStore.setState({ pipelineState: 'recording' }))
    await waitFor(() => {
      expect(stopMicrophonePreview).toHaveBeenCalledWith(1)
      expect(screen.getByText('microphoneDialog.busy')).toBeInTheDocument()
      expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '0')
    })
  })

  it('opens directly from a microphone settings deep link', async () => {
    window.location.hash = '#/settings?pane=general&dialog=microphone&open=first'
    render(<MicrophoneSelect />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await screen.findByRole('radio', { name: /USB mic/ })
  })

  it('opens again for a new request without reopening a cancelled request', async () => {
    window.location.hash = '#/settings?pane=general&dialog=microphone&open=first'
    render(<MicrophoneSelect />)
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: 'microphoneDialog.cancel' }))
    fireEvent(window, new Event('hashchange'))
    expect(screen.queryByRole('dialog')).toBeNull()
    act(() => {
      window.location.hash = '#/settings?pane=general&dialog=microphone&open=second'
      window.dispatchEvent(new Event('hashchange'))
    })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('does not preview a deep-linked dialog during Ask recording', async () => {
    window.location.hash = '#/settings?pane=general&dialog=microphone&open=busy'
    useAppStore.setState({ pipelineState: 'ask_recording' })
    render(<MicrophoneSelect />)
    await screen.findByRole('radio', { name: /USB mic/ })
    expect(startMicrophonePreview).not.toHaveBeenCalled()
    expect(screen.getByText('microphoneDialog.busy')).toBeInTheDocument()
  })

  it('stops a delayed preview that becomes ready after the dialog closes', async () => {
    let resolveStart: (session: number) => void = () => {}
    vi.mocked(startMicrophonePreview).mockImplementationOnce(
      () =>
        new Promise<number>((resolve) => {
          resolveStart = resolve
        }),
    )
    render(<MicrophoneSelect />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    await waitFor(() => expect(startMicrophonePreview).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: 'microphoneDialog.cancel' }))
    await act(async () => resolveStart(99))
    expect(stopMicrophonePreview).toHaveBeenCalledWith(99)
    expect(getMicrophonePreviewLevel).not.toHaveBeenCalledWith(99)
  })

  it('shows the actual preview level rather than a decorative animation', async () => {
    render(<MicrophoneSelect />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    await waitFor(() => {
      expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', '50')
    })
  })

  it('previews the draft device and only changes config on Save', async () => {
    render(<MicrophoneSelect />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    await screen.findByRole('radio', { name: /USB mic/ })
    fireEvent.click(screen.getByRole('radio', { name: /USB mic/ }))
    expect(useAppStore.getState().config.microphone_device).toBe('')
    await waitFor(() => expect(startMicrophonePreview).toHaveBeenCalledWith('USB mic'))
    fireEvent.click(screen.getByRole('button', { name: 'microphoneDialog.save' }))
    expect(useAppStore.getState().config.microphone_device).toBe('USB mic')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('cancels without saving and stops the preview', async () => {
    render(<MicrophoneSelect />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    await waitFor(() => expect(startMicrophonePreview).toHaveBeenCalled())
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'microphoneDialog.cancel' }))
    })
    expect(useAppStore.getState().config.microphone_device).toBe('')
    expect(stopMicrophonePreview).toHaveBeenCalledWith(1)
  })

  it('does not preview while a voice operation is active', async () => {
    useAppStore.setState({ pipelineState: 'recording' })
    render(<MicrophoneSelect />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    await screen.findByRole('radio', { name: /USB mic/ })
    expect(startMicrophonePreview).not.toHaveBeenCalled()
    expect(screen.getByText('microphoneDialog.busy')).toBeInTheDocument()
  })
})
