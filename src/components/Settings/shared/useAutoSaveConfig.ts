import { useEffect, useRef } from 'react'
import { useAppStore } from '../../../stores/appStore'
import {
  getConfig,
  getHotkeyRegistrationError,
  updateConfig,
  setAutoStart,
} from '../../../lib/tauri'
import { toast } from '../../toast-service'
import { useDirtyConfig } from './useDirtyConfig'

export const AUTOSAVE_DELAY_MS = 400

async function refreshHotkeyRegistrationError() {
  try {
    useAppStore.getState().setHotkeyRegistrationError(await getHotkeyRegistrationError())
  } catch (error) {
    console.error('[settings] failed to refresh hotkey registration error', error)
  }
}

export async function saveDirtyConfig() {
  const { config, savedConfig, setConfig, setSavedConfig } = useAppStore.getState()
  const autoStartChanged = savedConfig !== null && savedConfig.auto_start !== config.auto_start
  let autoStartApplied = false
  try {
    if (autoStartChanged) {
      await setAutoStart(config.auto_start)
      autoStartApplied = true
    }
    await updateConfig(config)
    await refreshHotkeyRegistrationError()
    setSavedConfig(config)
  } catch (e) {
    const msg = e instanceof Error ? e.message : 'Failed to save settings'
    if (autoStartApplied && savedConfig) {
      await setAutoStart(savedConfig.auto_start).catch((rollbackError) => {
        console.error('[settings] failed to roll back auto-start', rollbackError)
      })
    }
    toast(msg, 'error')
    try {
      const backendConfig = await getConfig()
      setConfig(backendConfig)
      setSavedConfig(backendConfig)
    } catch {
      // Keep the local edit if backend truth cannot be reloaded; the next edit retries.
    }
    await refreshHotkeyRegistrationError()
  }
}

/** Persists settings edits shortly after they happen, rolling back to backend truth on failure. */
export function useAutoSaveConfig() {
  const isDirty = useDirtyConfig()
  const config = useAppStore((s) => s.config)
  const saving = useRef<Promise<void> | null>(null)

  useEffect(() => {
    if (!isDirty) return
    const timer = setTimeout(() => {
      const previous = saving.current ?? Promise.resolve()
      saving.current = previous.then(saveDirtyConfig).finally(() => {
        saving.current = null
      })
    }, AUTOSAVE_DELAY_MS)
    return () => clearTimeout(timer)
  }, [isDirty, config])
}
