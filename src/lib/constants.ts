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
export const CLIENT_VERSION_HEADER = 'X-OpenTypeless-Version'
export const APP_VERSION_HEADER_VALUE = APP_VERSION.replace(/^v/i, '')
export const APP_REPO_URL = 'https://github.com/tover0314-w/opentypeless'
export const APP_LICENSE_URL = 'https://github.com/tover0314-w/opentypeless/blob/main/LICENSE'
// Cloud API base URL — defaults to www.opentypeless.com but can be overridden via VITE_API_BASE_URL env var.
// All core features (BYOK mode) work without any cloud connection.
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'https://www.opentypeless.com'

export const FREE_PLAN = {
  sttMinutes: 15,
  llmTokens: 100_000,
} as const

export type CheckoutProduct = 'pro_monthly' | 'lifetime_starter'

const CLOUD_PLAN_BENEFITS = [
  { labelKey: 'upgrade.benefits.cloudWords' },
  { labelKey: 'upgrade.benefits.noApiKey' },
  { labelKey: 'upgrade.benefits.backupScenes' },
] as const

type CloudPlanBenefit = (typeof CLOUD_PLAN_BENEFITS)[number]

export type CheckoutPlan = {
  product: CheckoutProduct
  nameKey: string
  descriptionKey: string
  badgeKey?: string
  sublineKey?: string
  price: string
  upgradePrice?: string
  upgradeSublineKey?: string
  periodKey: string
  ctaKey: string
  benefits: readonly CloudPlanBenefit[]
}

export const PRO_PLAN = {
  product: 'pro_monthly',
  nameKey: 'upgrade.pro',
  descriptionKey: 'upgrade.planDescriptions.pro',
  price: '$4.99',
  periodKey: 'upgrade.month',
  ctaKey: 'upgrade.subscribeToPro',
  benefits: CLOUD_PLAN_BENEFITS,
} satisfies CheckoutPlan

export const LIFETIME_PLAN = {
  product: 'lifetime_starter',
  nameKey: 'upgrade.lifetime',
  descriptionKey: 'upgrade.planDescriptions.lifetime',
  badgeKey: 'upgrade.lifetimeBadge',
  sublineKey: 'upgrade.lifetimeSave',
  price: '$89.99',
  upgradePrice: '$84.99',
  upgradeSublineKey: 'upgrade.lifetimeUpgradeSave',
  periodKey: 'upgrade.oneTime',
  ctaKey: 'upgrade.buyLifetime',
  benefits: CLOUD_PLAN_BENEFITS,
} satisfies CheckoutPlan

export const CHECKOUT_PLANS: CheckoutPlan[] = [PRO_PLAN, LIFETIME_PLAN]

export const DEFAULT_CHECKOUT_PRODUCT: CheckoutProduct = 'pro_monthly'

export const ACTIVE_CLOUD_PLANS = ['pro', 'lifetime_starter'] as const

export function isActiveCloudPlan(plan: string): plan is (typeof ACTIVE_CLOUD_PLANS)[number] {
  return ACTIVE_CLOUD_PLANS.includes(plan as (typeof ACTIVE_CLOUD_PLANS)[number])
}

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
  { value: 'cloud', labelKey: 'providers.stt.cloud' },
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
    provider.value !== CUSTOM_WHISPER_PROVIDER &&
    provider.value !== APPLE_SPEECH_PROVIDER &&
    provider.value !== 'cloud',
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
  { value: 'cloud', labelKey: 'providers.llm.cloud' },
] as const

export const ONBOARDING_LLM_PROVIDERS = LLM_PROVIDERS.filter(
  (provider) => provider.value !== 'cloud',
)

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
  claude: { baseUrl: 'https://api.anthropic.com/v1', model: 'claude-sonnet-4-0' },
  ollama: { baseUrl: 'http://localhost:11434/v1', model: 'llama3.2' },
  openrouter: { baseUrl: 'https://openrouter.ai/api/v1', model: 'openai/gpt-4o-mini' },
  cloud: { baseUrl: `${API_BASE_URL}/api/proxy`, model: 'default' },
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
