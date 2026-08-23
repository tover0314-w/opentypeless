import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LlmSetupStep } from '../LlmSetupStep'
import * as tauri from '../../../lib/tauri'

const mockStore = {
  config: {
    llm_provider: 'ollama',
    llm_api_key: '',
    llm_base_url: 'http://localhost:11434/v1',
    llm_model: 'llama3.2',
  },
  updateConfig: vi.fn(),
  llmTestStatus: 'idle',
  setLlmTestStatus: vi.fn(),
}

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) =>
      ({
        'onboarding.llm.serviceLabel': 'Service',
        'onboarding.llm.apiKeyLabel': 'API key',
        'onboarding.llm.apiKeyPlaceholder': 'API key',
        'onboarding.llm.testButton': 'Test',
        'onboarding.llm.modelLabel': 'Model',
        'onboarding.llm.modelPlaceholder': 'Model',
        'onboarding.llm.fetchModelsTitle': 'Fetch models',
        'onboarding.llm.baseUrlLabel': 'Base URL',
        'providers.llm.ollama': 'Ollama',
      })[key] ?? key,
  }),
}))

vi.mock('../../../stores/appStore', () => ({
  useAppStore: (selector: any) => selector(mockStore),
}))

vi.mock('../../../lib/tauri')

beforeEach(() => {
  mockStore.config = {
    llm_provider: 'ollama',
    llm_api_key: '',
    llm_base_url: 'http://localhost:11434/v1',
    llm_model: 'llama3.2',
  }
  mockStore.updateConfig = vi.fn()
  mockStore.llmTestStatus = 'idle'
  mockStore.setLlmTestStatus = vi.fn()
  vi.clearAllMocks()
  vi.mocked(tauri.testLlmConnection).mockResolvedValue(true)
  vi.mocked(tauri.fetchLlmModels).mockResolvedValue(['llama3.2'])
})

afterEach(cleanup)

describe('LlmSetupStep', () => {
  it('does not offer managed Cloud inside BYOK provider setup', () => {
    render(<LlmSetupStep />)

    const providerSelect = screen.getAllByRole('combobox')[0]
    expect(providerSelect.querySelector('option[value="cloud"]')).toBeNull()
  })

  it('moves a saved Cloud config to a valid BYOK provider instead of showing a dead selection', async () => {
    mockStore.config = {
      llm_provider: 'cloud',
      llm_api_key: '',
      llm_base_url: 'https://legacy.invalid/api/proxy',
      llm_model: 'default',
    }

    render(<LlmSetupStep />)

    expect(screen.getAllByRole('combobox')[0]).toHaveValue('zhipu')
    await waitFor(() => {
      expect(mockStore.updateConfig).toHaveBeenCalledWith({
        llm_provider: 'zhipu',
        llm_base_url: 'https://open.bigmodel.cn/api/paas/v4',
        llm_model: 'glm-4-flash',
      })
    })
  })

  it('allows testing Ollama without an API key', async () => {
    render(<LlmSetupStep />)

    const button = screen.getByRole('button', { name: 'Test' })
    expect(screen.queryByPlaceholderText('API key')).not.toBeInTheDocument()
    expect(button).not.toBeDisabled()
    fireEvent.click(button)

    await waitFor(() =>
      expect(tauri.testLlmConnection).toHaveBeenCalledWith(
        '',
        'ollama',
        'http://localhost:11434/v1',
        'llama3.2',
      ),
    )
  })

  it('waits for a key before fetching models from a keyed provider', () => {
    mockStore.config = {
      llm_provider: 'zhipu',
      llm_api_key: '',
      llm_base_url: 'https://open.bigmodel.cn/api/paas/v4',
      llm_model: 'glm-4-flash',
    }

    render(<LlmSetupStep />)

    expect(screen.getByTitle('Fetch models')).toBeDisabled()
  })
})
