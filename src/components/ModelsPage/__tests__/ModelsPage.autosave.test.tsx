import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ModelsPage } from '../index'
import { useAppStore } from '../../../stores/appStore'
import { getConfig, updateConfig } from '../../../lib/tauri'
import { AUTOSAVE_DELAY_MS, enqueueConfigSave } from '../../Settings/shared/useAutoSaveConfig'
import { ToastContainer } from '../../Toast'

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-i18next')>()),
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('../../../lib/tauri', () => ({
  updateConfig: vi.fn(),
  getConfig: vi.fn(),
  getHotkeyRegistrationError: vi.fn().mockResolvedValue(null),
  setAutoStart: vi.fn(),
}))
vi.mock('../../Settings/SttPane', () => ({
  SttPane: () => (
    <button
      type="button"
      onClick={() => useAppStore.getState().updateConfig({ stt_provider: 'deepgram' })}
    >
      Change speech provider
    </button>
  ),
}))
vi.mock('../../Settings/LlmPane', () => ({
  LlmPane: () => <div />,
}))

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  vi.mocked(updateConfig).mockResolvedValue(undefined)
  useAppStore.setState(useAppStore.getInitialState())
})

afterEach(async () => {
  cleanup()
  await enqueueConfigSave()
  vi.useRealTimers()
})

describe('model page autosave integration', () => {
  it('initializes its baseline and saves edits only after the debounce', async () => {
    render(<ModelsPage />)
    expect(useAppStore.getState().savedConfig).toBe(useAppStore.getState().config)
    fireEvent.click(screen.getByRole('button', { name: 'Change speech provider' }))
    const draft = useAppStore.getState().config
    await act(() => vi.advanceTimersByTimeAsync(AUTOSAVE_DELAY_MS - 1))
    expect(updateConfig).not.toHaveBeenCalled()
    await act(() => vi.advanceTimersByTimeAsync(1))
    expect(updateConfig).toHaveBeenCalledExactlyOnceWith(draft, expect.any(String))
    expect(useAppStore.getState().savedConfig).toBe(draft)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('reports a rejected save and reloads the native baseline', async () => {
    const baseline = useAppStore.getState().config
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Synthetic save failure'))
    vi.mocked(getConfig).mockResolvedValueOnce(baseline)
    render(
      <>
        <ModelsPage />
        <ToastContainer />
      </>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Change speech provider' }))
    await act(() => vi.advanceTimersByTimeAsync(AUTOSAVE_DELAY_MS))
    expect(screen.getByRole('alert')).toHaveTextContent('Synthetic save failure')
    expect(getConfig).toHaveBeenCalledTimes(1)
    expect(useAppStore.getState().config).toStrictEqual(baseline)
    expect(useAppStore.getState().savedConfig).toBe(baseline)
  })

  it('flushes a pending edit on navigation without a duplicate delayed save', async () => {
    const { unmount } = render(<ModelsPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Change speech provider' }))
    const draft = useAppStore.getState().config
    await act(async () => {
      unmount()
      await enqueueConfigSave()
    })
    await act(() => vi.advanceTimersByTimeAsync(AUTOSAVE_DELAY_MS))
    expect(updateConfig).toHaveBeenCalledExactlyOnceWith(draft, expect.any(String))
    expect(useAppStore.getState().savedConfig).toBe(draft)
  })
})
