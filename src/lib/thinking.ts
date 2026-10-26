import registry from './thinking-capabilities.json'

export const THINKING_STRENGTHS = [
  'default',
  'off',
  'minimal',
  'low',
  'medium',
  'high',
  'xhigh',
  'max',
  'budget_1024',
  'budget_2048',
] as const
export type ThinkingStrength = (typeof THINKING_STRENGTHS)[number]

export function isThinkingStrength(value: unknown): value is ThinkingStrength {
  return typeof value === 'string' && THINKING_STRENGTHS.some((choice) => choice === value)
}

// This registry is also compiled into Rust. Never infer support from model prefixes.
export function thinkingCapability(provider: string, baseUrl: string, model: string) {
  let url: URL
  try {
    url = new URL(baseUrl.trim())
  } catch {
    return undefined
  }
  if (
    url.protocol !== 'https:' ||
    (url.port !== '' && url.port !== '443') ||
    url.username ||
    url.password ||
    url.href.includes('?') ||
    url.href.includes('#')
  )
    return undefined
  const normalizedProvider = provider.trim().toLowerCase()
  const normalizedModel = ['claude', 'anthropic'].includes(normalizedProvider)
    ? model.trim().replace(/^anthropic\//, '')
    : model.trim()
  const path = url.pathname.replace(/\/+$/, '')
  return registry.entries.find(
    (entry) =>
      entry.providers.includes(normalizedProvider) &&
      entry.host === url.hostname &&
      entry.paths.includes(path) &&
      entry.models.includes(normalizedModel),
  )
}

export function thinkingState(
  provider: string,
  baseUrl: string,
  model: string,
  selected: ThinkingStrength = 'default',
) {
  const capability = thinkingCapability(provider, baseUrl, model)
  const options = (capability?.options ?? ['default']) as ThinkingStrength[]
  return { options, supported: options.includes(selected), available: Boolean(capability) }
}
