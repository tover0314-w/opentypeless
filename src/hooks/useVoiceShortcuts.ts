import { isMacPlatform, useAppStore } from '../stores/appStore'
import type { ShortcutBinding } from '../stores/appStore'

export type VoiceShortcutRole = 'dictation' | 'ask' | 'translate' | 'pasteLast'

/** Bindings for the three Typeless voice features plus the editor's macOS-only Fn presets. */
export function useVoiceShortcuts() {
  const hotkeys = useAppStore((s) => s.config.hotkeys)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const isMac = isMacPlatform()

  const bindings: Record<VoiceShortcutRole, ShortcutBinding[]> = {
    dictation: hotkeys.dictationBindings?.length ? hotkeys.dictationBindings : [hotkeys.dictation],
    ask: hotkeys.askBindings ?? (hotkeys.ask ? [hotkeys.ask] : []),
    translate: hotkeys.translateBindings ?? (hotkeys.translate ? [hotkeys.translate] : []),
    pasteLast: hotkeys.pasteLast ? [hotkeys.pasteLast] : [],
  }
  const secondary = [hotkeys.editSelection, hotkeys.switchScene, hotkeys.openApp].filter(
    (binding): binding is ShortcutBinding => Boolean(binding),
  )

  const otherBindings = (role: VoiceShortcutRole) => [
    ...(role === 'dictation' ? [] : bindings.dictation),
    ...(role === 'ask' ? [] : bindings.ask),
    ...(role === 'translate' ? [] : bindings.translate),
    ...(role === 'pasteLast' ? [] : bindings.pasteLast),
    ...secondary,
  ]

  const specialOptions: Record<VoiceShortcutRole, Array<{ value: string; label: string }>> = {
    dictation: isMac ? [{ value: 'Fn', label: 'Fn' }] : [],
    ask: isMac ? [{ value: 'Fn+Space', label: 'Fn + Space' }] : [],
    translate: isMac ? [{ value: 'Fn+LeftShift', label: 'Fn + Left Shift' }] : [],
    pasteLast: [],
  }

  const setBindings = (role: VoiceShortcutRole, next: ShortcutBinding[]) => {
    const nextHotkeys = { ...useAppStore.getState().config.hotkeys }
    if (role === 'dictation') {
      if (next.length === 0) return
      nextHotkeys.dictationBindings = next
      nextHotkeys.dictation = next[0]
    } else if (role === 'ask') {
      nextHotkeys.askBindings = next
      nextHotkeys.ask = next[0] ?? null
    } else if (role === 'translate') {
      nextHotkeys.translateBindings = next
      nextHotkeys.translate = next[0] ?? null
    } else {
      nextHotkeys.pasteLast = next[0] ?? null
    }
    updateConfig({ hotkeys: nextHotkeys })
  }

  const setPrimaryBinding = (role: VoiceShortcutRole, binding: ShortcutBinding) => {
    const current = useAppStore.getState().config.hotkeys
    const currentBindings =
      role === 'dictation'
        ? current.dictationBindings
        : role === 'ask'
          ? current.askBindings
          : role === 'translate'
            ? current.translateBindings
            : []
    setBindings(role, [binding, ...(currentBindings ?? []).slice(1)])
  }

  return { bindings, otherBindings, specialOptions, setBindings, setPrimaryBinding }
}
