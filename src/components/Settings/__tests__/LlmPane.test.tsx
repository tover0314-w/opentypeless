import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { LlmPane } from '../LlmPane'
import * as tauri from '../../../lib/tauri'

// Mock Tauri
vi.mock('../../../lib/tauri')

// Mock i18n
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: Record<string, unknown>) => {
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
        'settings.enableAiPolish': 'Enable AI Polish',
        'settings.translationMode': 'Translation Mode',
        'settings.selectedTextContext': 'Selected Text Context',
        'settings.selectedTextContextDesc': 'Include selected text as context',
        'settings.targetLanguage': 'Target Language',
        'settings.cloudLlmPro': 'Cloud LLM (Pro)',
        'settings.llmSignInHint': 'Sign in to use cloud LLM',
        'settings.llmUpgradeHint': 'Upgrade to Pro to use cloud LLM',
        'settings.llmProActive': 'Cloud LLM active',
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
    translate_enabled: false,
    selected_text_enabled: false,
    target_lang: 'en',
  },
  updateConfig: vi.fn(),
  llmTestStatus: 'idle' as 'idle' | 'testing' | 'success' | 'error',
  setLlmTestStatus: vi.fn(),
  llmLatencyMs: null as number | null,
  setLlmLatencyMs: vi.fn(),
  llmModels: [] as string[],
  setLlmModels: vi.fn(),
}

const mockAuthStore = {
  user: null as unknown,
  plan: null as unknown,
}

vi.mock('../../../stores/appStore', () => ({
  useAppStore: (selector: (store: typeof mockAppStore) => unknown) => {
    if (typeof selector === 'function') {
      return selector(mockAppStore)
    }
    return mockAppStore
  },
}))

vi.mock('../../../stores/authStore', () => ({
  useAuthStore: (selector: (store: typeof mockAuthStore) => unknown) => {
    if (typeof selector === 'function') {
      return selector(mockAuthStore)
    }
    return mockAuthStore
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
      translate_enabled: false,
      selected_text_enabled: false,
      target_lang: 'en',
    }
    mockAppStore.llmTestStatus = 'idle'
    mockAppStore.llmLatencyMs = null
    mockAppStore.llmModels = []
    mockAuthStore.user = null
    mockAuthStore.plan = null
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
  })

  describe('Cloud provider UI', () => {
    it('shows cloud info when provider is cloud and user not signed in', () => {
      mockAppStore.config.llm_provider = 'cloud'
      render(<LlmPane />)
      expect(screen.getByText('Sign in to use cloud LLM')).toBeInTheDocument()
    })

    it('shows upgrade hint when user is signed in but not pro', () => {
      mockAppStore.config.llm_provider = 'cloud'
      mockAuthStore.user = { id: '1', email: 'test@example.com' }
      mockAuthStore.plan = 'free'

      render(<LlmPane />)
      expect(screen.getByText('Upgrade to Pro to use cloud LLM')).toBeInTheDocument()
    })

    it('shows active status when user is pro', () => {
      mockAppStore.config.llm_provider = 'cloud'
      mockAuthStore.user = { id: '1', email: 'test@example.com' }
      mockAuthStore.plan = 'pro'

      render(<LlmPane />)
      expect(screen.getByText('Cloud LLM active')).toBeInTheDocument()
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

    it('updates config and resets test state when API key changes', () => {
      render(<LlmPane />)
      const input = screen.getByPlaceholderText('Enter API Key')

      fireEvent.change(input, { target: { value: 'sk-new-key' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({ llm_api_key: 'sk-new-key' })
      expect(mockAppStore.setLlmTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setLlmLatencyMs).toHaveBeenCalledWith(null)
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
  })

  describe('Model input', () => {
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
      const input = screen.getByPlaceholderText('https://open.bigmodel.cn/api/paas/v4')

      fireEvent.change(input, { target: { value: 'https://custom.api.com/v1' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        llm_base_url: 'https://custom.api.com/v1',
      })
    })
  })

  describe('Feature toggles', () => {
    it('shows target language selector when translation is enabled', () => {
      mockAppStore.config.translate_enabled = true

      render(<LlmPane />)
      expect(screen.getByText('Target Language')).toBeInTheDocument()
    })
  })
})
