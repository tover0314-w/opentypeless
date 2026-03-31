import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { SttPane } from '../SttPane'
import * as tauri from '../../../lib/tauri'

// Mock Tauri
vi.mock('../../../lib/tauri')

// Mock i18n
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'settings.provider': 'Provider',
        'settings.apiKey': 'API Key',
        'settings.test': 'Test',
        'settings.enterApiKey': 'Enter API Key',
        'settings.connectionSuccess': 'Connection successful',
        'settings.connectionFailed': 'Connection failed',
        'settings.storedLocally': 'Stored locally',
        'settings.sttLanguage': 'STT Language',
        'settings.cloudSttPro': 'Cloud STT (Pro)',
        'settings.sttSignInHint': 'Sign in to use cloud STT',
        'settings.sttUpgradeHint': 'Upgrade to Pro to use cloud STT',
        'settings.sttProActive': 'Cloud STT active',
      }
      return translations[key] || key
    },
  }),
}))

// Mock stores
const mockAppStore = {
  config: {
    stt_provider: 'deepgram' as string,
    stt_api_key: '',
    stt_language: 'en',
  },
  updateConfig: vi.fn(),
  sttTestStatus: 'idle' as 'idle' | 'testing' | 'success' | 'error',
  setSttTestStatus: vi.fn(),
  sttLatencyMs: null as number | null,
  setSttLatencyMs: vi.fn(),
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

describe('SttPane', () => {
  beforeEach(() => {
    // Reset mock store state
    mockAppStore.config = {
      stt_provider: 'deepgram',
      stt_api_key: '',
      stt_language: 'en',
    }
    mockAppStore.sttTestStatus = 'idle'
    mockAppStore.sttLatencyMs = null
    mockAuthStore.user = null
    mockAuthStore.plan = null

    // Clear all mock function calls
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  describe('Provider selection', () => {
    it('renders provider dropdown with current value', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0] // First select is provider
      expect(providerSelect).toHaveValue('deepgram')
    })

    it('updates config and resets state when provider changes', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0]

      fireEvent.change(providerSelect, { target: { value: 'assemblyai' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({ stt_provider: 'assemblyai' })
      expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
    })
  })

  describe('Cloud provider UI', () => {
    it('shows cloud info when provider is cloud and user not signed in', () => {
      mockAppStore.config.stt_provider = 'cloud'
      render(<SttPane />)
      expect(screen.getByText('Sign in to use cloud STT')).toBeInTheDocument()
    })

    it('shows upgrade hint when user is signed in but not pro', () => {
      mockAppStore.config.stt_provider = 'cloud'
      mockAuthStore.user = { id: '1', email: 'test@example.com' }
      mockAuthStore.plan = 'free'

      render(<SttPane />)
      expect(screen.getByText('Upgrade to Pro to use cloud STT')).toBeInTheDocument()
    })

    it('shows active status when user is pro', () => {
      mockAppStore.config.stt_provider = 'cloud'
      mockAuthStore.user = { id: '1', email: 'test@example.com' }
      mockAuthStore.plan = 'pro'

      render(<SttPane />)
      expect(screen.getByText('Cloud STT active')).toBeInTheDocument()
    })

    it('hides API key input when provider is cloud', () => {
      mockAppStore.config.stt_provider = 'cloud'

      const { container } = render(<SttPane />)
      const inputs = container.querySelectorAll('input[placeholder="Enter API Key"]')
      expect(inputs.length).toBe(0)
    })
  })

  describe('API Key input', () => {
    it('renders API key input with current value', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      const { container } = render(<SttPane />)
      const input = container.querySelector(
        'input[placeholder="Enter API Key"]',
      ) as HTMLInputElement
      expect(input.value).toBe('sk-test123')
      expect(input.type).toBe('password')
    })

    it('updates config and resets test state when API key changes', () => {
      const { container } = render(<SttPane />)
      const input = container.querySelector(
        'input[placeholder="Enter API Key"]',
      ) as HTMLInputElement

      fireEvent.change(input, { target: { value: 'sk-new-key' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({ stt_api_key: 'sk-new-key' })
      expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
    })
  })

  describe('Test button and latency display', () => {
    it('test button is disabled when API key is empty', () => {
      render(<SttPane />)
      const buttons = screen.getAllByRole('button', { name: /test/i })
      const button = buttons[0]
      expect(button).toBeDisabled()
    })

    it('test button is enabled when API key is present', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      render(<SttPane />)
      const buttons = screen.getAllByRole('button', { name: /test/i })
      const button = buttons[0]
      expect(button).not.toBeDisabled()
    })

    it('test button is disabled during testing', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'testing'
      render(<SttPane />)
      const buttons = screen.getAllByRole('button', { name: /test/i })
      const button = buttons[0]
      expect(button).toBeDisabled()
    })

    it('calls benchSttConnection on test button click', async () => {
      const mockBenchStt = vi.mocked(tauri.benchSttConnection)
      mockBenchStt.mockResolvedValue(234)

      mockAppStore.config.stt_api_key = 'sk-test123'
      render(<SttPane />)
      const buttons = screen.getAllByRole('button', { name: /test/i })
      const button = buttons[0]

      fireEvent.click(button)

      await waitFor(() => {
        expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('testing')
        expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
      })

      await waitFor(() => {
        expect(mockBenchStt).toHaveBeenCalledWith('sk-test123', 'deepgram')
      })
    })

    it('displays latency in milliseconds when test succeeds', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'success'
      mockAppStore.sttLatencyMs = 234

      render(<SttPane />)
      expect(screen.getByText('234ms')).toBeInTheDocument()
    })

    it('displays generic success message when latency is null', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'success'
      mockAppStore.sttLatencyMs = null

      render(<SttPane />)
      expect(screen.getByText('Connection successful')).toBeInTheDocument()
    })

    it('shows error state UI', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'error'

      render(<SttPane />)
      expect(screen.getByText('Connection failed')).toBeInTheDocument()
    })

    it('does not display latency when status is error', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'error'
      mockAppStore.sttLatencyMs = 234

      render(<SttPane />)
      expect(screen.queryByText('234ms')).not.toBeInTheDocument()
      expect(screen.getByText('Connection failed')).toBeInTheDocument()
    })
  })

  describe('Language selection', () => {
    it('renders language dropdown with current value', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const languageSelect = selects[1] // Second select is language
      expect(languageSelect).toHaveValue('en')
    })

    it('updates config when language changes', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const languageSelect = selects[1]

      fireEvent.change(languageSelect, { target: { value: 'zh' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({ stt_language: 'zh' })
    })
  })

  describe('Integration: state reset on config changes', () => {
    it('resets latency when API key changes after successful test', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'success'
      mockAppStore.sttLatencyMs = 234

      const { container } = render(<SttPane />)

      // Verify latency is displayed
      expect(screen.getByText('234ms')).toBeInTheDocument()

      // Change API key
      const input = container.querySelector(
        'input[placeholder="Enter API Key"]',
      ) as HTMLInputElement
      fireEvent.change(input, { target: { value: 'sk-new-key' } })

      // Verify state was reset
      expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
      expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('idle')
    })

    it('resets latency when provider changes after successful test', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'success'
      mockAppStore.sttLatencyMs = 234

      render(<SttPane />)

      // Change provider
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0]
      fireEvent.change(providerSelect, { target: { value: 'assemblyai' } })

      // Verify state was reset
      expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
      expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('idle')
    })
  })
})
