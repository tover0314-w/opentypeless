export function providerErrorMessage(
  error: unknown,
  secret: string,
  fallback: string | null,
): string | null {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : ''
  const redacted = secret ? message.split(secret).join('[redacted]') : message
  return redacted.trim() ? redacted : fallback
}
