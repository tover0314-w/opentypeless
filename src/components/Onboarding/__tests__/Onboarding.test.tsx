import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Onboarding } from '../index'
import { saveOnboardingConfig, saveOnboardingCompleted } from '../../../lib/tauri'
import { invoke } from '@tauri-apps/api/core'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn().mockResolvedValue(undefined) }))

const mockStore = {
  onboardingStep: 5,
  setOnboardingStep: vi.fn(),
  setOnboardingCompleted: vi.fn(),
  sttTestStatus: 'idle',
  llmTestStatus: 'idle',
  onboardingMode: 'cloud',
  setOnboardingMode: vi.fn(),
  updateConfig: vi.fn(),
  setConfig: vi.fn(),
  setSavedConfig: vi.fn(),
  configCommit: null as null | { revision: number },
  config: { polish_enabled: true },
}
const mockUser = { id: 'test-user' }

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  motion: {
    div: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('../OnboardingLayout', () => ({
  OnboardingLayout: ({
    children,
    onBack,
    onNext,
    onClose,
    onSkip,
  }: {
    children: React.ReactNode
    onBack: () => void
    onNext: () => void
    onClose: () => void
    onSkip: () => void
  }) => (
    <div>
      <button type="button" onClick={onBack}>
        Back
      </button>
      <button type="button" onClick={onNext}>
        Next
      </button>
      <button type="button" onClick={onClose}>
        Close
      </button>
      <button type="button" onClick={onSkip}>
        Skip
      </button>
      {children}
    </div>
  ),
}))

vi.mock('../WelcomeStep', () => ({ WelcomeStep: () => <div>Welcome</div> }))
vi.mock('../AccountStep', () => ({ AccountStep: () => <div>Account</div> }))
vi.mock('../ModeSelectStep', () => ({ ModeSelectStep: () => <div>Mode</div> }))
vi.mock('../SttSetupStep', () => ({ SttSetupStep: () => <div>STT</div> }))
vi.mock('../LlmSetupStep', () => ({ LlmSetupStep: () => <div>LLM</div> }))
vi.mock('../PermissionsStep', () => ({ PermissionsStep: () => <div>Permissions</div> }))
vi.mock('../GuidedPracticeFlow', () => ({ GuidedPracticeFlow: () => <div>Quick Test</div> }))
vi.mock('../DoneStep', () => ({ DoneStep: () => <div>Done</div> }))

vi.mock('../../../stores/appStore', () => ({
  useAppStore: Object.assign(
    (selector: (state: typeof mockStore) => unknown) => selector(mockStore),
    { getState: () => mockStore },
  ),
}))

vi.mock('../../../stores/authStore', () => ({
  useAuthStore: (selector: (state: { user: { id: string } }) => unknown) =>
    selector({ user: mockUser }),
}))

vi.mock('../../../lib/tauri', () => ({
  saveOnboardingConfig: vi.fn().mockResolvedValue({}),
  saveOnboardingCompleted: vi.fn().mockResolvedValue(undefined),
}))

beforeEach(() => {
  vi.clearAllMocks()
  mockStore.onboardingStep = 5
  mockStore.onboardingMode = 'cloud'
  mockStore.config = { polish_enabled: true }
  mockStore.configCommit = null
  mockStore.sttTestStatus = 'idle'
  mockStore.llmTestStatus = 'idle'
  mockStore.setOnboardingStep.mockReset()
})

afterEach(() => cleanup())

describe('Onboarding cloud navigation', () => {
  it('does not advance unverified practice with the normal Next action', async () => {
    mockStore.onboardingStep = 6
    render(<Onboarding />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Next' })))
    expect(saveOnboardingConfig).not.toHaveBeenCalled()
    expect(mockStore.setOnboardingStep).not.toHaveBeenCalled()
  })

  it('skips practice to the summary without reporting onboarding completion', async () => {
    mockStore.onboardingStep = 6
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    await waitFor(() => expect(mockStore.setOnboardingStep).toHaveBeenCalledWith(7))
    expect(saveOnboardingCompleted).not.toHaveBeenCalled()
    expect(mockStore.setOnboardingCompleted).not.toHaveBeenCalled()
  })

  it('does not replay the saved snapshot after the version stream committed it', async () => {
    mockStore.onboardingStep = 7
    mockStore.configCommit = { revision: 2 }
    vi.mocked(saveOnboardingConfig).mockResolvedValueOnce(mockStore.config as never)
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(mockStore.setOnboardingCompleted).toHaveBeenCalledWith(true))
    expect(mockStore.setConfig).not.toHaveBeenCalled()
    expect(mockStore.setSavedConfig).not.toHaveBeenCalled()
  })

  it('allows its owned normalized configuration echo without bypassing the completion marker', async () => {
    mockStore.onboardingStep = 7
    mockStore.configCommit = { revision: 2 }
    const normalized = { polish_enabled: false }
    let finish!: (saved: unknown) => void
    vi.mocked(saveOnboardingConfig).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }) as never,
    )
    const { rerender } = render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    mockStore.config = normalized
    rerender(<Onboarding />)
    await act(async () => finish(normalized))
    expect(saveOnboardingCompleted).toHaveBeenCalledOnce()
    expect(mockStore.setOnboardingCompleted).toHaveBeenCalledWith(true)
    expect(mockStore.setSavedConfig).not.toHaveBeenCalled()
  })

  it('continues completion when a persisted echo replaces the draft with equal values', async () => {
    let finishSave: ((config: unknown) => void) | undefined
    vi.mocked(saveOnboardingConfig).mockReturnValueOnce(
      new Promise((resolve) => {
        finishSave = resolve
      }) as never,
    )
    mockStore.onboardingStep = 7
    const original = mockStore.config
    const { rerender } = render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    mockStore.config = { ...original }
    rerender(<Onboarding />)
    await act(async () => finishSave?.(original))
    expect(saveOnboardingCompleted).toHaveBeenCalledOnce()
    expect(mockStore.setOnboardingCompleted).toHaveBeenCalledWith(true)
  })

  it('does not complete using a saved draft superseded by newer settings', async () => {
    let finishSave: ((config: unknown) => void) | undefined
    vi.mocked(saveOnboardingConfig).mockReturnValueOnce(
      new Promise((resolve) => {
        finishSave = resolve
      }) as never,
    )
    mockStore.onboardingStep = 7
    const original = mockStore.config
    const { rerender } = render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    mockStore.config = { polish_enabled: false }
    rerender(<Onboarding />)
    await act(async () => finishSave?.(original))
    expect(saveOnboardingCompleted).not.toHaveBeenCalled()
    expect(mockStore.setConfig).not.toHaveBeenCalled()
    expect(mockStore.setOnboardingCompleted).not.toHaveBeenCalled()
  })

  it.each([3, 4])(
    'does not advance step %s when its successful connection becomes invalid during saving',
    async (step) => {
      let finishSave: ((config: unknown) => void) | undefined
      vi.mocked(saveOnboardingConfig).mockReturnValueOnce(
        new Promise((resolve) => {
          finishSave = resolve
        }) as never,
      )
      mockStore.onboardingStep = step
      mockStore.onboardingMode = 'byok'
      mockStore.sttTestStatus = 'success'
      mockStore.llmTestStatus = 'success'
      const { rerender } = render(<Onboarding />)
      fireEvent.click(screen.getByRole('button', { name: 'Next' }))
      mockStore.sttTestStatus = 'error'
      mockStore.llmTestStatus = 'error'
      rerender(<Onboarding />)
      await act(async () => finishSave?.({}))
      expect(mockStore.setOnboardingStep).not.toHaveBeenCalled()
      expect(screen.queryByRole('alert')).toBeNull()
    },
  )

  it.each([
    { step: 2, mode: null },
    { step: 3, mode: 'byok' },
    { step: 4, mode: 'byok' },
  ])('does not advance step $step when its prerequisites are not met', async ({ step, mode }) => {
    mockStore.onboardingStep = step
    mockStore.onboardingMode = mode as typeof mockStore.onboardingMode
    mockStore.sttTestStatus = 'idle'
    mockStore.llmTestStatus = 'idle'
    render(<Onboarding />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Next' })))
    expect(saveOnboardingConfig).not.toHaveBeenCalled()
    expect(mockStore.setOnboardingStep).not.toHaveBeenCalled()
  })

  it('does not change provider drafts before Cloud settings have been saved', async () => {
    let finishSave: ((config: unknown) => void) | undefined
    vi.mocked(saveOnboardingConfig).mockReturnValueOnce(
      new Promise((resolve) => {
        finishSave = resolve
      }) as never,
    )
    mockStore.onboardingStep = 2
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(mockStore.updateConfig).not.toHaveBeenCalled()
    expect(mockStore.setOnboardingStep).not.toHaveBeenCalled()
    await act(async () => finishSave?.({ stt_provider: 'cloud', llm_provider: 'cloud' }))
    expect(mockStore.updateConfig).toHaveBeenCalledWith({
      stt_provider: 'cloud',
      llm_provider: 'cloud',
    })
    expect(mockStore.setOnboardingStep).toHaveBeenCalledWith(5)
  })

  it('saves before switching to BYOK so skip-login preserves navigation ownership', async () => {
    let finishSave: ((config: unknown) => void) | undefined
    vi.mocked(saveOnboardingConfig).mockReturnValueOnce(
      new Promise((resolve) => {
        finishSave = resolve
      }) as never,
    )
    mockStore.onboardingStep = 1
    const { rerender } = render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    expect(mockStore.setOnboardingMode).not.toHaveBeenCalled()
    rerender(<Onboarding />)
    await act(async () => finishSave?.({}))
    expect(mockStore.setOnboardingMode).toHaveBeenCalledWith('byok')
    expect(mockStore.setOnboardingStep).toHaveBeenCalledWith(3)
    expect(saveOnboardingCompleted).not.toHaveBeenCalled()
  })

  it('saves its draft before closing without marking onboarding complete', async () => {
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(invoke).toHaveBeenCalledWith('plugin:process|exit', { code: 0 }))
    expect(vi.mocked(saveOnboardingConfig).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(invoke).mock.invocationCallOrder[0],
    )
    expect(saveOnboardingCompleted).not.toHaveBeenCalled()
  })

  it('does not exit when the current draft cannot be saved', async () => {
    vi.mocked(saveOnboardingConfig).mockRejectedValueOnce(new Error('disk full'))
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('common.saveFailed'))
    expect(invoke).not.toHaveBeenCalled()
  })

  it('skips LLM setup when AI polish is already disabled', async () => {
    mockStore.onboardingStep = 3
    mockStore.onboardingMode = 'byok'
    mockStore.config.polish_enabled = false
    mockStore.sttTestStatus = 'success'
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(mockStore.setOnboardingStep).toHaveBeenCalledWith(5))
    expect(mockStore.updateConfig).not.toHaveBeenCalled()
  })

  it('returns to STT setup from Permissions when AI polish is disabled', async () => {
    mockStore.onboardingStep = 5
    mockStore.onboardingMode = 'byok'
    mockStore.config.polish_enabled = false
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Back' }))
    await waitFor(() => expect(mockStore.setOnboardingStep).toHaveBeenCalledWith(3))
  })

  it.each([
    { step: 2, action: 'Next' },
    { step: 5, action: 'Next' },
    { step: 5, action: 'Back' },
  ])('keeps step $step open when $action cannot save settings', async ({ step, action }) => {
    mockStore.onboardingStep = step
    vi.mocked(saveOnboardingConfig).mockRejectedValueOnce(new Error('vault unavailable'))
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: action }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('common.saveFailed'))
    expect(mockStore.setOnboardingStep).not.toHaveBeenCalled()
    expect(saveOnboardingCompleted).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: action }))
    await waitFor(() => expect(mockStore.setOnboardingStep).toHaveBeenCalledTimes(1))
  })

  it('ignores repeated completion clicks while settings are being saved', async () => {
    let finishSave: (config: unknown) => void = () => {}
    vi.mocked(saveOnboardingConfig).mockReturnValueOnce(
      new Promise((resolve) => {
        finishSave = resolve
      }) as never,
    )
    mockStore.onboardingStep = 7
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(saveOnboardingConfig).toHaveBeenCalledTimes(1)
    await act(async () => finishSave({}))
    expect(saveOnboardingCompleted).toHaveBeenCalledTimes(1)
    expect(mockStore.setOnboardingCompleted).toHaveBeenCalledTimes(1)
  })

  it('does not mark onboarding complete after leaving during its settings save', async () => {
    let finishSave: ((config: unknown) => void) | undefined
    vi.mocked(saveOnboardingConfig).mockReturnValueOnce(
      new Promise((resolve) => {
        finishSave = resolve
      }) as never,
    )
    mockStore.onboardingStep = 7
    const { unmount } = render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    unmount()
    await act(async () => finishSave?.({}))
    expect(saveOnboardingCompleted).not.toHaveBeenCalled()
    expect(mockStore.setOnboardingCompleted).not.toHaveBeenCalled()
  })

  it('does not complete an obsolete step after navigation changes during saving', async () => {
    let finishSave: ((config: unknown) => void) | undefined
    vi.mocked(saveOnboardingConfig).mockReturnValueOnce(
      new Promise((resolve) => {
        finishSave = resolve
      }) as never,
    )
    mockStore.onboardingStep = 7
    const { rerender } = render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    mockStore.onboardingStep = 5
    rerender(<Onboarding />)
    await act(async () => finishSave?.({}))
    expect(saveOnboardingCompleted).not.toHaveBeenCalled()
    expect(mockStore.setOnboardingCompleted).not.toHaveBeenCalled()
    expect(mockStore.setOnboardingStep).not.toHaveBeenCalled()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('stays in onboarding when its completion marker cannot be saved', async () => {
    mockStore.onboardingStep = 7
    vi.mocked(saveOnboardingCompleted).mockRejectedValueOnce(new Error('disk full'))
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('common.saveFailed'))
    expect(mockStore.setOnboardingCompleted).not.toHaveBeenCalled()
  })

  it('keeps onboarding open and reports a failed credential save', async () => {
    mockStore.onboardingStep = 7
    vi.mocked(saveOnboardingConfig).mockRejectedValueOnce(new Error('vault unavailable'))
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('common.saveFailed'))
    expect(saveOnboardingCompleted).not.toHaveBeenCalled()
    expect(mockStore.setOnboardingCompleted).not.toHaveBeenCalled()
  })
  it('replaces the onboarding draft with sanitized settings before entering the app', async () => {
    mockStore.onboardingStep = 7
    const saved = { ...mockStore.config, stt_api_key: '', stt_custom_api_key: '', llm_api_key: '' }
    vi.mocked(saveOnboardingConfig).mockResolvedValueOnce(
      saved as Awaited<ReturnType<typeof saveOnboardingConfig>>,
    )
    render(<Onboarding />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(mockStore.setOnboardingCompleted).toHaveBeenCalledWith(true))
    expect(saveOnboardingCompleted).toHaveBeenCalled()
    expect(mockStore.setConfig).toHaveBeenCalledWith(saved)
    expect(mockStore.setSavedConfig).toHaveBeenCalledWith(saved)
  })
  it('returns from Permissions to Mode Select because cloud skips provider setup', async () => {
    render(<Onboarding />)

    fireEvent.click(screen.getByRole('button', { name: 'Back' }))

    await waitFor(() => expect(mockStore.setOnboardingStep).toHaveBeenCalledWith(2))
  })

  it('returns from Quick Test to Permissions', async () => {
    mockStore.onboardingStep = 6
    render(<Onboarding />)

    fireEvent.click(screen.getByRole('button', { name: 'Back' }))

    await waitFor(() => expect(mockStore.setOnboardingStep).toHaveBeenCalledWith(5))
  })
})
