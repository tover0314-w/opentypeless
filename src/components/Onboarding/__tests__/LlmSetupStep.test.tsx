import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LlmSetupStep } from '../LlmSetupStep'
import { selectedValue, selectOptions } from '../../shared/__tests__/selectTestUtils'
import * as tauri from '../../../lib/tauri'
import { ONBOARDING_LLM_PROVIDERS } from '../../../lib/constants'

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
  it.each(ONBOARDING_LLM_PROVIDERS)(
    'preserves the existing $value endpoint and model on mount',
    ({ value }) => {
      mockStore.config.llm_provider = value
      mockStore.config.llm_base_url = 'http://127.0.0.1:8124/v1'
      mockStore.config.llm_model = 'synthetic-configured-model'
      const previous = { ...mockStore.config }
      render(<LlmSetupStep />)
      expect(mockStore.updateConfig).not.toHaveBeenCalled()
      expect(mockStore.config).toEqual(previous)
      expect(tauri.testLlmConnection).not.toHaveBeenCalled()
    },
  )

  it('names its endpoint and credential inputs using existing translated labels', () => {
    mockStore.config.llm_provider = 'openai'
    render(<LlmSetupStep />)
    expect(screen.getByLabelText('API key')).toHaveAttribute('type', 'password')
    expect(screen.getByRole('textbox', { name: 'Base URL' })).toBeInTheDocument()
  })

  it('uses the shared field and secondary action styles', () => {
    const { container } = render(<LlmSetupStep />)
    for (const input of container.querySelectorAll('input')) {
      expect(input).toHaveClass('field-control')
    }
    expect(screen.getByRole('button', { name: 'Test' })).toHaveClass('btn-outline')
    expect(screen.getByTitle('Fetch models')).toHaveClass('btn-outline')
  })

  it('ignores a connection result after the model changes', async () => {
    let finish: (value: boolean) => void = () => {}
    vi.mocked(tauri.testLlmConnection).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const { rerender } = render(<LlmSetupStep />)
    fireEvent.click(screen.getByRole('button', { name: 'Test' }))
    mockStore.config = { ...mockStore.config, llm_model: 'new-model' }
    rerender(<LlmSetupStep />)
    await act(async () => finish(true))
    expect(mockStore.setLlmTestStatus).not.toHaveBeenCalledWith('success')
  })

  it('does not complete a connection test after leaving setup', async () => {
    let finish: (value: boolean) => void = () => {}
    vi.mocked(tauri.testLlmConnection).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const { unmount } = render(<LlmSetupStep />)
    fireEvent.click(screen.getByRole('button', { name: 'Test' }))
    unmount()
    await act(async () => finish(true))
    expect(mockStore.setLlmTestStatus).not.toHaveBeenCalledWith('success')
    expect(mockStore.setLlmTestStatus).toHaveBeenLastCalledWith('idle')
  })

  it('ignores a model list returned for a previous endpoint', async () => {
    let finish: (models: string[]) => void = () => {}
    vi.mocked(tauri.fetchLlmModels).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const { rerender } = render(<LlmSetupStep />)
    fireEvent.click(screen.getByTitle('Fetch models'))
    mockStore.config = { ...mockStore.config, llm_base_url: 'http://localhost:9000/v1' }
    rerender(<LlmSetupStep />)
    await act(async () => finish(['llama3.2', 'old-endpoint-model']))
    fireEvent.focus(screen.getByRole('combobox', { name: 'Model' }))
    expect(screen.queryByRole('option', { name: 'old-endpoint-model' })).toBeNull()
    expect(screen.getByTitle('Fetch models')).toBeEnabled()
  })

  it('uses the shared styled model input while preserving custom model entry', async () => {
    vi.mocked(tauri.fetchLlmModels).mockResolvedValueOnce(['llama3.2', 'local-model'])
    const { container } = render(<LlmSetupStep />)
    fireEvent.click(screen.getByTitle('Fetch models'))
    await waitFor(() => expect(tauri.fetchLlmModels).toHaveBeenCalled())
    const input = screen.getByRole('combobox', { name: 'Model' })
    await act(async () => fireEvent.focus(input))
    expect(screen.getByRole('listbox', { name: 'Model' })).toHaveAttribute('data-select-menu')
    fireEvent.click(screen.getByRole('option', { name: 'local-model' }))
    expect(mockStore.updateConfig).toHaveBeenCalledWith({ llm_model: 'local-model' })
    fireEvent.change(input, { target: { value: 'custom-model' } })
    expect(mockStore.updateConfig).toHaveBeenCalledWith({ llm_model: 'custom-model' })
    expect(container.querySelector('datalist')).toBeNull()
  })

  it('does not offer managed Cloud inside BYOK provider setup', () => {
    render(<LlmSetupStep />)

    const providerSelect = screen.getAllByRole('combobox')[0]
    expect(selectOptions(providerSelect).map((option) => option.value)).not.toContain('cloud')
  })

  it('moves a saved Cloud config to a valid BYOK provider instead of showing a dead selection', async () => {
    mockStore.config = {
      llm_provider: 'cloud',
      llm_api_key: '',
      llm_base_url: 'https://www.opentypeless.com/api/proxy',
      llm_model: 'default',
    }

    render(<LlmSetupStep />)

    expect(selectedValue(screen.getAllByRole('combobox')[0])).toBe('zhipu')
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
