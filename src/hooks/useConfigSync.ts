import { useEffect, useState } from 'react'
import { listen } from '@tauri-apps/api/event'
import { connectConfigSync } from '../lib/configSyncConnection'
import { getConfigCommit } from '../lib/tauri'
import { useAppStore } from '../stores/appStore'
import i18n from '../i18n'
import { ConfigCommitCursor } from '../lib/configCommit'
import { isAppConfigSnapshot } from '../lib/appConfigSnapshot'
import type { AppConfig } from '../stores/appStore'

export function useConfigSync(): 'loading' | 'ready' | 'error' {
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  useEffect(() => {
    let active = true
    const readController = new AbortController()
    let readEpoch: string | null = null
    const connection = connectConfigSync({
      subscribe: (receive) => listen<unknown>('config:commit', (event) => receive(event.payload)),
      read: () => {
        readEpoch = useAppStore.getState().configCommit?.configEpoch ?? null
        return getConfigCommit(readController.signal)
      },
      initialize: (payload) => {
        const cursor = new ConfigCommitCursor<AppConfig>(isAppConfigSnapshot)
        if (!cursor.initialize(payload)) return false
        const read = cursor.snapshot!
        const current = useAppStore.getState().configCommit
        if (
          current &&
          current.configEpoch !== readEpoch &&
          current.configEpoch !== read.configEpoch
        ) {
          return false
        }
        if (
          current &&
          current.configEpoch === read.configEpoch &&
          read.revision < current.revision
        ) {
          return true
        }
        return useAppStore.getState().initializeConfigCommit(payload)
      },
      accept: (payload) => useAppStore.getState().applyConfigCommit(payload),
      changed: () => {
        const language = useAppStore.getState().config.ui_language
        if (language !== i18n.language) {
          void i18n.changeLanguage(language).catch(() => {
            if (active) console.error('Failed to apply configuration language')
          })
        }
        try {
          localStorage.setItem('ui_language', language)
        } catch {
          if (active) console.error('Failed to cache configuration language')
        }
      },
      failed: (stage) => {
        if (!active) return
        console.error(`Configuration synchronization failed: ${stage}`)
        setStatus('error')
      },
    })
    void connection.ready.then((ready) => {
      if (active && ready) setStatus('ready')
    })
    return () => {
      active = false
      readController.abort()
      connection.dispose()
    }
  }, [])
  return status
}
