import { useEffect } from 'react'
import { useAppStore } from '../stores/appStore'
import type { Theme } from '../stores/appStore'

export function useTheme() {
  const theme = useAppStore((s) => s.config.theme)

  useEffect(() => {
    const root = document.documentElement

    function applyTheme(t: Theme) {
      if (t === 'dark') {
        root.classList.add('dark')
      } else if (t === 'light') {
        root.classList.remove('dark')
      } else {
        // system
        const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
        root.classList.toggle('dark', prefersDark)
      }
    }

    applyTheme(theme)

    if (theme === 'system') {
      let active = true
      const mq = window.matchMedia('(prefers-color-scheme: dark)')
      const handler = (e: MediaQueryListEvent) => {
        if (!active || useAppStore.getState().config.theme !== 'system') return
        root.classList.toggle('dark', e.matches)
      }
      mq.addEventListener('change', handler)
      return () => {
        active = false
        mq.removeEventListener('change', handler)
      }
    }
  }, [theme])

  return theme
}
