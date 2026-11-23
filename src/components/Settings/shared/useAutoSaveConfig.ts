import { useEffect } from 'react'
import { useAppStore, type AppConfig } from '../../../stores/appStore'
import {
  getConfig,
  getConfigCommit,
  getHotkeyRegistrationError,
  updateConfig,
  setAutoStart,
} from '../../../lib/tauri'
import { toast } from '../../toast-service'
import { useDirtyConfig } from './useDirtyConfig'
import { valuesEqual } from '../../../lib/configValuesEqual'
import { mergeAppConfigDraft } from '../../../lib/appConfigMergePolicy'
import { ConfigCommitCursor } from '../../../lib/configCommit'
import {
  isAppConfigSnapshot,
  isHotkeyConfigurationConsistent,
} from '../../../lib/appConfigSnapshot'
import { pendingConfigSaves } from '../../../lib/configSaveRegistry'
import i18n from '../../../i18n'

export const AUTOSAVE_DELAY_MS = 400
let saveQueue: Promise<void> = Promise.resolve()
let latestSaveAttempt = 0

export function enqueueConfigSave() {
  const pending = saveQueue.then(async () => {
    const { config, savedConfig } = useAppStore.getState()
    if (savedConfig === null) return false
    if (valuesEqual(config, savedConfig)) return true
    return saveDirtyConfig()
  })
  saveQueue = pending.then(
    () => {},
    () => {
      console.error('[settings] autosave queue failed')
    },
  )
  return pending
}

async function refreshHotkeyRegistrationError() {
  try {
    useAppStore.getState().setHotkeyRegistrationError(await getHotkeyRegistrationError())
  } catch {
    console.error('[settings] failed to refresh hotkey registration error')
  }
}

export async function saveDirtyConfig(): Promise<boolean> {
  const { config, savedConfig, setConfig, setSavedConfig } = useAppStore.getState()
  if (!isHotkeyConfigurationConsistent(config)) {
    toast(i18n.t('common.saveFailed'), 'error')
    return false
  }
  const saveAttempt = ++latestSaveAttempt
  const autoStartChanged = savedConfig !== null && savedConfig.auto_start !== config.auto_start
  let autoStartApplied = false
  let registeredSaveId: string | null = null
  try {
    const saveId = crypto.randomUUID()
    pendingConfigSaves.register(saveId, config)
    registeredSaveId = saveId
    if (autoStartChanged) {
      await setAutoStart(config.auto_start)
      autoStartApplied = true
    }
    const receipt = await updateConfig(config, saveId)
    if (receipt && receipt.commit !== undefined) {
      const cursor = new ConfigCommitCursor<AppConfig>(
        (value): value is AppConfig =>
          isAppConfigSnapshot(value) && valuesEqual(value, receipt.config),
      )
      if (
        !cursor.initialize(receipt.commit) ||
        cursor.snapshot?.saveId !== saveId ||
        cursor.snapshot?.revision !== receipt.savedRevision
      ) {
        toast(i18n.t('common.saveFailed'), 'error')
        await refreshHotkeyRegistrationError()
        return false
      }
    }
    const persistedConfig = receipt?.config ?? config
    await refreshHotkeyRegistrationError()
    if (useAppStore.getState().configCommit !== null) {
      if (!receipt?.commit) {
        toast(i18n.t('common.saveFailed'), 'error')
        return false
      }
      const disposition = useAppStore.getState().applyConfigCommit(receipt.commit)
      if (disposition !== 'accepted' && disposition !== 'duplicate' && disposition !== 'stale') {
        toast(i18n.t('common.saveFailed'), 'error')
      }
      return disposition === 'accepted' || disposition === 'duplicate'
    }
    const current = useAppStore.getState()
    const compatibleBaseline =
      receipt &&
      savedConfig &&
      current.savedConfig &&
      Object.keys(persistedConfig).every((key) => {
        const field = key as keyof AppConfig
        return (
          valuesEqual(current.savedConfig![field], savedConfig[field]) ||
          valuesEqual(current.savedConfig![field], config[field]) ||
          valuesEqual(current.savedConfig![field], persistedConfig[field])
        )
      })
    if (
      saveAttempt === latestSaveAttempt &&
      (current.savedConfig === savedConfig || compatibleBaseline)
    ) {
      if (receipt) {
        setConfig(
          valuesEqual(current.config, config)
            ? persistedConfig
            : mergeAppConfigDraft(config, current.config, persistedConfig),
        )
      }
      setSavedConfig(persistedConfig)
      return true
    }
    return valuesEqual(useAppStore.getState().savedConfig, persistedConfig)
  } catch (e) {
    let msg = e instanceof Error ? e.message : i18n.t('common.saveFailed')
    if (autoStartApplied && savedConfig && useAppStore.getState().savedConfig === savedConfig) {
      await setAutoStart(savedConfig.auto_start).catch((rollbackError) => {
        const rollbackMessage =
          rollbackError instanceof Error ? rollbackError.message : i18n.t('common.saveFailed')
        msg = `${msg} · ${i18n.t('settings.launchAtStartup')}: ${rollbackMessage}`
      })
    }
    toast(msg, 'error')
    try {
      if (useAppStore.getState().configCommit !== null) {
        useAppStore.getState().applyConfigCommit(await getConfigCommit())
      } else {
        const baselineBeforeRead = useAppStore.getState().savedConfig
        const backendConfig = await getConfig()
        if (
          useAppStore.getState().configCommit === null &&
          useAppStore.getState().savedConfig === baselineBeforeRead
        ) {
          if (useAppStore.getState().config === config) setConfig(backendConfig)
          setSavedConfig(backendConfig)
        }
      }
    } catch {
      // Keep the local edit if backend truth cannot be reloaded; the next edit retries.
    }
    await refreshHotkeyRegistrationError()
    return false
  } finally {
    if (registeredSaveId !== null) pendingConfigSaves.retire(registeredSaveId)
  }
}

/** Persists settings edits shortly after they happen, rolling back to backend truth on failure. */
export function useAutoSaveConfig() {
  const isDirty = useDirtyConfig()
  const config = useAppStore((s) => s.config)

  useEffect(
    () => () => {
      void enqueueConfigSave()
    },
    [],
  )

  useEffect(() => {
    if (!isDirty) return
    const timer = setTimeout(() => {
      void enqueueConfigSave()
    }, AUTOSAVE_DELAY_MS)
    return () => clearTimeout(timer)
  }, [isDirty, config])
}
