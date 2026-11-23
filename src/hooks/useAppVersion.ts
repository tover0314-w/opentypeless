import { useEffect, useState } from 'react'
import { getVersion } from '@tauri-apps/api/app'
import { APP_VERSION_HEADER_VALUE } from '../lib/constants'

let cached: string | null = null

/** Bundle version from Tauri; the Vite build constant is only a fallback outside the app. */
export function useAppVersion(): string {
  const [version, setVersion] = useState(cached ?? APP_VERSION_HEADER_VALUE)
  useEffect(() => {
    if (cached) return
    let alive = true
    getVersion()
      .then((value) => {
        cached = value
        if (alive) setVersion(value)
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [])
  return version
}
