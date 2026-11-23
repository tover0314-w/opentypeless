const FORBIDDEN_SETTINGS_KEYS = new Set([
  'customappmappings',
  'matcher',
  'bundleid',
  'nativebundleid',
  'executable',
  'nativeexecutable',
  'exactwebhost',
  'windowtitle',
  'browserhost',
  'processid',
  'fullurl',
  'apikey',
  'sttapikey',
  'sttcustomapikey',
  'llmapikey',
  'sessiontoken',
  'accesstoken',
  'refreshtoken',
  'token',
  'password',
  'secret',
  'clientsecret',
  'authorization',
])

const ENDPOINT_KEYS = new Set(['llmbaseurl', 'sttcustombaseurl'])
const CREDENTIAL_QUERY_KEYS = new Set([
  'apikey',
  'accesstoken',
  'authorization',
  'token',
  'password',
  'secret',
  'clientsecret',
  'signature',
  'sig',
  'key',
])

function normalizedKey(key: string): string {
  return key.toLowerCase().replace(/[-_]/g, '')
}

export function safeBackupEndpoint(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  if (!value.trim()) return value
  try {
    const endpoint = new URL(value)
    if (
      !['http:', 'https:'].includes(endpoint.protocol) ||
      endpoint.username ||
      endpoint.password ||
      endpoint.hash
    ) {
      return undefined
    }
    for (const key of endpoint.searchParams.keys()) {
      if (CREDENTIAL_QUERY_KEYS.has(normalizedKey(key))) return undefined
    }
    return value
  } catch {
    return undefined
  }
}

export function hasSafeBackupSettings(value: unknown, depth = 0): boolean {
  if (depth > 64) return false
  if (Array.isArray(value)) {
    return value.every((item) => hasSafeBackupSettings(item, depth + 1))
  }
  if (value === null || typeof value !== 'object') return true
  return Object.entries(value).every(([key, item]) => {
    const normalized = normalizedKey(key)
    return (
      !FORBIDDEN_SETTINGS_KEYS.has(normalized) &&
      (!ENDPOINT_KEYS.has(normalized) || safeBackupEndpoint(item) !== undefined) &&
      hasSafeBackupSettings(item, depth + 1)
    )
  })
}
