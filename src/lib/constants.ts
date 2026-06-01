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
export const APP_VERSION = import.meta.env.VITE_APP_VERSION ?? 'v0.1.0'
export const APP_REPO_URL = 'https://github.com/tover0314-w/opentypeless'
export const APP_LICENSE_URL = 'https://github.com/tover0314-w/opentypeless/blob/main/LICENSE'
// Cloud API base URL — defaults to www.opentypeless.com but can be overridden via VITE_API_BASE_URL env var.
// All core features (BYOK mode) work without any cloud connection.
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'https://www.opentypeless.com'

export const FREE_PLAN = {
  sttMinutes: 15,
  llmTokens: 100_000,
} as const

export const PRO_PLAN = {
  price: '$4.99',
  period: 'month',
  features: [
    { labelKey: 'upgrade.features.sttTitle', detailKey: 'upgrade.features.sttDetail' },
    { labelKey: 'upgrade.features.llmTitle', detailKey: 'upgrade.features.llmDetail' },
    { labelKey: 'upgrade.features.backupTitle', detailKey: 'upgrade.features.backupDetail' },
    { labelKey: 'upgrade.features.scenesTitle', detailKey: 'upgrade.features.scenesDetail' },
    {
      labelKey: 'upgrade.features.zeroConfigTitle',
      detailKey: 'upgrade.features.zeroConfigDetail',
    },
  ],
}

export const STT_PROVIDERS: { value: string; labelKey: string }[] = [
  { value: 'deepgram', labelKey: 'providers.stt.deepgram' },
  { value: 'assemblyai', labelKey: 'providers.stt.assemblyai' },
  { value: 'glm-asr', labelKey: 'providers.stt.glmAsr' },
  { value: 'openai-whisper', labelKey: 'providers.stt.openaiWhisper' },
  { value: 'groq-whisper', labelKey: 'providers.stt.groqWhisper' },
  { value: 'siliconflow', labelKey: 'providers.stt.siliconflow' },
  { value: 'cloud', labelKey: 'providers.stt.cloud' },
] as const

export const LLM_PROVIDERS: { value: string; labelKey: string }[] = [
  { value: 'zhipu', labelKey: 'providers.llm.zhipu' },
  { value: 'deepseek', labelKey: 'providers.llm.deepseek' },
  { value: 'siliconflow', labelKey: 'providers.llm.siliconflow' },
  { value: 'openai', labelKey: 'providers.llm.openai' },
  { value: 'gemini', labelKey: 'providers.llm.gemini' },
  { value: 'moonshot', labelKey: 'providers.llm.moonshot' },
  { value: 'qwen', labelKey: 'providers.llm.qwen' },
  { value: 'groq', labelKey: 'providers.llm.groq' },
  { value: 'claude', labelKey: 'providers.llm.claude' },
  { value: 'ollama', labelKey: 'providers.llm.ollama' },
  { value: 'openrouter', labelKey: 'providers.llm.openrouter' },
  { value: 'cloud', labelKey: 'providers.llm.cloud' },
] as const

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
  qwen: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-turbo' },
  groq: { baseUrl: 'https://api.groq.com/openai/v1', model: 'llama-3.3-70b-versatile' },
  claude: { baseUrl: 'https://openrouter.ai/api/v1', model: 'anthropic/claude-sonnet-4' },
  ollama: { baseUrl: 'http://localhost:11434/v1', model: 'llama3.2' },
  openrouter: { baseUrl: 'https://openrouter.ai/api/v1', model: 'openai/gpt-4o-mini' },
  cloud: { baseUrl: `${API_BASE_URL}/api/proxy`, model: 'default' },
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
