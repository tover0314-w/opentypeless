import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useAppStore } from '../../stores/appStore'
import { useVoiceShortcuts } from '../useVoiceShortcuts'

afterEach(cleanup)

describe('voice shortcut updates', () => {
  it.each(['hold', 'toggle', 'auto'] as const)(
    'changes the existing trigger control to %s without discarding bindings',
    (mode) => {
      const original = useAppStore.getState().config
      const extra = { primary: '9', modifiers: ['Ctrl', 'Alt'] }
      try {
        const state = useAppStore.getState()
        state.updateConfig({
          hotkeys: {
            ...state.config.hotkeys,
            dictationBindings: [state.config.hotkeys.dictation, extra],
          },
        })
        const before = useAppStore.getState().config.hotkeys
        useAppStore.getState().updateConfig({ hotkey_mode: mode })
        const after = useAppStore.getState().config
        expect(after.hotkey_mode).toBe(mode)
        expect(after.hotkeys.dictationMode).toBe(mode)
        expect(after.hotkeys.dictationBindings).toEqual(before.dictationBindings)
        expect(after.hotkeys.askBindings).toEqual(before.askBindings)
        expect(after.hotkeys.translateBindings).toEqual(before.translateBindings)
      } finally {
        useAppStore.setState({ config: original })
      }
    },
  )

  it.each([
    { role: 'dictation' as const, listKey: 'dictationBindings' as const },
    { role: 'ask' as const, listKey: 'askBindings' as const },
    { role: 'translate' as const, listKey: 'translateBindings' as const },
  ])('preserves the latest extra binding when replacing $role primary', ({ role, listKey }) => {
    const original = useAppStore.getState().config
    const hook = renderHook(() => useVoiceShortcuts())
    const replacePrimary = hook.result.current.setPrimaryBinding
    const extra = { primary: '9', modifiers: ['Ctrl', 'Alt'] }
    const primary = { primary: 'K', modifiers: ['Ctrl', 'Shift'] }
    try {
      act(() => {
        const current = useAppStore.getState()
        current.updateConfig({
          hotkeys: {
            ...current.config.hotkeys,
            [listKey]: [current.config.hotkeys.dictation, extra],
          },
        })
        replacePrimary(role, primary)
      })
      expect(useAppStore.getState().config.hotkeys[listKey]).toEqual([primary, extra])
      expect(useAppStore.getState().config.hotkeys[role]).toEqual(primary)
    } finally {
      hook.unmount()
      useAppStore.setState({ config: original })
    }
  })

  it.each(['ask', 'translate', 'pasteLast'] as const)(
    'restores a disabled $role without changing other shortcuts',
    (role) => {
      const original = useAppStore.getState().config
      const hook = renderHook(() => useVoiceShortcuts())
      const primary = { primary: 'K', modifiers: ['Ctrl', 'Shift'] }
      try {
        act(() => {
          hook.result.current.setBindings(role, [])
        })
        const disabled = useAppStore.getState().config.hotkeys
        expect(disabled[role]).toBeNull()
        act(() => hook.result.current.setPrimaryBinding(role, primary))
        const restored = useAppStore.getState().config.hotkeys
        expect(restored[role]).toEqual(primary)
        expect(restored.dictationBindings).toEqual(disabled.dictationBindings)
        expect(restored.dictationMode).toBe(disabled.dictationMode)
      } finally {
        hook.unmount()
        useAppStore.setState({ config: original })
      }
    },
  )

  it('preserves another shortcut changed before the current render catches up', () => {
    const original = useAppStore.getState().config
    const hook = renderHook(() => useVoiceShortcuts())
    const setBindings = hook.result.current.setBindings
    const ask = { primary: 'J', modifiers: ['Ctrl', 'Shift'] }
    const dictation = { primary: 'K', modifiers: ['Ctrl', 'Shift'] }
    try {
      act(() => {
        const current = useAppStore.getState()
        current.updateConfig({
          hotkeys: { ...current.config.hotkeys, ask, askBindings: [ask] },
        })
        setBindings('dictation', [dictation])
      })
      const hotkeys = useAppStore.getState().config.hotkeys
      expect(hotkeys.ask).toEqual(ask)
      expect(hotkeys.askBindings).toEqual([ask])
      expect(hotkeys.dictation).toEqual(dictation)
    } finally {
      hook.unmount()
      useAppStore.setState({ config: original })
    }
  })
})
