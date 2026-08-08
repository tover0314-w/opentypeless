import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup, within } from '@testing-library/react'
import { LlmPane } from '../LlmPane'
import * as tauri from '../../../lib/tauri'

// Mock Tauri
vi.mock('../../../lib/tauri')

// Mock i18n
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: any) => {
      const translations: Record<string, string> = {
        'settings.provider': 'Provider',
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

vi.mock('../../../stores/appStore', () => ({
  useAppStore: (selector: any) => {
    if (typeof selector === 'function') {
      return selector(mockAppStore)
    }
    return mockAppStore
  },
}))

describe('LlmPane', () => {
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
      expect(providerSelect).toHaveValue('openai')
    })

    it('updates config and resets state when provider changes', () => {
      render(<LlmPane />)
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0]

      fireEvent.change(providerSelect, { target: { value: 'anthropic' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalled()
      expect(mockAppStore.setLlmTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setLlmLatencyMs).toHaveBeenCalledWith(null)
      expect(mockAppStore.setLlmModels).toHaveBeenCalledWith([])
    })

    it('applies Doubao defaults when provider changes to Doubao', () => {
      render(<LlmPane />)
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0]

      fireEvent.change(providerSelect, { target: { value: 'doubao' } })

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

  describe('AI polish settings', () => {
    it('keeps Ask Anything out of AI Polish settings', () => {
      render(<LlmPane />)

      expect(screen.queryByText('Ask Anything')).not.toBeInTheDocument()
      expect(screen.queryByText('Voice question')).not.toBeInTheDocument()
    })
  })

  describe('API Key input', () => {
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
    it('test button is disabled when API key is empty', () => {
      render(<LlmPane />)
      const button = screen.getByRole('button', { name: /test/i })
      expect(button).toBeDisabled()
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

  describe('AI polish behavior settings', () => {
    it('shows Clean as the default polish style outside advanced settings', () => {
      render(<LlmPane />)

      expect(screen.getByText('Polish style')).toBeInTheDocument()
      expect(screen.getByDisplayValue('Clean')).toBeInTheDocument()
    })

    it('updates the selected polish style without opening advanced settings', () => {
      render(<LlmPane />)

      fireEvent.change(screen.getByDisplayValue('Clean'), { target: { value: 'structured' } })

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
    it('updates config when base URL changes', () => {
      render(<LlmPane />)
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

      const contextSwitch = screen
        .getByText('Adapt writing to the current app')
        .closest('label')
        ?.querySelector('[role="switch"]')
      expect(contextSwitch).toBeDisabled()
    })

    it('updates the context adaptation preference', () => {
      render(<LlmPane />)
      const contextSwitch = screen
        .getByText('Adapt writing to the current app')
        .closest('label')
        ?.querySelector('[role="switch"]')
      expect(contextSwitch).not.toBeNull()
      fireEvent.click(contextSwitch!)
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

    it('dims representative apps whenever app adaptation is not active', () => {
      mockAppStore.config.context_adaptation_enabled = false
      render(<LlmPane />)

      expect(screen.getByLabelText('Apps adapted by context')).toHaveAttribute(
        'aria-disabled',
        'true',
      )
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

    it('shows a compact translate-to control next to translation when enabled', () => {
      mockAppStore.config.translate_enabled = true

      render(<LlmPane />)
      expect(screen.getByText('Translate to')).toBeInTheDocument()
      expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    })
  })
})
