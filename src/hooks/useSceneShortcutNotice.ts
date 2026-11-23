import { useEffect } from 'react'
import { listen } from '@tauri-apps/api/event'
import { useCapsuleAlertStore } from '../stores/capsuleAlertStore'
import { useAppStore } from '../stores/appStore'

export function useSceneShortcutNotice() {
  useEffect(() => {
    let active = true
    let unlisten: (() => void) | null = null
    let pending: { configEpoch: string; revision: number } | null = null
    let highestRevision = -1
    const epoch = useAppStore.getState().configCommit?.configEpoch
    const publish = () => {
      const commit = useAppStore.getState().configCommit
      if (!active || !pending || !commit || commit.configEpoch !== pending.configEpoch) return
      if (commit.revision < pending.revision) return
      if (commit.revision === pending.revision) {
        const name = commit.config.active_scene?.name
        if (name === undefined || (name.trim() && name.length <= 500)) {
          useCapsuleAlertStore.getState().show('scene_changed', name)
        }
      }
      pending = null
    }
    const unsubscribe = useAppStore.subscribe(publish)
    const release = (stop: () => void) => {
      try {
        Promise.resolve(stop()).catch(() => {})
      } catch {
        return
      }
    }
    listen<unknown>('scene:shortcut-changed', ({ payload }) => {
      if (!active) return
      if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return
      const notice = payload as Record<string, unknown>
      const currentEpoch = epoch ?? useAppStore.getState().configCommit?.configEpoch
      if (
        !currentEpoch ||
        notice.configEpoch !== currentEpoch ||
        typeof notice.revision !== 'number' ||
        !Number.isSafeInteger(notice.revision) ||
        notice.revision < 0 ||
        notice.revision <= highestRevision
      ) {
        return
      }
      highestRevision = notice.revision
      pending = { configEpoch: currentEpoch, revision: notice.revision }
      publish()
    })
      .then((stop) => {
        if (active) unlisten = stop
        else release(stop)
      })
      .catch(() => console.error('Could not subscribe to scene shortcut feedback'))
    return () => {
      active = false
      unsubscribe()
      if (unlisten) release(unlisten)
    }
  }, [])
}
