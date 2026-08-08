// App metadata
export const UI_LANGUAGES = [
  { value: 'en', label: 'English' },
  { value: 'zh', label: '中文' },
  { value: 'ja', label: '日本語' },
  { value: 'ko', label: '한국어' },
  { value: 'fr', label: 'Français' },
  { value: 'de', label: 'Deutsch' },
  { value: 'es', label: 'Español' },
  { value: 'pt', label: 'Português' },
  { value: 'ru', label: 'Русский' },
  { value: 'it', label: 'Italiano' },
] as const

export const APP_NAME = 'OpenTypeless'
export const APP_VERSION = import.meta.env.VITE_APP_VERSION ?? 'v0.1.42'
export const APP_REPO_URL = 'https://github.com/dengxuezhao/opentypeless'
export const APP_LICENSE_URL = 'https://github.com/dengxuezhao/opentypeless/blob/main/LICENSE'

export const CUSTOM_WHISPER_PROVIDER = 'custom-whisper' as const
export const APPLE_SPEECH_PROVIDER = 'apple-speech' as const

export const CUSTOM_STT_DEFAULTS = {
  preset: 'speaches',
  baseUrl: 'http://localhost:8000/v1',
  model: 'Systran/faster-whisper-large-v3',
} as const

export const CUSTOM_STT_PRESETS = [
  {
    value: 'speaches',
    labelKey: 'settings.customSttPresetSpeaches',
    baseUrl: CUSTOM_STT_DEFAULTS.baseUrl,
    model: CUSTOM_STT_DEFAULTS.model,
  },
  {
    value: 'custom',
    labelKey: 'settings.customSttPresetCustom',
  },
] as const

export const STT_PROVIDERS: { value: string; labelKey: string }[] = [
  { value: 'deepgram', labelKey: 'providers.stt.deepgram' },
  { value: 'assemblyai', labelKey: 'providers.stt.assemblyai' },
  { value: 'aliyun-qwen3-asr', labelKey: 'providers.stt.aliyunQwen3Asr' },
  { value: 'volcengine-doubao', labelKey: 'providers.stt.volcengineDoubao' },
  { value: 'glm-asr', labelKey: 'providers.stt.glmAsr' },
  { value: 'openai-whisper', labelKey: 'providers.stt.openaiWhisper' },
  { value: 'groq-whisper', labelKey: 'providers.stt.groqWhisper' },
  { value: 'siliconflow', labelKey: 'providers.stt.siliconflow' },
  { value: APPLE_SPEECH_PROVIDER, labelKey: 'providers.stt.appleSpeech' },
  { value: CUSTOM_WHISPER_PROVIDER, labelKey: 'providers.stt.customWhisper' },
] as const

export const VOLCENGINE_STT_RESOURCES = [
  {
    value: 'volc.seedasr.sauc.duration',
    labelKey: 'settings.volcengineResourceSeedAsr',
  },
  {
    value: 'volc.bigasr.sauc.duration',
    labelKey: 'settings.volcengineResourceBigAsr',
  },
] as const

export const ONBOARDING_STT_PROVIDERS = STT_PROVIDERS.filter(
  (provider) =>
    provider.value !== CUSTOM_WHISPER_PROVIDER && provider.value !== APPLE_SPEECH_PROVIDER,
)

export const LLM_PROVIDERS: { value: string; labelKey: string }[] = [
  { value: 'zhipu', labelKey: 'providers.llm.zhipu' },
  { value: 'deepseek', labelKey: 'providers.llm.deepseek' },
  { value: 'siliconflow', labelKey: 'providers.llm.siliconflow' },
  { value: 'openai', labelKey: 'providers.llm.openai' },
  { value: 'gemini', labelKey: 'providers.llm.gemini' },
  { value: 'moonshot', labelKey: 'providers.llm.moonshot' },
  { value: 'doubao', labelKey: 'providers.llm.doubao' },
  { value: 'qwen', labelKey: 'providers.llm.qwen' },
  { value: 'groq', labelKey: 'providers.llm.groq' },
  { value: 'claude', labelKey: 'providers.llm.claude' },
  { value: 'ollama', labelKey: 'providers.llm.ollama' },
  { value: 'openrouter', labelKey: 'providers.llm.openrouter' },
] as const

export const ONBOARDING_LLM_PROVIDERS = LLM_PROVIDERS

export const LLM_DEFAULT_CONFIG: Record<string, { baseUrl: string; model: string }> = {
  zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  deepseek: { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  siliconflow: { baseUrl: 'https://api.siliconflow.cn/v1', model: 'Qwen/Qwen2.5-7B-Instruct' },
  openai: { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  gemini: {
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    model: 'gemini-2.0-flash',
  },
  moonshot: { baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  doubao: {
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    model: 'doubao-seed-1-6-flash-250615',
  },
  qwen: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-turbo' },
  groq: { baseUrl: 'https://api.groq.com/openai/v1', model: 'llama-3.3-70b-versatile' },
  claude: { baseUrl: 'https://openrouter.ai/api/v1', model: 'anthropic/claude-sonnet-4' },
  ollama: { baseUrl: 'http://localhost:11434/v1', model: 'llama3.2' },
  openrouter: { baseUrl: 'https://openrouter.ai/api/v1', model: 'openai/gpt-4o-mini' },
}

export function llmProviderRequiresApiKey(provider: string): boolean {
  return provider.trim().toLowerCase() !== 'ollama'
}

export const LANGUAGES: { value: string; label?: string; labelKey?: string }[] = [
  { value: 'multi', labelKey: 'settings.autoDetect' },
  { value: 'zh', label: '中文' },
  { value: 'en', label: 'English' },
  { value: 'ja', label: '日本語' },
  { value: 'ko', label: '한국어' },
  { value: 'fr', label: 'Français' },
  { value: 'de', label: 'Deutsch' },
  { value: 'es', label: 'Español' },
  { value: 'pt', label: 'Português' },
  { value: 'ru', label: 'Русский' },
  { value: 'ar', label: 'العربية' },
  { value: 'hi', label: 'हिन्दी' },
  { value: 'th', label: 'ไทย' },
  { value: 'vi', label: 'Tiếng Việt' },
  { value: 'it', label: 'Italiano' },
  { value: 'nl', label: 'Nederlands' },
  { value: 'tr', label: 'Türkçe' },
  { value: 'pl', label: 'Polski' },
  { value: 'uk', label: 'Українська' },
  { value: 'id', label: 'Bahasa Indonesia' },
  { value: 'ms', label: 'Bahasa Melayu' },
]

export const TARGET_LANGUAGES: { value: string; label: string; labelKey?: string }[] = [
  { value: 'en', label: 'English' },
  { value: 'zh', label: '中文' },
  { value: 'ja', label: '日本語' },
  { value: 'ko', label: '한국어' },
  { value: 'fr', label: 'Français' },
  { value: 'de', label: 'Deutsch' },
  { value: 'es', label: 'Español' },
  { value: 'pt', label: 'Português' },
  { value: 'ru', label: 'Русский' },
  { value: 'ar', label: 'العربية' },
  { value: 'hi', label: 'हिन्दी' },
  { value: 'th', label: 'ไทย' },
  { value: 'vi', label: 'Tiếng Việt' },
  { value: 'it', label: 'Italiano' },
  { value: 'nl', label: 'Nederlands' },
  { value: 'tr', label: 'Türkçe' },
  { value: 'pl', label: 'Polski' },
  { value: 'uk', label: 'Українська' },
  { value: 'id', label: 'Bahasa Indonesia' },
  { value: 'ms', label: 'Bahasa Melayu' },
]
