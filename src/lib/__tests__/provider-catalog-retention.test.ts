import { describe, expect, it } from 'vitest'
import {
  CUSTOM_STT_PRESETS,
  LLM_DEFAULT_CONFIG,
  LLM_PROVIDERS,
  STT_PROVIDERS,
  VOLCENGINE_STT_RESOURCES,
  llmProviderRequiresApiKey,
} from '../constants'

const ORIGINAL_STT = [
  'deepgram',
  'assemblyai',
  'aliyun-qwen3-asr',
  'volcengine-doubao',
  'glm-asr',
  'openai-whisper',
  'groq-whisper',
  'siliconflow',
  'apple-speech',
  'custom-whisper',
  'cloud',
]

const ORIGINAL_LLM = [
  'zhipu',
  'deepseek',
  'siliconflow',
  'openai',
  'gemini',
  'moonshot',
  'doubao',
  'qwen',
  'groq',
  'claude',
  'ollama',
  'openrouter',
  'cloud',
]

describe('upstream provider catalog retention', () => {
  it('keeps every STT option from upstream f5ad602', () => {
    const providers = STT_PROVIDERS.map((provider) => provider.value)
    expect(ORIGINAL_STT.filter((provider) => !providers.includes(provider))).toEqual([])
    expect(new Set(providers).size).toBe(providers.length)
  })

  it('keeps every LLM option and a usable default endpoint', () => {
    const providers = LLM_PROVIDERS.map((provider) => provider.value)
    expect(ORIGINAL_LLM.filter((provider) => !providers.includes(provider))).toEqual([])
    expect(new Set(providers).size).toBe(providers.length)
    for (const provider of ORIGINAL_LLM) {
      expect(LLM_DEFAULT_CONFIG[provider]?.model.trim()).not.toBe('')
      const endpoint = new URL(LLM_DEFAULT_CONFIG[provider].baseUrl)
      expect(['http:', 'https:']).toContain(endpoint.protocol)
      expect(endpoint.hostname).not.toBe('')
    }
  })

  it('keeps local keyless Ollama and custom STT configuration paths', () => {
    expect(llmProviderRequiresApiKey('ollama')).toBe(false)
    expect(llmProviderRequiresApiKey('openai')).toBe(true)
    expect(CUSTOM_STT_PRESETS.map((preset) => preset.value)).toEqual(
      expect.arrayContaining(['speaches', 'custom']),
    )
    expect(VOLCENGINE_STT_RESOURCES.map((resource) => resource.value)).toEqual(
      expect.arrayContaining(['volc.seedasr.sauc.duration', 'volc.bigasr.sauc.duration']),
    )
  })
})
