import {
  API_BASE_URL,
  APP_VERSION_HEADER_VALUE,
  CLIENT_VERSION_HEADER,
  DEFAULT_CHECKOUT_PRODUCT,
  type CheckoutProduct,
} from './constants'
import { invalidateCloudSessionOnce } from './cloud-session'

const DEFAULT_TIMEOUT_MS = 30_000

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('session_token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function clientHeaders(): Record<string, string> {
  return { [CLIENT_VERSION_HEADER]: APP_VERSION_HEADER_VALUE }
}

async function request<T>(
  path: string,
  options?: RequestInit & { timeoutMs?: number },
): Promise<T> {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, ...fetchOptions } = options ?? {}
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)

  try {
    const res = await fetch(`${API_BASE_URL}${path}`, {
      ...fetchOptions,
      credentials: 'include',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...clientHeaders(),
        ...authHeaders(),
        ...fetchOptions?.headers,
      },
    })

    if (!res.ok) {
      const body = await res.json().catch(() => ({ error: res.statusText }))
      throw parseCloudError(res.status, body, res.statusText)
    }

    return res.json()
  } finally {
    clearTimeout(timer)
  }
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export class CloudApiError extends ApiError {
  constructor(
    status: number,
    public readonly code: string | null,
    message: string,
  ) {
    super(status, message)
    this.name = 'CloudApiError'
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function parseCloudError(
  status: number,
  body: unknown,
  fallbackMessage = `Request failed (${status})`,
): CloudApiError {
  let code: string | null = null
  let message = fallbackMessage

  if (isRecord(body)) {
    const error = body.error
    if (typeof error === 'string' && error.trim()) {
      message = error
    } else if (isRecord(error)) {
      if (typeof error.code === 'string' && error.code.trim()) code = error.code
      if (typeof error.message === 'string' && error.message.trim()) message = error.message
    }
  }

  if (code === 'AUTH_SESSION_INVALID') {
    void invalidateCloudSessionOnce().catch((error) => {
      console.error('Failed to invalidate cloud session:', error)
    })
  }
  return new CloudApiError(status, code, message)
}

// Subscription
export type SubscriptionPlan =
  | 'free'
  | 'pro'
  | 'lifetime_starter'
  | 'appsumo_tier1'
  | 'appsumo_tier2'
  | 'appsumo_tier3'

export type SubscriptionSource = 'free' | 'creem' | 'stripe' | 'lifetime' | 'appsumo'
export type BillingProvider = 'stripe' | 'creem' | 'appsumo' | null
export type LicenseStatus = 'pending' | 'active' | 'refunded' | 'deactivated'
export type QuotaModel = 'legacy_dual_meter' | 'cloud_words'

export interface ManagedSttFormat {
  mimeType: string
  maxAudioBytes: number
  preferredClientSwitchBytes?: number
  bitrateBitsPerSecond?: number
}

export interface ManagedSttCapability {
  version: number
  maxRecordingSeconds: number
  maxMultipartBytes: number
  formats: ManagedSttFormat[]
}

export interface AuthenticatedAccountSnapshot {
  schemaVersion: 1
  userId: string
  managedSttCapabilities: ManagedSttCapability | null
  generatedAt: string
}

export interface SubscriptionStatus {
  plan: SubscriptionPlan
  source: SubscriptionSource
  displayName: string
  subscriptionEnd: string | null
  subscriptionStatus: string | null
  licenseStatus?: LicenseStatus | null
  billingProvider?: BillingProvider
  cancelAtPeriodEnd?: boolean
  canManageBilling?: boolean
  canMigrateToStripe?: boolean
  entitlementVersion?: number
  quotaModel: QuotaModel
  displayWordsUsedEstimate: number
  displayWordsLimit: number
  displayWordsResetAt: string | null
  sttSecondsUsed: number
  sttSecondsLimit: number
  llmTokensUsed: number
  llmTokensLimit: number
  cloudWordsUsed: number
  cloudWordsLimit: number
  cloudWordsResetAt: string | null
  byokUnlimited: boolean
  accountSnapshot?: AuthenticatedAccountSnapshot | null
}

export function getSubscriptionStatus(): Promise<SubscriptionStatus> {
  return request<Partial<SubscriptionStatus>>('/api/subscription/status').then((status) => {
    const plan = (status.plan ?? 'free') as SubscriptionPlan
    const source =
      status.source ??
      (plan === 'pro'
        ? 'creem'
        : plan === 'lifetime_starter'
          ? 'lifetime'
          : plan.startsWith('appsumo_')
            ? 'appsumo'
            : 'free')

    const quotaModel =
      status.quotaModel ?? (source === 'appsumo' ? 'cloud_words' : 'legacy_dual_meter')
    const billingProvider: BillingProvider =
      status.billingProvider === 'stripe' ||
      status.billingProvider === 'creem' ||
      status.billingProvider === 'appsumo'
        ? status.billingProvider
        : source === 'stripe' || source === 'creem'
          ? source
          : source === 'appsumo'
            ? 'appsumo'
            : null
    const displayWordsUsedEstimate = status.displayWordsUsedEstimate ?? 0
    const displayWordsLimit = status.displayWordsLimit ?? 0
    const displayWordsResetAt = status.displayWordsResetAt ?? null

    return {
      plan,
      source: source as SubscriptionSource,
      displayName:
        status.displayName ??
        (plan === 'pro'
          ? 'Pro'
          : plan === 'lifetime_starter'
            ? 'Lifetime Starter'
            : plan === 'free'
              ? 'Free'
              : 'AppSumo Lifetime'),
      subscriptionEnd: status.subscriptionEnd ?? null,
      subscriptionStatus: status.subscriptionStatus ?? null,
      licenseStatus: status.licenseStatus ?? null,
      billingProvider,
      cancelAtPeriodEnd: status.cancelAtPeriodEnd === true,
      canManageBilling:
        typeof status.canManageBilling === 'boolean'
          ? status.canManageBilling
          : plan === 'pro' && (billingProvider === 'stripe' || billingProvider === 'creem'),
      canMigrateToStripe: status.canMigrateToStripe === true && billingProvider === 'creem',
      entitlementVersion:
        typeof status.entitlementVersion === 'number' ? status.entitlementVersion : 1,
      quotaModel,
      displayWordsUsedEstimate,
      displayWordsLimit,
      displayWordsResetAt,
      sttSecondsUsed: status.sttSecondsUsed ?? 0,
      sttSecondsLimit: status.sttSecondsLimit ?? 0,
      llmTokensUsed: status.llmTokensUsed ?? 0,
      llmTokensLimit: status.llmTokensLimit ?? 0,
      cloudWordsUsed: status.cloudWordsUsed ?? 0,
      cloudWordsLimit: status.cloudWordsLimit ?? 0,
      cloudWordsResetAt: status.cloudWordsResetAt ?? null,
      byokUnlimited: status.byokUnlimited ?? true,
      accountSnapshot: status.accountSnapshot ?? null,
    }
  })
}

// Checkout
export interface CheckoutResponse {
  url: string
}

export function createCheckout(
  origin: 'desktop' | 'web' = 'desktop',
  product: CheckoutProduct = DEFAULT_CHECKOUT_PRODUCT,
): Promise<CheckoutResponse> {
  return request('/api/checkout/create', {
    method: 'POST',
    body: JSON.stringify({ origin, product }),
  })
}

export interface CloudOperationContext {
  operationId?: string
  stageKey?: string
  requestType?: string
  clientVersion?: string
  hasSelectedText?: boolean
  translateEnabled?: boolean
  rawTextChars?: number
  selectedTextChars?: number
}

// Proxy STT
export async function proxyStt(
  audioBlob: Blob,
  language: string,
  context?: CloudOperationContext,
): Promise<{ text: string }> {
  const formData = new FormData()
  formData.append('audio', audioBlob)
  formData.append('language', language)
  if (context?.operationId) formData.append('operationId', context.operationId)
  if (context?.stageKey) formData.append('stageKey', context.stageKey)
  if (context?.requestType) formData.append('requestType', context.requestType)
  if (context?.clientVersion) formData.append('clientVersion', context.clientVersion)

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 60_000)

  try {
    const res = await fetch(`${API_BASE_URL}/api/proxy/stt`, {
      method: 'POST',
      credentials: 'include',
      signal: controller.signal,
      headers: {
        ...clientHeaders(),
        ...authHeaders(),
      },
      body: formData,
    })

    if (!res.ok) {
      const body = await res.json().catch(() => ({ error: res.statusText }))
      throw parseCloudError(res.status, body, res.statusText)
    }

    return res.json()
  } finally {
    clearTimeout(timer)
  }
}

// Proxy LLM
export function proxyLlm(
  messages: Array<{ role: string; content: string }>,
  context?: CloudOperationContext,
): Promise<{ text: string }> {
  return request('/api/proxy/llm', {
    method: 'POST',
    body: JSON.stringify({ messages, ...(context ? { context } : {}) }),
  })
}

// Backup
export function uploadBackup(data: {
  history?: unknown
  dictionary?: unknown
  settings?: unknown
}): Promise<{ success: boolean }> {
  return request('/api/backup/upload', {
    method: 'POST',
    body: JSON.stringify(data),
  })
}

export function downloadBackup(): Promise<{
  history?: unknown
  dictionary?: unknown
  settings?: unknown
}> {
  return request('/api/backup/download')
}

// Scenes
export interface ScenePack {
  id: string
  name: string
  description: string
  category: string
  promptTemplate: string
  dictionaryTerms: Array<{ word: string; pronunciation?: string }>
  isPro: boolean
}

export function getScenes(): Promise<ScenePack[]> {
  return request('/api/scenes')
}

// Subscription portal
export function createPortalSession(): Promise<{ url: string }> {
  return request('/api/subscription/portal', { method: 'POST' })
}
