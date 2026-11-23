export const DEVELOPMENT_CLOUD_PREFIX = '/__opentypeless_cloud'

export async function cloudFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const source = input instanceof Request ? input.url : String(input)
  const apiBase = import.meta.env.VITE_API_BASE_URL ?? 'https://www.opentypeless.com'
  const destination = new URL(source)
  if (
    destination.origin !== new URL(apiBase).origin ||
    destination.username ||
    destination.password
  ) {
    throw new Error('Untrusted cloud destination')
  }
  const mapped = cloudRequestUrl(
    source,
    apiBase,
    import.meta.env.DEV && import.meta.env.MODE !== 'test',
  )
  const request =
    input instanceof Request && mapped !== source
      ? new Request(new URL(mapped, window.location.origin), input)
      : input instanceof Request
        ? input
        : mapped
  return fetch(request, { ...init, redirect: 'error' })
}

export function cloudRequestUrl(url: string, apiBase: string, development: boolean): string {
  if (!development) return url
  const base = new URL(apiBase)
  const target = new URL(url)
  const basePath = base.pathname.replace(/\/$/, '')
  if (target.origin !== base.origin || !target.pathname.startsWith(`${basePath}/api/`)) {
    return url
  }
  return `${DEVELOPMENT_CLOUD_PREFIX}${target.pathname.slice(basePath.length)}${target.search}`
}
