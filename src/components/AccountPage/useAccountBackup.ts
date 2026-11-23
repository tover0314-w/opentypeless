import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore, type AppConfig } from '../../stores/appStore'
import { useAuthStore } from '../../stores/authStore'
import { downloadBackup, uploadBackup } from '../../lib/api'
import { createBackupSettings, mergeBackupSettings } from '../../lib/backup-settings'
import { valuesEqual } from '../../lib/configValuesEqual'
import { pendingConfigSaves } from '../../lib/configSaveRegistry'
import { ConfigCommitCursor } from '../../lib/configCommit'
import { isAppConfigSnapshot, isHotkeyConfigurationConsistent } from '../../lib/appConfigSnapshot'
import { mergeAppConfigDraft } from '../../lib/appConfigMergePolicy'
import {
  getConfig,
  getCloudBackupSnapshot,
  validateBackupData,
  restoreBackupData,
  setAutoStart,
  updateConfig as persistConfig,
} from '../../lib/tauri'

export function useAccountBackup() {
  const { t } = useTranslation()
  const user = useAuthStore((state) => state.user)
  const setConfig = useAppStore((state) => state.setConfig)
  const setSavedConfig = useAppStore((state) => state.setSavedConfig)
  const setHistory = useAppStore((state) => state.setHistory)
  const setDictionary = useAppStore((state) => state.setDictionary)
  const setCorrectionRules = useAppStore((state) => state.setCorrectionRules)
  const [backupLoading, setBackupLoading] = useState(false)
  const [backupMsg, setBackupMsg] = useState<string | null>(null)
  const backupRequestId = useRef(0)
  const pendingRequestId = useRef<number | null>(null)
  const mounted = useRef(false)

  useEffect(() => {
    mounted.current = true
    pendingRequestId.current = null
    setBackupLoading(false)
    setBackupMsg(null)
    return () => {
      mounted.current = false
      backupRequestId.current += 1
    }
  }, [user])

  const handleBackup = async () => {
    if (!mounted.current || !user || useAuthStore.getState().user !== user) return
    if (pendingRequestId.current !== null) return
    const requestId = ++backupRequestId.current
    pendingRequestId.current = requestId
    setBackupLoading(true)
    setBackupMsg(null)
    try {
      const { config, history, dictionary, correctionRules } = await getCloudBackupSnapshot()
      const safeConfig = createBackupSettings(config)
      if (
        !mounted.current ||
        requestId !== backupRequestId.current ||
        useAuthStore.getState().user !== user
      ) {
        return
      }
      await uploadBackup({
        history: history.map((entry) =>
          entry.output_error === undefined ? entry : { ...entry, output_error: null },
        ),
        dictionary: { entries: dictionary, correction_rules: correctionRules },
        settings: safeConfig,
      })
      if (requestId !== backupRequestId.current) return
      setBackupMsg(t('account.toast.backupOk'))
    } catch (error) {
      if (requestId === backupRequestId.current) {
        const message = error instanceof Error ? error.message : String(error)
        setBackupMsg(
          message === 'backup_history_too_large'
            ? t('account.backupHistoryLimit')
            : error instanceof Error
              ? error.message
              : t('account.toast.backupFail'),
        )
      }
    } finally {
      if (pendingRequestId.current === requestId) pendingRequestId.current = null
      if (requestId === backupRequestId.current) setBackupLoading(false)
    }
  }

  const handleRestore = async () => {
    if (!mounted.current || !user || useAuthStore.getState().user !== user) return
    if (pendingRequestId.current !== null) return
    const requestId = ++backupRequestId.current
    pendingRequestId.current = requestId
    const account = useAuthStore.getState().user
    const isCurrentRestore = () =>
      requestId === backupRequestId.current && useAuthStore.getState().user === account
    setBackupLoading(true)
    setBackupMsg(null)
    let autoStartApplied = false
    let settingsRestored = false
    let settingsWriteReturned = false
    let dataRestoreStarted = false
    let registeredSaveId: string | null = null
    try {
      const data = await downloadBackup()
      if (!isCurrentRestore()) return
      if (data.history != null || data.dictionary != null) {
        await validateBackupData(data.history ?? null, data.dictionary ?? null)
        if (!isCurrentRestore()) return
      }
      if (data.settings) {
        const currentConfig = useAppStore.getState().config
        const savedBeforeRestore = useAppStore.getState().savedConfig
        const restoredConfig = mergeBackupSettings(currentConfig, data.settings)
        if (!isHotkeyConfigurationConsistent(restoredConfig)) {
          throw new Error('Invalid shortcut configuration')
        }
        if (restoredConfig.auto_start !== currentConfig.auto_start) {
          await setAutoStart(restoredConfig.auto_start)
          autoStartApplied = true
          if (!isCurrentRestore()) return
        }
        const versioned = useAppStore.getState().configCommit !== null
        if (versioned) {
          registeredSaveId = crypto.randomUUID()
          pendingConfigSaves.register(registeredSaveId, restoredConfig)
          setConfig(restoredConfig)
        }
        try {
          const receipt = registeredSaveId
            ? await persistConfig(restoredConfig, registeredSaveId)
            : await persistConfig(restoredConfig)
          settingsWriteReturned = true
          if (!isCurrentRestore()) return
          if (useAppStore.getState().configCommit !== null) {
            if (!registeredSaveId || !receipt?.commit) {
              throw new Error('Backup configuration receipt unavailable')
            }
            const cursor = new ConfigCommitCursor<AppConfig>(isAppConfigSnapshot)
            if (
              !cursor.initialize(receipt.commit) ||
              cursor.snapshot?.saveId !== registeredSaveId ||
              cursor.snapshot?.revision !== receipt.savedRevision ||
              !valuesEqual(cursor.snapshot?.config, receipt.config)
            ) {
              throw new Error('Backup configuration receipt invalid')
            }
            const disposition = useAppStore.getState().applyConfigCommit(receipt.commit)
            if (disposition !== 'accepted' && disposition !== 'duplicate') {
              throw new Error('Backup configuration restore superseded')
            }
          }
        } catch (error) {
          if (
            versioned &&
            isCurrentRestore() &&
            useAppStore.getState().savedConfig === savedBeforeRestore
          ) {
            const latest = useAppStore.getState().config
            setConfig(mergeAppConfigDraft(restoredConfig, latest, currentConfig))
          }
          if (
            autoStartApplied &&
            isCurrentRestore() &&
            useAppStore.getState().savedConfig === savedBeforeRestore
          ) {
            try {
              await setAutoStart(currentConfig.auto_start)
            } catch (rollbackError) {
              const saveMessage =
                error instanceof Error ? error.message : t('account.toast.restoreFail')
              const rollbackMessage =
                rollbackError instanceof Error ? rollbackError.message : t('common.saveFailed')
              throw Object.assign(
                new Error(`${saveMessage} · ${t('settings.launchAtStartup')}: ${rollbackMessage}`),
                { cause: rollbackError },
              )
            }
          }
          throw error
        }
        if (!isCurrentRestore()) return
        if (useAppStore.getState().configCommit === null) {
          const persistedConfig = await getConfig()
          if (!isCurrentRestore()) return
          if (useAppStore.getState().configCommit !== null) {
            throw new Error('Backup configuration stream changed during readback')
          }
          const latestConfig = useAppStore.getState().config
          const mergedConfig = { ...persistedConfig }
          for (const key of Object.keys(latestConfig) as Array<keyof typeof latestConfig>) {
            if (!valuesEqual(latestConfig[key], currentConfig[key])) {
              Object.assign(mergedConfig, { [key]: latestConfig[key] })
            }
          }
          setConfig(mergedConfig)
          setSavedConfig(persistedConfig)
        }
        settingsRestored = true
      }
      if (data.history != null || data.dictionary != null) {
        const beforeRestore = useAppStore.getState()
        dataRestoreStarted = true
        const restoredData = await restoreBackupData(data.history ?? null, data.dictionary ?? null)
        if (!isCurrentRestore()) return
        const latest = useAppStore.getState()
        if (latest.history === beforeRestore.history) setHistory(restoredData.history)
        if (latest.dictionary === beforeRestore.dictionary) setDictionary(restoredData.dictionary)
        if (latest.correctionRules === beforeRestore.correctionRules)
          setCorrectionRules(restoredData.correctionRules)
      }
      setBackupMsg(t('account.toast.restoreOk'))
    } catch (error) {
      if (isCurrentRestore()) {
        const message = error instanceof Error ? error.message : t('account.toast.restoreFail')
        const notice = settingsRestored
          ? t('account.toast.restorePartial')
          : settingsWriteReturned
            ? t('account.toast.restoreSettingsUnknown')
            : dataRestoreStarted
              ? t('account.toast.restoreDataUnknown')
              : null
        setBackupMsg(notice ? `${notice} ${message}` : message)
      }
    } finally {
      if (registeredSaveId !== null) pendingConfigSaves.retire(registeredSaveId)
      if (pendingRequestId.current === requestId) pendingRequestId.current = null
      if (isCurrentRestore()) setBackupLoading(false)
    }
  }

  return { backupLoading, backupMsg, handleBackup, handleRestore }
}
