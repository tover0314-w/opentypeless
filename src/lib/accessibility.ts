import type { AppConfig } from '../stores/appStore'

export function isMacPlatform() {
  return typeof navigator !== 'undefined' && navigator.platform.toUpperCase().includes('MAC')
}

function usesFnHotkey(config: AppConfig) {
  const legacyUsesFn = [config.hotkey, config.ask_hotkey].some((hotkey) =>
    hotkey?.split('+').some((part) => part.trim().toLowerCase() === 'fn'),
  )
  const hotkeys = config.hotkeys
  const bindings = [
    ...(hotkeys?.dictationBindings?.length
      ? hotkeys.dictationBindings
      : hotkeys?.dictation
        ? [hotkeys.dictation]
        : []),
    ...(hotkeys?.translateBindings?.length
      ? hotkeys.translateBindings
      : hotkeys?.translate
        ? [hotkeys.translate]
        : []),
    ...(hotkeys?.askBindings?.length ? hotkeys.askBindings : hotkeys?.ask ? [hotkeys.ask] : []),
  ]
  return (
    legacyUsesFn ||
    bindings.some(
      (binding) =>
        binding.primary?.trim().toLowerCase() === 'fn' ||
        binding.modifiers.some((modifier) => modifier.trim().toLowerCase() === 'fn'),
    )
  )
}

export function needsMacAccessibility(config: AppConfig) {
  const usesAutomatedInput = config.insertion_strategy !== 'clipboardCopyOnly'
  return isMacPlatform() && (usesAutomatedInput || usesFnHotkey(config))
}
