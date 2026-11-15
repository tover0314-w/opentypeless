import { useAppStore } from '../../../stores/appStore'
import { valuesEqual } from '../../../lib/configValuesEqual'

export function useDirtyConfig() {
  const config = useAppStore((state) => state.config)
  const savedConfig = useAppStore((state) => state.savedConfig)
  return savedConfig !== null && !valuesEqual(config, savedConfig)
}
