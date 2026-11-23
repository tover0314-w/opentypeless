import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QuickSettings } from '../QuickSettings'
import { useAppStore } from '../../../stores/appStore'
import * as tauri from '../../../lib/tauri'
import { chooseOption } from '../../shared/__tests__/selectTestUtils'

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-i18next')>()),
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('../../../lib/tauri', () => ({
  updateConfig: vi.fn().mockResolvedValue(undefined),
  getConfig: vi.fn(),
  getHotkeyRegistrationError: vi.fn().mockResolvedValue(null),
  setAutoStart: vi.fn().mockResolvedValue(undefined),
  listMicrophones: vi.fn().mockResolvedValue([]),
  startMicrophonePreview: vi.fn(),
  getMicrophonePreviewLevel: vi.fn(),
  stopMicrophonePreview: vi.fn(),
}))

beforeEach(() => {
  useAppStore.setState(useAppStore.getInitialState())
  useAppStore.getState().setSavedConfig(useAppStore.getState().config)
  vi.clearAllMocks()
})

afterEach(async () => {
  await act(async () => cleanup())
})

describe('home quick settings', () => {
  it('restores backend output settings when saving fails', async () => {
    const config = useAppStore.getState().config
    vi.mocked(tauri.updateConfig).mockRejectedValueOnce(new Error('Save failed'))
    vi.mocked(tauri.getConfig).mockResolvedValueOnce(config)
    render(<QuickSettings />)
    const mode = config.output_mode === 'keyboard' ? 'clipboard' : 'keyboard'
    chooseOption(screen.getByRole('combobox', { name: 'generalPane.outputMode' }), mode)
    await waitFor(() => expect(tauri.getConfig).toHaveBeenCalled())
    await waitFor(() => expect(useAppStore.getState().config.output_mode).toBe(config.output_mode))
    expect(useAppStore.getState().config.insertion_strategy).toBe(config.insertion_strategy)
  })

  it('changes trigger mode and autosaves without dropping extra shortcuts', async () => {
    const config = useAppStore.getState().config
    const bindings = [config.hotkeys.dictation, { primary: '9', modifiers: ['Ctrl', 'Alt'] }]
    useAppStore.setState({
      config: {
        ...config,
        hotkey_mode: 'toggle',
        hotkeys: { ...config.hotkeys, dictationMode: 'toggle', dictationBindings: bindings },
      },
    })
    useAppStore.getState().setSavedConfig(useAppStore.getState().config)
    render(<QuickSettings />)
    chooseOption(screen.getByRole('combobox', { name: 'generalPane.triggerMode' }), 'hold')
    expect(useAppStore.getState().config.hotkey_mode).toBe('hold')
    expect(useAppStore.getState().config.hotkeys.dictationMode).toBe('hold')
    expect(useAppStore.getState().config.hotkeys.dictationBindings).toEqual(bindings)
    await waitFor(() => expect(tauri.updateConfig).toHaveBeenCalled())
  })

  it('keeps output strategy synchronized and does not preview a microphone on mount', async () => {
    render(<QuickSettings />)
    expect(tauri.listMicrophones).not.toHaveBeenCalled()
    expect(tauri.startMicrophonePreview).not.toHaveBeenCalled()
    chooseOption(screen.getByRole('combobox', { name: 'generalPane.outputMode' }), 'clipboard')
    expect(useAppStore.getState().config.insertion_strategy).toBe('clipboardPaste')
    chooseOption(screen.getByRole('combobox', { name: 'generalPane.outputMode' }), 'keyboard')
    expect(useAppStore.getState().config.insertion_strategy).toBe('auto')
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'microphoneDialog.cancel' }))
  })
})
