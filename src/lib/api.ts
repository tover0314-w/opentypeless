import {
  API_BASE_URL,
  APP_VERSION_HEADER_VALUE,
  CLIENT_VERSION_HEADER,
  DEFAULT_CHECKOUT_PRODUCT,
  type CheckoutProduct,
} from './constants'
import { captureCloudSessionOwner, invalidateCloudSessionOnce } from './cloud-session'
import { loadSessionWithSignal } from './load-session-with-signal'
import { cloudFetch } from './cloudTransport'
import { validBackupDictionary } from './backup-dictionary-validation'
import { validBackupHistory } from './backup-history-validation'
import { hasValidSubscriptionBillingFields, isSubscriptionStatus } from './subscription-validation'

const DEFAULT_TIMEOUT_MS = 30_000

async function authHeaders(signal: AbortSignal) {
  const isCurrentOwner = captureCloudSessionOwner()
  const token = await loadSessionWithSignal(signal)
  const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {}
  return { headers, isCurrentOwner }
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
    const authenticationHeaders = await authHeaders(controller.signal)
    controller.signal.throwIfAborted()
    const res = await cloudFetch(`${API_BASE_URL}${path}`, {
      ...fetchOptions,
      credentials: 'include',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...clientHeaders(),
        ...authenticationHeaders.headers,
        ...fetchOptions?.headers,
      },
    })

    if (!res.ok) {
      const body = await res.json().catch((error: unknown) => {
        if (
          controller.signal.aborted ||
          ((error instanceof Error || error instanceof DOMException) && error.name === 'AbortError')
        ) {
          throw error
        }
        return { error: res.statusText }
      })
      controller.signal.throwIfAborted()
      throw parseCloudError(res.status, body, res.statusText, authenticationHeaders.isCurrentOwner)
    }

    const body = await res.json()
    controller.signal.throwIfAborted()
    return body
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
  isCurrentOwner: () => boolean = () => true,
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

  if (status === 401 && code === 'AUTH_SESSION_INVALID' && isCurrentOwner()) {
    void invalidateCloudSessionOnce().catch(() => {
      console.error('Failed to invalidate cloud session')
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
  preferredClientSwitchBytes?: number | null
  bitrateBitsPerSecond?: number | null
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
  quotaDataAvailable?: boolean
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
    if (
      !hasValidSubscriptionBillingFields(status) ||
      (status.quotaDataAvailable !== undefined && typeof status.quotaDataAvailable !== 'boolean')
    ) {
      throw new Error('Invalid subscription response')
    }
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

    const subscription = {
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
      quotaDataAvailable:
        status.quotaDataAvailable !== false &&
        (quotaModel === 'legacy_dual_meter' && displayWordsLimit > 0
          ? typeof status.displayWordsUsedEstimate === 'number' &&
            typeof status.displayWordsLimit === 'number'
          : (typeof status.cloudWordsUsed === 'number' &&
              typeof status.cloudWordsLimit === 'number') ||
            (quotaModel === 'legacy_dual_meter' &&
              status.displayWordsUsedEstimate === 0 &&
              status.displayWordsLimit === 0)),
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
    if (!isSubscriptionStatus(subscription)) {
      throw new Error('Invalid subscription response')
    }
    return subscription
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
    const authenticationHeaders = await authHeaders(controller.signal)
    controller.signal.throwIfAborted()
    const res = await cloudFetch(`${API_BASE_URL}/api/proxy/stt`, {
      method: 'POST',
      credentials: 'include',
      signal: controller.signal,
      headers: {
        ...clientHeaders(),
        ...authenticationHeaders.headers,
      },
      body: formData,
    })

    if (!res.ok) {
      const body = await res.json().catch((error: unknown) => {
        if (
          controller.signal.aborted ||
          ((error instanceof Error || error instanceof DOMException) && error.name === 'AbortError')
        ) {
          throw error
        }
        return { error: res.statusText }
      })
      controller.signal.throwIfAborted()
      throw parseCloudError(res.status, body, res.statusText, authenticationHeaders.isCurrentOwner)
    }

    const body = await res.json()
    controller.signal.throwIfAborted()
    return body
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
export async function uploadBackup(data: {
  history?: unknown
  dictionary?: unknown
  settings?: unknown
}): Promise<{ success: boolean }> {
  const body = await request<unknown>('/api/backup/upload', {
    method: 'POST',
    body: JSON.stringify(data),
  })
  if (!isRecord(body) || body.success !== true || 'error' in body) {
    throw new Error('Backup upload was not confirmed')
  }
  return { success: true }
}

export async function downloadBackup(): Promise<{
  history?: unknown
  dictionary?: unknown
  settings?: unknown
}> {
  const body = await request<unknown>('/api/backup/download')
  if (!isRecord(body) || 'error' in body) throw new Error('Invalid backup response')
  if (!validBackupHistory(body.history)) {
    throw new Error('Invalid backup response')
  }
  if (body.settings != null && !isRecord(body.settings)) {
    throw new Error('Invalid backup response')
  }
  if (!validBackupDictionary(body.dictionary)) {
    throw new Error('Invalid backup response')
  }
  return body
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
