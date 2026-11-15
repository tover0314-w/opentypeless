import { useCallback, useEffect, useRef } from 'react'
import { emitTo } from '@tauri-apps/api/event'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useAppStore } from '../stores/appStore'

export function useUseCaseVoiceHint() {
  const mounted = useRef(false)
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>())
  const pending = useRef(new Set<string>())

  useEffect(() => {
    mounted.current = true
    const scheduled = timers.current
    const applications = pending.current
    return () => {
      mounted.current = false
      scheduled.forEach(clearTimeout)
      scheduled.clear()
      applications.clear()
    }
  }, [])

  return useCallback(async (application: string, url: string) => {
    try {
      await openUrl(url)
      if (!mounted.current || pending.current.has(application)) return
      const key = `capsule.useVoice.${application}`
      if (localStorage.getItem(key)) return
      pending.current.add(application)
      const timer = setTimeout(async () => {
        timers.current.delete(timer)
        try {
          const { pipelineState, pipelineError, config } = useAppStore.getState()
          const binding = config.hotkeys.dictationBindings?.[0] ?? config.hotkeys.dictation
          if (!mounted.current || pipelineState !== 'idle' || pipelineError || !binding) return
          await emitTo('capsule', 'capsule:alert', { kind: 'use_voice' })
          localStorage.setItem(key, '1')
        } catch (error) {
          console.error('Failed to show use-case voice hint:', error)
        } finally {
          pending.current.delete(application)
        }
      }, 1000)
      timers.current.add(timer)
    } catch (error) {
      console.error('Failed to open use case:', error)
    }
  }, [])
}
