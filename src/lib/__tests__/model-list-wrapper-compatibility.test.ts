import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { fetchLlmModels } from '../tauri'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

describe('model list wrapper compatibility', () => {
  beforeEach(() => {
    vi.mocked(invoke).mockReset().mockResolvedValue(['synthetic-model'])
  })

  it('preserves the old key and endpoint positional arguments', async () => {
    expect(await fetchLlmModels('synthetic-key', 'https://example.test/v1')).toEqual([
      'synthetic-model',
    ])
    expect(invoke).toHaveBeenCalledWith('fetch_llm_models', {
      apiKey: 'synthetic-key',
      provider: 'openai',
      baseUrl: 'https://example.test/v1',
    })
  })

  it('keeps explicit provider selection for current callers', async () => {
    await fetchLlmModels('synthetic-key', 'claude', 'https://api.anthropic.com/v1')
    expect(invoke).toHaveBeenCalledWith('fetch_llm_models', {
      apiKey: 'synthetic-key',
      provider: 'claude',
      baseUrl: 'https://api.anthropic.com/v1',
    })
  })

  it('preserves an explicitly empty endpoint rather than treating it as a legacy call', async () => {
    await fetchLlmModels('', 'ollama', '')
    expect(invoke).toHaveBeenCalledWith('fetch_llm_models', {
      apiKey: '',
      provider: 'ollama',
      baseUrl: '',
    })
  })
})
