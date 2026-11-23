import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, fireEvent, waitFor, cleanup, within } from '@testing-library/react'
import { LlmPane } from '../LlmPane'
import { chooseOption, selectedValue } from '../../shared/__tests__/selectTestUtils'
import * as tauri from '../../../lib/tauri'

// Mock Tauri
vi.mock('../../../lib/tauri')

// Mock i18n
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: any) => {
      const translations: Record<string, string> = {
        'settings.provider': 'Provider',
        'nav.upgrade': 'Upgrade',
        'settings.apiKey': 'API Key',
        'settings.model': 'Model',
        'settings.baseUrl': 'Base URL',
        'settings.test': 'Test',
        'settings.enterApiKey': 'Enter API Key',
        'settings.connectionSuccess': 'Connection successful',
        'settings.connectionFailed': 'Connection failed',
        'settings.storedLocally': 'Stored locally',
        'settings.fetchModels': 'Fetch models',
        'settings.modelsAvailable': `${params?.count || 0} models available`,
        'settings.modelCertified': 'Optimized context and thought-aware support',
        'settings.modelBestEffort': 'Context and thought-aware support is best effort',
        'settings.llmModelPlaceholder': 'e.g. gpt-4o-mini',
        'settings.enableAiPolish': 'AI cleanup for dictation',
        'settings.enableAiPolishDesc': 'Cleans up dictation before output',
        'settings.contextAdaptation': 'Adapt writing to the current app',
        'settings.contextAdaptationHint': 'Uses a private local app category',
        'settings.contextAdaptationApps': 'Apps adapted by context',
        'settings.lastDictationContext': 'Last dictation context',
        'settings.browserAccessHint':
          'Allow browser access to use Gmail, Docs, and Slack Web modes.',
        'settings.appStyleMenu': 'App writing style',
        'settings.useDifferentWritingStyle': 'Use a different writing style',
        'settings.manageAppMappings': 'Manage app mappings',
        'settings.appStyleDialogTitle': 'Writing style for this app',
        'settings.polishStyle': 'Polish style',
        'settings.polishStyleMinimal': 'Minimal',
        'settings.polishStyleClean': 'Clean',
        'settings.polishStyleStructured': 'Structured',
        'settings.polishStyleProfessional': 'Professional',
        'settings.advancedPolishSettings': 'Advanced polish settings',
        'settings.advancedPolishSettingsDesc': 'Optional writing rules',
        'settings.customPolishInstructions': 'Custom polish instructions',
        'settings.customPolishInstructionsPlaceholder': 'Example prompt',
        'settings.customPolishInstructionsCount': `${params?.count || 0} / 2000 characters`,
        'settings.activeScene': `Active scene: ${params?.name || ''}`,
        'settings.clearActiveScene': 'Clear scene',
        'settings.translationMode': 'Always translate output',
        'settings.translationModeDesc': 'Translate each dictation result',
        'settings.selectedTextContext': 'Use selected text as context',
        'settings.selectedTextContextDesc': 'Use selected text for context',
        'settings.targetLanguage': 'Translate to',
        'settings.manageTranslationTargets': 'Manage languages',
        'settings.cloudLlmPro': 'Cloud LLM (Pro)',
        'settings.llmSignInHint':
          'Sign in and subscribe to Pro to use cloud AI polish. No API key needed.',
        'settings.llmUpgradeHint':
          'Upgrade to Pro for cloud AI polish and monthly usage. No API key needed.',
        'settings.llmProActive': 'Pro active — cloud AI polish is ready. No API key needed.',
        'settings.askAnything': 'Ask Anything',
        'settings.askAnythingDesc': 'Voice question, one-shot answer. No chat history.',
        'ask.ready': 'Ready to ask',
        'ask.listening': 'Listening',
        'ask.thinking': 'Thinking',
        'ask.voiceQuestion': 'Voice question',
        'ask.voiceQuestionDesc': 'Speak your question. Stop recording to answer.',
        'ask.transcriptLabel': 'Question transcript',
        'ask.answerLabel': 'Answer',
        'ask.manualFallback': 'Type instead',
        'ask.placeholder': 'Type a question, or use the capsule above.',
        'ask.recordQuestion': 'Record question',
        'ask.stopAndAsk': 'Stop and ask',
        'ask.send': 'Ask',
        'providers.llm.doubao': 'Doubao (Volcengine)',
      }
      return translations[key] || key
    },
  }),
}))

// Mock stores - must be done before importing the component
const mockAppStore = {
  config: {
    llm_provider: 'openai' as string,
    llm_api_key: '',
    llm_base_url: 'https://api.openai.com/v1',
    llm_model: 'gpt-4o-mini',
    polish_enabled: true,
    context_adaptation_enabled: true,
    polish_style: 'clean',
    polish_custom_prompt: '',
    polish_chinese_script: 'preserve',
    custom_scenes: [],
    active_scene: null as any,
    family_scene_assignments: [],
    translate_enabled: false,
    selected_text_enabled: false,
    target_lang: 'en',
    translation: { targets: ['en'], active_target: 'en' },
  },
  updateConfig: vi.fn(),
  setConfig: vi.fn(),
  setSavedConfig: vi.fn(),
  llmTestStatus: 'idle' as 'idle' | 'testing' | 'success' | 'error',
  setLlmTestStatus: vi.fn(),
  llmLatencyMs: null as number | null,
  setLlmLatencyMs: vi.fn(),
  llmModels: [] as string[],
  setLlmModels: vi.fn(),
  lastContext: null as any,
}

const mockAuthStore = {
  user: null as any,
  plan: null as any,
  source: 'free',
  cloudWordsLimit: 0,
  licenseStatus: null as any,
}

vi.mock('../../../stores/appStore', () => ({
  useAppStore: Object.assign(
    (selector: any) => {
      if (typeof selector === 'function') {
        return selector(mockAppStore)
      }
      return mockAppStore
    },
    { getState: () => mockAppStore },
  ),
}))

vi.mock('../../../stores/authStore', () => ({
  hasManagedCloudAccess: (state: typeof mockAuthStore) =>
    state.licenseStatus !== 'refunded' &&
    state.licenseStatus !== 'deactivated' &&
    ((state.source === 'creem' && state.cloudWordsLimit > 0) ||
      (state.source === 'appsumo' &&
        state.cloudWordsLimit > 0 &&
        state.licenseStatus === 'active') ||
      state.plan === 'pro'),
  useAuthStore: (selector: any) => {
    if (typeof selector === 'function') {
      return selector(mockAuthStore)
    }
    return mockAuthStore
  },
}))

describe('LlmPane', () => {
  it('preserves connection feedback when confirming the current model', () => {
    mockAppStore.llmTestStatus = 'success'
    mockAppStore.llmLatencyMs = 187
    mockAppStore.llmModels = ['gpt-4o-mini', 'another-model']
    render(<LlmPane />)
    const input = screen.getByRole('combobox', { name: 'Model' })
    fireEvent.focus(input)
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(mockAppStore.updateConfig).not.toHaveBeenCalled()
    expect(mockAppStore.setLlmTestStatus).not.toHaveBeenCalled()
    expect(mockAppStore.setLlmLatencyMs).not.toHaveBeenCalled()
    expect(screen.getByText('187ms')).toBeInTheDocument()
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('uses the existing connection failure text for a blank backend error', async () => {
    mockAppStore.config.llm_api_key = 'synthetic-key'
    vi.mocked(tauri.benchLlmConnection).mockRejectedValueOnce(new Error(' \n\t'))
    render(<LlmPane />)
    fireEvent.click(screen.getByRole('button', { name: 'Test' }))
    expect(await screen.findByText('Connection failed')).toBeInTheDocument()
  })

  it('redacts the tested API key from connection feedback and logs', async () => {
    const key = 'synthetic+$private-key'
    mockAppStore.config.llm_api_key = key
    vi.mocked(tauri.benchLlmConnection).mockRejectedValueOnce(
      new Error(`Rejected ${key}; repeated ${key}`),
    )
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(<LlmPane />)
      fireEvent.click(screen.getByRole('button', { name: 'Test' }))
      expect(
        await screen.findByText('Rejected [redacted]; repeated [redacted]'),
      ).toBeInTheDocument()
      expect(log).toHaveBeenCalledExactlyOnceWith('[LLM Test] Connection failed')
    } finally {
      log.mockRestore()
    }
  })

  it.each(['success', 'failure'] as const)(
    'ignores connection %s after configuration changes without a rerender',
    async (outcome) => {
      let finish: ((latency: number) => void) | undefined
      let fail: ((error: Error) => void) | undefined
      vi.mocked(tauri.benchLlmConnection).mockReturnValueOnce(
        new Promise<number>((resolve, reject) => {
          finish = resolve
          fail = reject
        }),
      )
      mockAppStore.config.llm_api_key = 'synthetic-key'
      render(<LlmPane />)
      fireEvent.click(screen.getByRole('button', { name: 'Test' }))
      mockAppStore.config = { ...mockAppStore.config, llm_base_url: 'https://new.example.test/v1' }
      await act(async () => {
        if (outcome === 'success') finish?.(123)
        else fail?.(new Error('Obsolete connection failure'))
      })
      expect(mockAppStore.setLlmLatencyMs).not.toHaveBeenCalledWith(123)
      expect(mockAppStore.setLlmTestStatus).not.toHaveBeenCalledWith('success')
      expect(mockAppStore.setLlmTestStatus).not.toHaveBeenCalledWith('error')
    },
  )

  it.each(['llm_provider', 'llm_base_url', 'llm_api_key'] as const)(
    'does not send model or connection requests from a stale %s render',
    async (field) => {
      mockAppStore.config.llm_api_key = 'synthetic-key'
      render(<LlmPane />)
      const modelsButton = screen.getByRole('button', { name: 'Fetch models' })
      const testButton = screen.getByRole('button', { name: 'Test' })
      await act(async () => {
        mockAppStore.config = { ...mockAppStore.config, [field]: 'changed-value' }
        fireEvent.click(modelsButton)
        fireEvent.click(testButton)
      })
      expect(tauri.fetchLlmModels).not.toHaveBeenCalled()
      expect(tauri.benchLlmConnection).not.toHaveBeenCalled()
    },
  )

  it.each(['llm_provider', 'llm_base_url', 'llm_api_key'] as const)(
    'rejects a model list after %s changes before the page rerenders',
    async (field) => {
      let finish: ((models: string[]) => void) | undefined
      vi.mocked(tauri.fetchLlmModels).mockReturnValueOnce(
        new Promise<string[]>((resolve) => {
          finish = resolve
        }),
      )
      mockAppStore.config.llm_api_key = 'synthetic-key'
      render(<LlmPane />)
      fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }))
      mockAppStore.config = { ...mockAppStore.config, [field]: 'changed-value' }
      await act(async () => finish?.(['obsolete-model']))
      expect(mockAppStore.setLlmModels).not.toHaveBeenCalledWith(['obsolete-model'])
    },
  )

  it('does not overwrite a newer shared model cache with a late request', async () => {
    let finish: ((models: string[]) => void) | undefined
    vi.mocked(tauri.fetchLlmModels).mockReturnValueOnce(
      new Promise<string[]>((resolve) => {
        finish = resolve
      }),
    )
    mockAppStore.config.llm_api_key = 'synthetic-key'
    const { rerender } = render(<LlmPane />)
    fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }))
    mockAppStore.llmModels = ['new-shared-model']
    rerender(<LlmPane />)
    await act(async () => finish?.(['old-request-model']))
    expect(mockAppStore.setLlmModels).not.toHaveBeenCalledWith(['old-request-model'])
  })

  it('keeps a newly typed key when the initial vault read finishes late', async () => {
    let finishRead: (secret: string) => void = () => {}
    vi.mocked(tauri.readCredential).mockReturnValueOnce(
      new Promise<string>((resolve) => {
        finishRead = resolve
      }),
    )
    render(<LlmPane />)
    fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'new-user-key' } })
    await act(async () => finishRead('old-vault-key'))
    expect(screen.getByLabelText('API Key')).toHaveValue('new-user-key')
  })

  beforeEach(() => {
    // Reset mock store state
    mockAppStore.config = {
      llm_provider: 'openai',
      llm_api_key: '',
      llm_base_url: 'https://api.openai.com/v1',
      llm_model: 'gpt-4o-mini',
      polish_enabled: true,
      context_adaptation_enabled: true,
      polish_style: 'clean',
      polish_custom_prompt: '',
      polish_chinese_script: 'preserve',
      custom_scenes: [],
      active_scene: null,
      family_scene_assignments: [],
      translate_enabled: false,
      selected_text_enabled: false,
      target_lang: 'en',
      translation: { targets: ['en'], active_target: 'en' },
    }
    mockAppStore.llmTestStatus = 'idle'
    mockAppStore.llmLatencyMs = null
    mockAppStore.llmModels = []
    mockAppStore.lastContext = null
    mockAuthStore.user = null
    mockAuthStore.plan = null
    mockAuthStore.source = 'free'
    mockAuthStore.cloudWordsLimit = 0
    mockAuthStore.licenseStatus = null

    vi.clearAllMocks()
    vi.mocked(tauri.readCredential).mockResolvedValue(null)
    vi.mocked(tauri.setCredential).mockResolvedValue(undefined)
    vi.mocked(tauri.getLlmModelCapability).mockResolvedValue('unknown')
    vi.mocked(tauri.getLatestMappingCandidate).mockResolvedValue(null)
    vi.mocked(tauri.listCustomAppMappings).mockResolvedValue([])
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  describe('Provider selection', () => {
    it('renders provider dropdown with current value', () => {
      render(<LlmPane />)
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0] // First select is provider
      expect(selectedValue(providerSelect)).toBe('openai')
    })

    it('updates config and resets state when provider changes', () => {
      render(<LlmPane />)
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0]

      chooseOption(providerSelect, 'claude')

      expect(mockAppStore.updateConfig).toHaveBeenCalled()
      expect(mockAppStore.setLlmTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setLlmLatencyMs).toHaveBeenCalledWith(null)
      expect(mockAppStore.setLlmModels).toHaveBeenCalledWith([])
    })

    it('applies Doubao defaults when provider changes to Doubao', () => {
      render(<LlmPane />)
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0]

      chooseOption(providerSelect, 'doubao')

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        llm_provider: 'doubao',
        llm_base_url: 'https://ark.cn-beijing.volces.com/api/v3',
        llm_model: 'doubao-seed-1-6-flash-250615',
      })
      expect(mockAppStore.setLlmTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setLlmLatencyMs).toHaveBeenCalledWith(null)
      expect(mockAppStore.setLlmModels).toHaveBeenCalledWith([])
    })
  })

  describe('Cloud provider UI', () => {
    it('keeps Ask Anything out of AI Polish settings', () => {
      render(<LlmPane />)

      expect(screen.queryByText('Ask Anything')).not.toBeInTheDocument()
      expect(screen.queryByText('Voice question')).not.toBeInTheDocument()
    })

    it('shows cloud info when provider is cloud and user not signed in', () => {
      mockAppStore.config.llm_provider = 'cloud'
      render(<LlmPane />)
      expect(screen.getByText('Cloud LLM (Pro)')).toBeInTheDocument()
      expect(
        screen.getByText('Sign in and subscribe to Pro to use cloud AI polish. No API key needed.'),
      ).toBeInTheDocument()
    })

    it('shows upgrade hint when user is signed in but not pro', () => {
      mockAppStore.config.llm_provider = 'cloud'
      mockAuthStore.user = { id: '1', email: 'test@example.com' }
      mockAuthStore.plan = 'free'

      render(<LlmPane />)
      expect(screen.getByText('Cloud LLM (Pro)')).toBeInTheDocument()
      expect(
        screen.getByText(
          'Upgrade to Pro for cloud AI polish and monthly usage. No API key needed.',
        ),
      ).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Upgrade' }))
      expect(window.location.hash).toBe('#/upgrade')
    })

    it('shows active status when user is pro', () => {
      mockAppStore.config.llm_provider = 'cloud'
      mockAuthStore.user = { id: '1', email: 'test@example.com' }
      mockAuthStore.plan = 'pro'

      render(<LlmPane />)
      expect(
        screen.getByText('Pro active — cloud AI polish is ready. No API key needed.'),
      ).toBeInTheDocument()
    })
  })

  describe('API Key input', () => {
    it('shows a load error rather than a save error when credential reading fails', async () => {
      vi.mocked(tauri.readCredential).mockRejectedValueOnce(new Error('synthetic-private-detail'))
      render(<LlmPane />)
      expect(await screen.findByText('common.loadFailed')).toBeInTheDocument()
      expect(screen.queryByText(/settings.credentialSaveFailed/)).not.toBeInTheDocument()
      const input = screen.getByPlaceholderText('Enter API Key')
      fireEvent.change(input, { target: { value: 'synthetic-replacement' } })
      expect(input).toHaveValue('synthetic-replacement')
      expect(screen.queryByText('common.loadFailed')).not.toBeInTheDocument()
    })

    it('displays the rotated vault key instead of a stale onboarding key', async () => {
      mockAppStore.config.llm_api_key = 'old-onboarding-key'
      vi.mocked(tauri.readCredential).mockResolvedValueOnce('new-vault-key')
      render(<LlmPane />)
      await waitFor(() =>
        expect(screen.getByPlaceholderText('Enter API Key')).toHaveValue('new-vault-key'),
      )
    })
    it('renders API key input with current value', () => {
      mockAppStore.config.llm_api_key = 'sk-test123'
      render(<LlmPane />)
      const input = screen.getByPlaceholderText('Enter API Key') as HTMLInputElement
      expect(input.value).toBe('sk-test123')
      expect(input.type).toBe('password')
    })

    it('stores API key in credential vault and resets test state when API key changes', async () => {
      render(<LlmPane />)
      const input = screen.getByPlaceholderText('Enter API Key')

      fireEvent.change(input, { target: { value: 'sk-new-key' } })
      fireEvent.blur(input)

      await waitFor(() =>
        expect(tauri.setCredential).toHaveBeenCalledWith('llm', 'openai', 'sk-new-key'),
      )
      expect(mockAppStore.updateConfig).not.toHaveBeenCalledWith({ llm_api_key: 'sk-new-key' })
      expect(mockAppStore.setLlmTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setLlmLatencyMs).toHaveBeenCalledWith(null)
    })

    it('shows an inline error when credential vault save fails', async () => {
      vi.mocked(tauri.setCredential).mockRejectedValueOnce(new Error('vault unavailable'))
      render(<LlmPane />)
      const input = screen.getByPlaceholderText('Enter API Key')

      fireEvent.change(input, { target: { value: 'sk-new-key' } })
      fireEvent.blur(input)

      expect(await screen.findByText(/settings.credentialSaveFailed/)).toBeInTheDocument()
    })
  })

  describe('Test button and latency display', () => {
    it('starts only one model-list request for same-turn repeated refresh clicks', async () => {
      let finish: ((models: string[]) => void) | undefined
      vi.mocked(tauri.fetchLlmModels).mockReturnValueOnce(
        new Promise<string[]>((resolve) => {
          finish = resolve
        }),
      )
      mockAppStore.config.llm_api_key = 'synthetic-key'
      render(<LlmPane />)
      const button = screen.getByRole('button', { name: 'Fetch models' })
      act(() => {
        fireEvent.click(button)
        fireEvent.click(button)
      })
      expect(tauri.fetchLlmModels).toHaveBeenCalledOnce()
      await act(async () => finish?.(['synthetic-model']))
      expect(button).not.toBeDisabled()
    })

    it('keeps a new endpoint request locked when the old request finishes', async () => {
      let finishOld: ((models: string[]) => void) | undefined
      let finishNew: ((models: string[]) => void) | undefined
      vi.mocked(tauri.fetchLlmModels)
        .mockReturnValueOnce(
          new Promise<string[]>((resolve) => {
            finishOld = resolve
          }),
        )
        .mockReturnValueOnce(
          new Promise<string[]>((resolve) => {
            finishNew = resolve
          }),
        )
      mockAppStore.config.llm_api_key = 'synthetic-key'
      const { rerender } = render(<LlmPane />)
      const button = screen.getByRole('button', { name: 'Fetch models' })
      fireEvent.click(button)
      mockAppStore.config = { ...mockAppStore.config, llm_base_url: 'https://new.example.test/v1' }
      rerender(<LlmPane />)
      fireEvent.click(button)
      expect(tauri.fetchLlmModels).toHaveBeenCalledTimes(2)
      await act(async () => finishOld?.(['old-model']))
      expect(button).toBeDisabled()
      fireEvent.click(button)
      expect(tauri.fetchLlmModels).toHaveBeenCalledTimes(2)
      await act(async () => finishNew?.(['new-model']))
      expect(button).not.toBeDisabled()
      expect(mockAppStore.setLlmModels).toHaveBeenCalledWith(['new-model'])
      expect(mockAppStore.setLlmModels).not.toHaveBeenCalledWith(['old-model'])
    })

    it('permits a model-list retry after a failed request without clearing cached models', async () => {
      vi.mocked(tauri.fetchLlmModels)
        .mockRejectedValueOnce(new Error('Synthetic model-list failure'))
        .mockResolvedValueOnce(['replacement-model'])
      mockAppStore.config.llm_api_key = 'synthetic-key'
      mockAppStore.llmModels = ['cached-model']
      render(<LlmPane />)
      const button = screen.getByRole('button', { name: 'Fetch models' })
      await act(async () => fireEvent.click(button))
      expect(button).not.toBeDisabled()
      expect(mockAppStore.setLlmModels).not.toHaveBeenCalled()
      await act(async () => fireEvent.click(button))
      expect(tauri.fetchLlmModels).toHaveBeenCalledTimes(2)
      expect(mockAppStore.setLlmModels).toHaveBeenCalledWith(['replacement-model'])
    })

    it.each(['llm_model', 'llm_base_url'] as const)(
      'ignores a pending connection result after external %s changes',
      async (field) => {
        let finish: (latency: number) => void = () => {}
        vi.mocked(tauri.benchLlmConnection).mockReturnValueOnce(
          new Promise<number>((resolve) => {
            finish = resolve
          }),
        )
        mockAppStore.config.llm_api_key = 'sk-test123'
        const { rerender } = render(<LlmPane />)
        fireEvent.click(screen.getByRole('button', { name: 'Test' }))
        mockAppStore.config = { ...mockAppStore.config, [field]: 'changed-config' }
        rerender(<LlmPane />)
        await act(async () => finish(100))
        expect(mockAppStore.setLlmTestStatus).not.toHaveBeenCalledWith('success')
        expect(mockAppStore.setLlmLatencyMs).not.toHaveBeenCalledWith(100)
      },
    )

    it('ignores a model list after the endpoint changes outside the page controls', async () => {
      let finish: (models: string[]) => void = () => {}
      vi.mocked(tauri.fetchLlmModels).mockReturnValueOnce(
        new Promise<string[]>((resolve) => {
          finish = resolve
        }),
      )
      mockAppStore.config.llm_api_key = 'sk-test123'
      const { rerender } = render(<LlmPane />)
      fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }))
      mockAppStore.config = { ...mockAppStore.config, llm_base_url: 'https://new.example/v1' }
      rerender(<LlmPane />)
      finish(['old-endpoint-model'])
      await Promise.resolve()
      await Promise.resolve()
      expect(mockAppStore.setLlmModels).not.toHaveBeenCalledWith(['old-endpoint-model'])
    })

    it('does not apply an old model list after switching providers', async () => {
      let finish: (models: string[]) => void = () => {}
      vi.mocked(tauri.fetchLlmModels).mockReturnValueOnce(
        new Promise<string[]>((resolve) => {
          finish = resolve
        }),
      )
      mockAppStore.config.llm_api_key = 'sk-test123'
      render(<LlmPane />)
      fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }))
      chooseOption(screen.getByRole('combobox', { name: 'Provider' }), 'ollama')
      finish(['old-provider-model'])
      await Promise.resolve()
      await Promise.resolve()
      expect(mockAppStore.setLlmModels).not.toHaveBeenCalledWith(['old-provider-model'])
    })

    it('does not apply a model list after leaving the configuration page', async () => {
      let finish: (models: string[]) => void = () => {}
      vi.mocked(tauri.fetchLlmModels).mockReturnValueOnce(
        new Promise<string[]>((resolve) => {
          finish = resolve
        }),
      )
      mockAppStore.config.llm_api_key = 'sk-test123'
      const { unmount } = render(<LlmPane />)
      fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }))
      unmount()
      finish(['old-provider-model'])
      await Promise.resolve()
      await Promise.resolve()
      expect(mockAppStore.setLlmModels).not.toHaveBeenCalledWith(['old-provider-model'])
    })

    it('ignores a connection result after leaving the configuration page', async () => {
      let finish: (latency: number) => void = () => {}
      vi.mocked(tauri.benchLlmConnection).mockReturnValueOnce(
        new Promise<number>((resolve) => {
          finish = resolve
        }),
      )
      mockAppStore.config.llm_api_key = 'sk-test123'
      const { unmount } = render(<LlmPane />)
      fireEvent.click(screen.getByRole('button', { name: 'Test' }))
      unmount()
      finish(100)
      await Promise.resolve()
      await Promise.resolve()
      expect(mockAppStore.setLlmTestStatus).not.toHaveBeenCalledWith('success')
    })

    it('resets connection status when the model name changes', () => {
      mockAppStore.config.llm_api_key = 'sk-test123'
      mockAppStore.llmTestStatus = 'success'
      render(<LlmPane />)
      fireEvent.change(screen.getByRole('combobox', { name: 'Model' }), {
        target: { value: 'different-model' },
      })
      expect(mockAppStore.setLlmTestStatus).toHaveBeenCalledWith('idle')
    })

    it('ignores a test result after the model was edited', async () => {
      let finish: (latency: number) => void = () => {}
      vi.mocked(tauri.benchLlmConnection).mockReturnValueOnce(
        new Promise<number>((resolve) => {
          finish = resolve
        }),
      )
      mockAppStore.config.llm_api_key = 'sk-test123'
      render(<LlmPane />)
      fireEvent.click(screen.getByRole('button', { name: 'Test' }))
      fireEvent.change(screen.getByRole('combobox', { name: 'Model' }), {
        target: { value: 'different-model' },
      })
      finish(100)
      await waitFor(() => expect(mockAppStore.setLlmTestStatus).toHaveBeenCalledWith('idle'))
      expect(mockAppStore.setLlmTestStatus).not.toHaveBeenCalledWith('success')
    })

    it('test button is disabled when API key is empty', () => {
      render(<LlmPane />)
      const button = screen.getByRole('button', { name: /test/i })
      expect(button).toBeDisabled()
    })

    it('does not enable connection testing with a whitespace-only API key', () => {
      mockAppStore.config.llm_api_key = ' \t\n'
      render(<LlmPane />)
      const button = screen.getByRole('button', { name: /test/i })
      expect(button).toBeDisabled()
      fireEvent.click(button)
      expect(tauri.benchLlmConnection).not.toHaveBeenCalled()
    })

    it('test button is enabled when API key is present', () => {
      mockAppStore.config.llm_api_key = 'sk-test123'
      render(<LlmPane />)
      const button = screen.getByRole('button', { name: /test/i })
      expect(button).not.toBeDisabled()
    })

    it('allows testing Ollama without an API key', async () => {
      vi.mocked(tauri.benchLlmConnection).mockResolvedValue(42)
      mockAppStore.config.llm_provider = 'ollama'
      mockAppStore.config.llm_api_key = ''
      mockAppStore.config.llm_base_url = 'http://localhost:11434/v1'
      mockAppStore.config.llm_model = 'llama3.2'

      render(<LlmPane />)
      // Keyless providers test from the Base URL row inside the advanced section.
      fireEvent.click(screen.getByRole('button', { name: /advanced polish settings/i }))
      const button = screen.getByRole('button', { name: /test/i })

      expect(screen.queryByPlaceholderText('Enter API Key')).not.toBeInTheDocument()
      expect(button).not.toBeDisabled()
      fireEvent.click(button)
      await waitFor(() =>
        expect(tauri.benchLlmConnection).toHaveBeenCalledWith(
          '',
          'ollama',
          'http://localhost:11434/v1',
          'llama3.2',
        ),
      )
    })

    it('auto-fetches Ollama models without an API key', async () => {
      vi.useFakeTimers()
      try {
        mockAppStore.config.llm_provider = 'ollama'
        mockAppStore.config.llm_api_key = ''
        mockAppStore.config.llm_base_url = 'http://localhost:11434/v1'

        render(<LlmPane />)
        await vi.advanceTimersByTimeAsync(500)

        expect(tauri.fetchLlmModels).toHaveBeenCalledWith('', 'ollama', 'http://localhost:11434/v1')
      } finally {
        vi.useRealTimers()
      }
    })

    it('does not fetch keyed-provider models before an API key is entered', () => {
      render(<LlmPane />)

      expect(screen.getByTitle('Fetch models')).toBeDisabled()
    })

    it('does not fetch models or test Ollama with a whitespace-only base URL', async () => {
      vi.useFakeTimers()
      try {
        mockAppStore.config.llm_provider = 'ollama'
        mockAppStore.config.llm_base_url = '   '
        render(<LlmPane />)
        fireEvent.click(screen.getByRole('button', { name: /advanced polish settings/i }))
        expect(screen.getByTitle('Fetch models')).toBeDisabled()
        expect(screen.getByRole('button', { name: /test/i })).toBeDisabled()
        await vi.advanceTimersByTimeAsync(500)
        expect(tauri.fetchLlmModels).not.toHaveBeenCalled()
        expect(tauri.benchLlmConnection).not.toHaveBeenCalled()
      } finally {
        vi.useRealTimers()
      }
    })

    it('shows loading state during test', () => {
      mockAppStore.config.llm_api_key = 'sk-test123'
      mockAppStore.llmTestStatus = 'testing'
      render(<LlmPane />)
      const button = screen.getByRole('button', { name: /test/i })
      expect(button).toBeDisabled()
    })

    it('calls benchLlmConnection on test button click', async () => {
      const mockBenchLlm = vi.mocked(tauri.benchLlmConnection)
      mockBenchLlm.mockResolvedValue(187)

      mockAppStore.config.llm_api_key = 'sk-test123'
      render(<LlmPane />)
      const button = screen.getByRole('button', { name: /test/i })

      fireEvent.click(button)

      await waitFor(() => {
        expect(mockAppStore.setLlmTestStatus).toHaveBeenCalledWith('testing')
        expect(mockAppStore.setLlmLatencyMs).toHaveBeenCalledWith(null)
      })

      await waitFor(() => {
        expect(mockBenchLlm).toHaveBeenCalledWith(
          'sk-test123',
          'openai',
          'https://api.openai.com/v1',
          'gpt-4o-mini',
        )
      })
    })

    it('displays latency in milliseconds when test succeeds', () => {
      mockAppStore.config.llm_api_key = 'sk-test123'
      mockAppStore.llmTestStatus = 'success'
      mockAppStore.llmLatencyMs = 187

      render(<LlmPane />)
      expect(screen.getByText('187ms')).toBeInTheDocument()
    })

    it('displays generic success message when latency is null', () => {
      mockAppStore.config.llm_api_key = 'sk-test123'
      mockAppStore.llmTestStatus = 'success'
      mockAppStore.llmLatencyMs = null

      render(<LlmPane />)
      expect(screen.getByText('Connection successful')).toBeInTheDocument()
    })

    it('shows error state UI', () => {
      mockAppStore.config.llm_api_key = 'sk-test123'
      mockAppStore.llmTestStatus = 'error'

      render(<LlmPane />)
      expect(screen.getByText('Connection failed')).toBeInTheDocument()
    })

    it('shows backend error details when test fails', async () => {
      const mockBenchLlm = vi.mocked(tauri.benchLlmConnection)
      mockBenchLlm.mockRejectedValue('HTTP 429 Too Many Requests')

      mockAppStore.config.llm_api_key = 'sk-test123'
      render(<LlmPane />)

      fireEvent.click(screen.getByRole('button', { name: /test/i }))

      expect(await screen.findByText('HTTP 429 Too Many Requests')).toBeInTheDocument()
      expect(mockAppStore.setLlmTestStatus).toHaveBeenCalledWith('error')
    })
  })

  it('starts only one connection test while a request is pending', async () => {
    let finish: (latency: number) => void = () => {}
    vi.mocked(tauri.benchLlmConnection).mockReturnValueOnce(
      new Promise<number>((resolve) => {
        finish = resolve
      }),
    )
    mockAppStore.config.llm_api_key = 'test-key'
    render(<LlmPane />)
    const button = screen.getByRole('button', { name: 'Test' })
    fireEvent.click(button)
    fireEvent.click(button)
    expect(tauri.benchLlmConnection).toHaveBeenCalledTimes(1)
    await act(async () => finish(100))
    fireEvent.click(button)
    expect(tauri.benchLlmConnection).toHaveBeenCalledTimes(2)
  })

  describe('AI polish behavior settings', () => {
    it('opens migrated translation controls from the capsule deep link', () => {
      const previousHash = window.location.hash
      window.location.hash = '#/models?section=translation'
      try {
        render(<LlmPane grouped />)
        expect(screen.getByTestId('translation-target-en')).toBeInTheDocument()
        expect(screen.getByRole('button', { name: 'Advanced polish settings' })).toHaveAttribute(
          'aria-expanded',
          'true',
        )
      } finally {
        window.location.hash = previousHash
      }
    })

    it('keeps app examples visible but dimmed in the grouped page without coverage counts', () => {
      mockAppStore.config.polish_enabled = false
      render(<LlmPane grouped />)
      const examples = screen.getByLabelText('Apps adapted by context')
      expect(examples).toHaveAttribute('aria-disabled', 'true')
      expect(within(examples).getByLabelText('Gmail')).toBeInTheDocument()
      expect(within(examples).queryByText('+63')).toBeNull()
      expect(within(examples).queryByText('+65')).toBeNull()
    })

    it('keeps the connection address in the model group instead of text processing', () => {
      mockAppStore.config.llm_provider = 'ollama'
      mockAppStore.config.llm_base_url = 'http://localhost:11434/v1'
      render(<LlmPane grouped />)
      const models = screen.getByRole('region', { name: 'modelsPage.aiModel' })
      const processing = screen.getByRole('region', { name: 'modelsPage.textProcessing' })
      expect(within(models).getByRole('textbox', { name: 'Base URL' })).toHaveValue(
        'http://localhost:11434/v1',
      )
      expect(within(processing).queryByRole('textbox', { name: 'Base URL' })).toBeNull()
    })

    it('does not replace an existing translation target when all five slots are occupied', () => {
      mockAppStore.config.translation = {
        targets: ['en', 'ja', 'fr', 'de', 'es'],
        active_target: 'en',
      }
      render(<LlmPane grouped />)
      fireEvent.click(screen.getByRole('combobox', { name: 'settings.translationTarget' }))
      const option = screen.getByRole('option', { name: 'Português' })
      expect(option).toHaveAttribute('aria-disabled', 'true')
      fireEvent.click(option)
      expect(mockAppStore.updateConfig).not.toHaveBeenCalled()
    })

    it('selects the active translation language without enabling always-translate', () => {
      mockAppStore.config.translation = { targets: ['en', 'ja'], active_target: 'en' }
      render(<LlmPane grouped />)
      chooseOption(screen.getByRole('combobox', { name: 'settings.translationTarget' }), 'ja')
      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        translation: { targets: ['en', 'ja'], active_target: 'ja' },
      })
      expect(screen.queryByRole('switch', { name: 'Always translate output' })).toBeNull()
    })

    it('shows custom style instructions once without clearing the existing draft', () => {
      mockAppStore.config.polish_style = 'custom'
      mockAppStore.config.polish_custom_prompt = 'Keep short sentences.'
      render(<LlmPane />)
      expect(screen.getAllByRole('textbox', { name: 'Custom polish instructions' })).toHaveLength(1)
      expect(screen.getByRole('textbox', { name: 'Custom polish instructions' })).toHaveValue(
        'Keep short sentences.',
      )
      fireEvent.click(screen.getByRole('button', { name: /advanced polish settings/i }))
      expect(screen.getAllByRole('textbox', { name: 'Custom polish instructions' })).toHaveLength(1)
    })

    it('shows Clean as the default polish style outside advanced settings', () => {
      render(<LlmPane />)

      expect(screen.getByText('Polish style')).toBeInTheDocument()
      expect(screen.getByRole('combobox', { name: 'Polish style' })).toHaveTextContent('Clean')
    })

    it('updates the selected polish style without opening advanced settings', () => {
      render(<LlmPane />)

      chooseOption(screen.getByRole('combobox', { name: 'Polish style' }), 'structured')

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({ polish_style: 'structured' })
      expect(screen.queryByText('Custom polish instructions')).not.toBeInTheDocument()
    })

    it('keeps custom instruction controls inside advanced settings', () => {
      render(<LlmPane />)

      expect(screen.getByText('Advanced polish settings')).toBeInTheDocument()
      expect(screen.queryByText('Optional writing rules')).not.toBeInTheDocument()
      expect(screen.queryByText('Chinese output')).not.toBeInTheDocument()
      expect(screen.queryByText('Custom polish instructions')).not.toBeInTheDocument()
      expect(screen.queryByText('Use selected text as context')).not.toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: /advanced polish settings/i }))

      expect(screen.getByText('Custom polish instructions')).toBeInTheDocument()
      expect(screen.getByText('Use selected text as context')).toBeInTheDocument()
      expect(screen.getByText('Use selected text for context')).toBeInTheDocument()
      expect(screen.queryByText('Chinese output')).not.toBeInTheDocument()
    })

    it('keeps selected-text controls reachable when cleanup is disabled', () => {
      mockAppStore.config.polish_enabled = false
      render(<LlmPane />)

      fireEvent.click(screen.getByRole('button', { name: /advanced polish settings/i }))

      expect(screen.getByText('Use selected text as context')).toBeInTheDocument()
      expect(screen.queryByText('Custom polish instructions')).not.toBeInTheDocument()
    })

    it('updates custom polish instructions from advanced settings', () => {
      render(<LlmPane />)

      fireEvent.click(screen.getByRole('button', { name: /advanced polish settings/i }))
      const textarea = screen.getByPlaceholderText('Example prompt')
      fireEvent.change(textarea, { target: { value: 'Keep a concise professional tone.' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        polish_custom_prompt: 'Keep a concise professional tone.',
      })
    })

    it('opens advanced settings automatically when custom instructions exist', () => {
      mockAppStore.config.polish_custom_prompt = 'Keep it concise.'

      render(<LlmPane />)

      expect(screen.getByText('Custom polish instructions')).toBeInTheDocument()
      expect(screen.queryByText('Chinese output')).not.toBeInTheDocument()
    })

    it('does not expose legacy global active scenes in AI Polish', () => {
      mockAppStore.config.active_scene = {
        id: 'custom_meeting',
        source: 'custom',
        name: 'Meeting Notes',
        prompt_template: 'Use bullets.',
      }

      render(<LlmPane />)

      expect(screen.queryByText('Active scene: Meeting Notes')).not.toBeInTheDocument()
      expect(screen.queryByText('Clear scene')).not.toBeInTheDocument()
    })
  })

  describe('Model input', () => {
    it('does not expose model capability implementation notes in the default flow', async () => {
      vi.mocked(tauri.getLlmModelCapability).mockResolvedValueOnce('certified')
      render(<LlmPane />)

      expect(
        screen.queryByText('Optimized context and thought-aware support'),
      ).not.toBeInTheDocument()
      expect(
        screen.queryByText('Context and thought-aware support is best effort'),
      ).not.toBeInTheDocument()
      expect(tauri.getLlmModelCapability).not.toHaveBeenCalled()
      expect(screen.queryByRole('table')).not.toBeInTheDocument()
    })

    it('keeps model editing available without a best-effort banner', () => {
      render(<LlmPane />)

      expect(
        screen.queryByText('Context and thought-aware support is best effort'),
      ).not.toBeInTheDocument()
      expect(screen.getByPlaceholderText('e.g. gpt-4o-mini')).not.toBeDisabled()
    })

    it('updates config and resets latency when model changes', () => {
      render(<LlmPane />)
      const input = screen.getByPlaceholderText('e.g. gpt-4o-mini')

      fireEvent.change(input, { target: { value: 'gpt-4o' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({ llm_model: 'gpt-4o' })
      expect(mockAppStore.setLlmLatencyMs).toHaveBeenCalledWith(null)
    })

    it('displays available models count', () => {
      mockAppStore.llmModels = ['gpt-4o', 'gpt-4o-mini', 'gpt-3.5-turbo']

      render(<LlmPane />)
      expect(screen.getByText('3 models available')).toBeInTheDocument()
    })
  })

  describe('Base URL input', () => {
    it('keeps the base URL inside advanced settings', () => {
      render(<LlmPane />)
      expect(screen.queryByPlaceholderText('https://api.openai.com/v1')).not.toBeInTheDocument()
    })

    it('updates config when base URL changes', () => {
      render(<LlmPane />)
      fireEvent.click(screen.getByRole('button', { name: /advanced polish settings/i }))
      const input = screen.getByPlaceholderText('https://api.openai.com/v1')

      fireEvent.change(input, { target: { value: 'https://custom.api.com/v1' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        llm_base_url: 'https://custom.api.com/v1',
      })
    })
  })

  describe('Feature toggles', () => {
    it('keeps context adaptation adjacent to AI polish and disables it when polish is off', () => {
      mockAppStore.config.polish_enabled = false
      render(<LlmPane />)

      const contextSwitch = screen.getByRole('switch', { name: 'Adapt writing to the current app' })
      expect(contextSwitch).toBeDisabled()
    })

    it('updates the context adaptation preference', () => {
      render(<LlmPane />)
      const contextSwitch = screen.getByRole('switch', { name: 'Adapt writing to the current app' })
      fireEvent.click(contextSwitch)
      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        context_adaptation_enabled: false,
      })
    })

    it('shows a compact noninteractive line of representative adapted apps', () => {
      render(<LlmPane />)

      const coverage = screen.getByLabelText('Apps adapted by context')
      for (const name of [
        'Gmail',
        'Slack',
        'Lark',
        'WeChat',
        'Google Docs',
        'Notion',
        'GitHub',
        'Cursor',
      ]) {
        expect(within(coverage).getByLabelText(name)).toBeInTheDocument()
      }
      expect(within(coverage).getByText('+63')).toBeInTheDocument()
      expect(within(coverage).getByText('+65')).toHaveClass('context-app-count--compact')
      expect(within(coverage).getByLabelText('Lark')).toHaveClass('context-app--compact-duplicate')
      expect(within(coverage).getByLabelText('Notion')).toHaveClass(
        'context-app--compact-duplicate',
      )
      expect(within(coverage).queryByRole('button')).not.toBeInTheDocument()
      expect(within(coverage).queryByRole('link')).not.toBeInTheDocument()
      expect(coverage).toHaveAttribute('aria-disabled', 'false')
      expect(coverage).toHaveClass('gap-1')
    })

    it('hides representative apps whenever app adaptation is not active', () => {
      mockAppStore.config.context_adaptation_enabled = false
      render(<LlmPane />)

      expect(screen.queryByLabelText('Apps adapted by context')).not.toBeInTheDocument()
      expect(
        screen.getByRole('switch', { name: 'Adapt writing to the current app' }),
      ).toBeInTheDocument()
    })

    it('hides representative apps when AI cleanup is disabled', () => {
      mockAppStore.config.polish_enabled = false
      render(<LlmPane />)
      expect(screen.queryByLabelText('Apps adapted by context')).not.toBeInTheDocument()
      expect(screen.getByRole('switch', { name: 'AI cleanup for dictation' })).toBeInTheDocument()
    })

    it('keeps helper copy and advanced toggles out of the default flow', () => {
      render(<LlmPane />)

      expect(screen.queryByText('Cleans up dictation before output')).not.toBeInTheDocument()
      expect(screen.queryByText('Translate each dictation result')).not.toBeInTheDocument()
      expect(screen.queryByText('Uses a private local app category')).not.toBeInTheDocument()
      expect(screen.queryByText('Use selected text as context')).not.toBeInTheDocument()
      expect(screen.queryByText('Use selected text for context')).not.toBeInTheDocument()
    })

    it('hides last context until an operation snapshot exists', () => {
      render(<LlmPane />)
      expect(screen.queryByText('Last dictation context')).not.toBeInTheDocument()
    })

    it('shows only the safe last operation context after dictation', () => {
      mockAppStore.lastContext = {
        profileId: 'chat.slack',
        family: 'work_chat',
        appLabel: 'Slack',
        iconKey: 'slack',
        overrideId: 'slack',
        browserAccessStatus: 'available',
      }
      render(<LlmPane />)

      expect(screen.getByText('Last dictation context')).toBeInTheDocument()
      expect(screen.getByText('Slack')).toBeInTheDocument()
      expect(screen.queryByText(/window|host|confidence/i)).not.toBeInTheDocument()
    })

    it('shows a short browser access hint when browser context needs URL access', () => {
      mockAppStore.lastContext = {
        profileId: 'general.browser',
        family: 'general',
        appLabel: 'Browser',
        iconKey: 'general',
        overrideId: null,
        browserAccessStatus: 'needs_permission',
      }

      render(<LlmPane />)

      expect(
        screen.getByText('Allow browser access to use Gmail, Docs, and Slack Web modes.'),
      ).toBeInTheDocument()
    })

    it('keeps the app-style overflow hidden without a live candidate or user mappings', async () => {
      mockAppStore.lastContext = {
        profileId: 'chat.slack',
        family: 'work_chat',
        appLabel: 'Slack',
        iconKey: 'slack',
        overrideId: 'slack',
        browserAccessStatus: 'available',
      }

      render(<LlmPane />)

      await waitFor(() => expect(tauri.listCustomAppMappings).toHaveBeenCalled())
      expect(screen.queryByRole('button', { name: 'App writing style' })).not.toBeInTheDocument()
    })

    it('opens one compact writing-style dialog from a live safe candidate', async () => {
      mockAppStore.lastContext = {
        profileId: 'general.browser',
        family: 'general',
        appLabel: 'Example',
        iconKey: 'general',
        overrideId: null,
        browserAccessStatus: 'available',
      }
      vi.mocked(tauri.getLatestMappingCandidate).mockResolvedValue({
        generation: 7,
        matcherType: 'exact_web_host',
        displayValue: 'docs.example.com',
        suggestedLabel: 'docs.example.com',
        currentFamily: 'document',
        iconKey: 'general',
      })

      render(<LlmPane />)

      fireEvent.click(await screen.findByRole('button', { name: 'App writing style' }))
      fireEvent.click(screen.getByText('Use a different writing style'))

      expect(
        await screen.findByRole('dialog', { name: 'Writing style for this app' }),
      ).toBeVisible()
      expect(screen.getByText('docs.example.com')).toBeInTheDocument()
    })

    it('shows mapping management only for user-created mappings', async () => {
      mockAppStore.lastContext = {
        profileId: 'chat.slack',
        family: 'work_chat',
        appLabel: 'Slack',
        iconKey: 'slack',
        overrideId: 'slack',
        browserAccessStatus: 'available',
      }
      vi.mocked(tauri.listCustomAppMappings).mockResolvedValue([
        {
          id: 'mapping-1',
          label: 'Work Slack',
          matcherType: 'native_bundle_id',
          displayValue: 'Work Slack · macOS',
          family: 'work_chat',
          sceneId: null,
          enabled: true,
          iconKey: 'slack',
        },
      ])

      render(<LlmPane />)

      fireEvent.click(await screen.findByRole('button', { name: 'App writing style' }))
      expect(screen.getByText('Manage app mappings')).toBeInTheDocument()
      expect(screen.queryByText('Use a different writing style')).not.toBeInTheDocument()
      expect(screen.queryByText('Gmail')).not.toBeInTheDocument()
    })

    it('leaves translation targets to Settings > Language', () => {
      mockAppStore.config.translate_enabled = true

      render(<LlmPane />)
      expect(screen.getByRole('switch', { name: 'Always translate output' })).toBeInTheDocument()
      expect(screen.queryByText('Translate to')).not.toBeInTheDocument()
    })
  })
})
