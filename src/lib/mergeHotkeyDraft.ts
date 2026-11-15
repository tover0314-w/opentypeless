import type { HotkeyConfig } from '../stores/appStore'
import { valuesEqual } from './configValuesEqual'

const HOTKEY_FIELD_GROUPS = [
  ['dictation', 'dictationBindings'],
  ['ask', 'askBindings'],
  ['translate', 'translateBindings'],
  ['dictationMode'],
  ['editSelection'],
  ['switchScene'],
  ['openApp'],
  ['pasteLast'],
] as const

export function mergeHotkeyDraft(
  local: HotkeyConfig,
  baseline: HotkeyConfig,
  persisted: HotkeyConfig,
): HotkeyConfig {
  const retained: Partial<HotkeyConfig> = {}
  for (const group of HOTKEY_FIELD_GROUPS) {
    if (group.some((field) => !valuesEqual(local[field], baseline[field]))) {
      Object.assign(retained, Object.fromEntries(group.map((field) => [field, local[field]])))
    }
  }
  return { ...persisted, ...retained }
}
