import { useAppStore } from '../../../stores/appStore'

export function useDirtyConfig() {
  const config = useAppStore((s) => s.config)
  const savedConfig = useAppStore((s) => s.savedConfig)
  return savedConfig !== null && JSON.stringify(config) !== JSON.stringify(savedConfig)
}
