import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { pauseHotkey } from '../../../lib/tauri'
import { ShortcutKeycapInput, ShortcutRecorderBox } from '../ShortcutKeycapInput'
import { useLayoutEffect } from 'react'

const fnCapture = vi.hoisted(() => ({
  edge: null as null | ((pressed: boolean) => void),
  status: 'ready' as 'ready' | 'error',
  listeners: new Map<(pressed: boolean) => void, boolean>(),
}))
vi.mock('../../../hooks/useFnShortcutCapture', () => ({
  useFnShortcutCapture: (enabled: boolean, onEdge: (pressed: boolean) => void) => {
    useLayoutEffect(() => {
      fnCapture.listeners.set(onEdge, enabled)
      return () => {
        fnCapture.listeners.delete(onEdge)
      }
    }, [enabled, onEdge])
    fnCapture.edge = (pressed) => {
      for (const [listener, active] of fnCapture.listeners) {
        if (active) listener(pressed)
      }
    }
    return fnCapture.status
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('../../../lib/tauri', () => ({
  pauseHotkey: vi.fn().mockResolvedValue(undefined),
  resumeHotkey: vi.fn().mockResolvedValue(undefined),
}))

beforeEach(() => {
  vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel')
})

afterEach(async () => {
  fireEvent.keyUp(window, { key: 'k' })
  fireEvent.keyUp(window, { key: 'Escape' })
  fireEvent.keyUp(window, { key: 'Control' })
  fireEvent.keyUp(window, { key: 'Shift' })
  await act(async () => cleanup())
  vi.clearAllMocks()
  fnCapture.status = 'ready'
  fnCapture.listeners.clear()
  vi.restoreAllMocks()
})

describe('primary shortcut retention', () => {
  it('promotes an existing binding without dropping or mutating the others', () => {
    const bindings = [
      { primary: 'Fn', modifiers: [] },
      { primary: 'K', modifiers: ['Ctrl'] },
      { primary: 'J', modifiers: ['Ctrl'] },
    ]
    const change = vi.fn()
    render(
      <ShortcutKeycapInput
        label="Dictate"
        bindings={bindings}
        otherBindings={[]}
        required
        onChange={change}
      />,
    )
    const actions = screen.getAllByRole('button', { name: 'settings.shortcutMakePrimary' })
    expect(actions).toHaveLength(2)
    fireEvent.click(actions[1])
    expect(change).toHaveBeenCalledExactlyOnceWith([bindings[2], bindings[0], bindings[1]])
    expect(bindings.map((binding) => binding.primary)).toEqual(['Fn', 'K', 'J'])
    expect(pauseHotkey).not.toHaveBeenCalled()
  })

  it('disables promotion while a shortcut capture owns the editor', async () => {
    const change = vi.fn()
    render(
      <ShortcutKeycapInput
        label="Dictate"
        bindings={[
          { primary: 'Fn', modifiers: [] },
          { primary: 'K', modifiers: ['Ctrl'] },
        ]}
        otherBindings={[]}
        required
        onChange={change}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'shortcuts.change: Fn' }))
    await act(async () => {})
    const promote = screen.getByRole('button', { name: 'settings.shortcutMakePrimary' })
    expect(promote).toBeDisabled()
    fireEvent.click(promote)
    expect(change).not.toHaveBeenCalled()
  })

  it('does not add a primary action for a single binding', () => {
    render(
      <ShortcutKeycapInput
        label="Dictate"
        bindings={[{ primary: 'Fn', modifiers: [] }]}
        otherBindings={[]}
        required
        onChange={vi.fn()}
      />,
    )
    expect(screen.queryByRole('button', { name: 'settings.shortcutMakePrimary' })).toBeNull()
  })
})

describe('shortcut capture readiness', () => {
  it('revokes capture immediately when clicking outside without moving focus', async () => {
    const save = vi.fn()
    const cancel = vi.fn()
    render(
      <>
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={cancel} />
        <div data-testid="outside-blank">Blank area</div>
      </>,
    )
    await act(async () => {})
    fireEvent.pointerDown(screen.getByTestId('outside-blank'))
    expect(cancel).toHaveBeenCalledOnce()
    act(() => fnCapture.edge?.(true))
    act(() => fnCapture.edge?.(false))
    expect(save).not.toHaveBeenCalled()
  })

  it('does not reclaim focus or revive capture after cancellation when callbacks change', async () => {
    const save = vi.fn()
    const { rerender } = render(
      <>
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />
        <input aria-label="Keep ordinary focus" />
      </>,
    )
    await act(async () => {})
    const input = screen.getByRole('textbox', { name: 'Keep ordinary focus' })
    act(() => input.focus())
    rerender(
      <>
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />
        <input aria-label="Keep ordinary focus" />
      </>,
    )
    expect(input).toHaveFocus()
    act(() => fnCapture.edge?.(true))
    act(() => fnCapture.edge?.(false))
    expect(save).not.toHaveBeenCalled()
  })

  it('removes keyboard interception and Fn callbacks when the editor unmounts', async () => {
    const save = vi.fn()
    const editor = render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    act(() => fnCapture.edge?.(true))
    await act(async () => editor.unmount())
    render(<input aria-label="Normal typing after editor" />)
    const input = screen.getByRole('textbox', { name: 'Normal typing after editor' })
    input.focus()
    expect(fireEvent.keyDown(input, { key: 'k', code: 'KeyK' })).toBe(true)
    fireEvent.keyUp(input, { key: 'k', code: 'KeyK' })
    act(() => fnCapture.edge?.(false))
    expect(save).not.toHaveBeenCalled()
    expect(fnCapture.listeners.size).toBe(0)
  })

  it('does not capture ordinary typing after focus moves to another text field', async () => {
    const save = vi.fn()
    const cancel = vi.fn()
    render(
      <>
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={cancel} />
        <input aria-label="Ordinary text" />
      </>,
    )
    await act(async () => {})
    const input = screen.getByRole('textbox', { name: 'Ordinary text' })
    input.focus()
    expect(fireEvent.keyDown(input, { key: 'k', code: 'KeyK' })).toBe(true)
    fireEvent.keyUp(input, { key: 'k', code: 'KeyK' })
    expect(save).not.toHaveBeenCalled()
    expect(cancel).toHaveBeenCalled()
  })

  it('ignores new Fn edges after focus leaves capture even before the parent removes the editor', async () => {
    const save = vi.fn()
    render(
      <>
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />
        <input aria-label="Outside capture" />
      </>,
    )
    await act(async () => {})
    act(() => screen.getByRole('textbox', { name: 'Outside capture' }).focus())
    act(() => fnCapture.edge?.(true))
    act(() => fnCapture.edge?.(false))
    expect(save).not.toHaveBeenCalled()
  })

  it.each(['Enter', ' '])('does not intercept %s on the nested remove button', (key) => {
    const change = vi.fn()
    render(
      <ShortcutKeycapInput
        label="Shortcuts"
        bindings={[{ primary: 'K', modifiers: ['Ctrl'] }]}
        otherBindings={[]}
        onChange={change}
      />,
    )
    const remove = screen.getByRole('button', { name: 'shortcuts.remove' })
    remove.focus()
    expect(fireEvent.keyDown(remove, { key })).toBe(true)
    expect(document.querySelector('[data-editing]')).toBeNull()
    expect(change).not.toHaveBeenCalled()
    fireEvent.click(remove)
    expect(change).toHaveBeenCalledExactlyOnceWith([])
  })

  it('supports Enter activation after cancelling the previous editor with Escape', async () => {
    const firstChange = vi.fn()
    const secondChange = vi.fn()
    render(
      <>
        <ShortcutKeycapInput
          label="First shortcuts"
          bindings={[{ primary: 'K', modifiers: ['Ctrl'] }]}
          otherBindings={[]}
          onChange={firstChange}
        />
        <ShortcutKeycapInput
          label="Second shortcuts"
          bindings={[{ primary: 'J', modifiers: ['Ctrl'] }]}
          otherBindings={[]}
          onChange={secondChange}
        />
      </>,
    )
    const firstEditor = screen.getByRole('button', { name: 'shortcuts.change: Ctrl K' })
    firstEditor.focus()
    fireEvent.keyDown(firstEditor, { key: 'Enter', code: 'Enter' })
    fireEvent.keyUp(firstEditor, { key: 'Enter', code: 'Enter' })
    await act(async () => {})
    expect(firstEditor).toHaveAttribute('data-editing', 'true')
    fireEvent.keyDown(firstEditor, { key: 'Escape', code: 'Escape' })
    fireEvent.keyUp(firstEditor, { key: 'Escape', code: 'Escape' })
    await act(async () => {})
    expect(document.querySelector('[data-editing]')).toBeNull()
    const secondEditor = screen.getByRole('button', { name: 'shortcuts.change: Ctrl J' })
    secondEditor.focus()
    fireEvent.keyDown(secondEditor, { key: 'Enter', code: 'Enter' })
    fireEvent.keyUp(secondEditor, { key: 'Enter', code: 'Enter' })
    await act(async () => {})
    expect(secondEditor).toHaveAttribute('data-editing', 'true')
    fireEvent.keyDown(secondEditor, { key: 'm', code: 'KeyM', ctrlKey: true })
    fireEvent.keyUp(secondEditor, { key: 'm', code: 'KeyM' })
    fireEvent.keyUp(window, { key: 'Control', code: 'ControlLeft' })
    expect(firstChange).not.toHaveBeenCalled()
    expect(secondChange).toHaveBeenCalledExactlyOnceWith(
      [{ primary: 'M', modifiers: ['Ctrl'] }],
      true,
    )
  })

  it('allows only the latest settings editor to save a captured chord', async () => {
    const firstChange = vi.fn()
    const secondChange = vi.fn()
    render(
      <>
        <ShortcutKeycapInput
          label="First shortcuts"
          bindings={[{ primary: 'K', modifiers: ['Ctrl'] }]}
          otherBindings={[]}
          onChange={firstChange}
        />
        <ShortcutKeycapInput
          label="Second shortcuts"
          bindings={[{ primary: 'J', modifiers: ['Ctrl'] }]}
          otherBindings={[]}
          onChange={secondChange}
        />
      </>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'shortcuts.change: Ctrl K' }))
    await act(async () => {})
    fireEvent.click(screen.getByRole('button', { name: 'shortcuts.change: Ctrl J' }))
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'm', code: 'KeyM', ctrlKey: true })
    fireEvent.keyUp(window, { key: 'm', code: 'KeyM' })
    expect(secondChange).toHaveBeenCalledExactlyOnceWith(
      [{ primary: 'M', modifiers: ['Ctrl'] }],
      true,
    )
    expect(firstChange).not.toHaveBeenCalled()
  })

  it('does not reactivate a cancelled editor after refresh or removal of the new editor', async () => {
    const firstChange = vi.fn()
    const secondChange = vi.fn()
    const editors = (showSecond: boolean) => (
      <>
        <ShortcutKeycapInput
          label="First shortcuts"
          bindings={[{ primary: 'K', modifiers: ['Ctrl'] }]}
          otherBindings={[]}
          onChange={firstChange}
        />
        {showSecond && (
          <ShortcutKeycapInput
            label="Second shortcuts"
            bindings={[{ primary: 'J', modifiers: ['Ctrl'] }]}
            otherBindings={[]}
            onChange={secondChange}
          />
        )}
      </>
    )
    const view = render(editors(true))
    fireEvent.click(screen.getByRole('button', { name: 'shortcuts.change: Ctrl K' }))
    await act(async () => {})
    fireEvent.click(screen.getByRole('button', { name: 'shortcuts.change: Ctrl J' }))
    await act(async () => {})
    view.rerender(editors(true))
    expect(document.querySelectorAll('[data-editing]')).toHaveLength(1)
    view.rerender(editors(false))
    await act(async () => {})
    expect(document.querySelector('[data-editing]')).toBeNull()
    fireEvent.keyDown(window, { key: 'm', code: 'KeyM', ctrlKey: true })
    fireEvent.keyUp(window, { key: 'm', code: 'KeyM' })
    fireEvent.keyUp(window, { key: 'Control', code: 'ControlLeft' })
    expect(firstChange).not.toHaveBeenCalled()
    expect(secondChange).not.toHaveBeenCalled()
  })

  it('clears a rejected chord error before showing a valid retry save failure', async () => {
    const save = vi.fn()
    const view = render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    act(() => fnCapture.edge?.(true))
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK' })
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
    act(() => fnCapture.edge?.(false))
    expect(screen.getByRole('alert')).toHaveTextContent('shortcuts.errorNativeCombination')
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true })
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
    expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'K', modifiers: ['Ctrl'] })
    expect(screen.queryByRole('alert')).toBeNull()
    view.rerender(
      <ShortcutRecorderBox
        binding={null}
        otherBindings={[]}
        onSave={save}
        onCancel={vi.fn()}
        saveError="Retry save failed"
      />,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Retry save failed')
  })

  it.each([
    { keys: ['A', 'C'], expected: 'shortcuts.errorNativeCombination' },
    { keys: ['A', 'B'], expected: 'shortcuts.errorConsecutiveLetters' },
    { keys: ['A', 'C', 'E'], expected: 'shortcuts.errorTooMany' },
  ])('explains the rejected multi-key chord $keys', async ({ keys, expected }) => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    for (const key of keys) {
      fireEvent.keyDown(window, { key, code: `Key${key}`, ctrlKey: true })
    }
    for (const key of keys) {
      fireEvent.keyUp(window, { key, code: `Key${key}`, ctrlKey: true })
    }
    fireEvent.keyUp(window, { key: 'Control' })
    expect(save).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent(expected)
  })

  it.each([
    { key: 'ArrowUp', primary: 'Up' },
    { key: 'ArrowDown', primary: 'Down' },
    { key: 'ArrowLeft', primary: 'Left' },
    { key: 'ArrowRight', primary: 'Right' },
    { key: 'Home', primary: 'Home' },
    { key: 'End', primary: 'End' },
    { key: 'PageUp', primary: 'PageUp' },
    { key: 'PageDown', primary: 'PageDown' },
    { key: 'Insert', primary: 'Insert' },
    { key: 'Delete', primary: 'Delete' },
    { key: 'Backspace', primary: 'Backspace' },
  ])(
    'records the existing native navigation key $key with a modifier',
    async ({ key, primary }) => {
      const save = vi.fn()
      render(
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
      )
      await act(async () => {})
      fireEvent.keyDown(window, { key, code: key, ctrlKey: true })
      fireEvent.keyUp(window, { key, code: key })
      expect(save).toHaveBeenCalledExactlyOnceWith({ primary, modifiers: ['Ctrl'] })
    },
  )

  it('associates a save error with its editor and announces it', async () => {
    const view = render(
      <ShortcutRecorderBox
        binding={{ primary: 'K', modifiers: ['Ctrl'] }}
        otherBindings={[]}
        onSave={vi.fn()}
        onCancel={vi.fn()}
        saveError="Save failed"
      />,
    )
    await act(async () => {})
    const editor = screen.getByRole('button', { name: 'shortcuts.change: Ctrl K' })
    const message = screen.getByRole('alert')
    expect(message).toHaveTextContent('Save failed')
    expect(editor).toHaveAttribute('aria-describedby', message.id)
    view.rerender(
      <ShortcutRecorderBox
        binding={{ primary: 'K', modifiers: ['Ctrl'] }}
        otherBindings={[]}
        onSave={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    expect(editor).not.toHaveAttribute('aria-describedby')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('uses the existing keyboard-only recording prompt', async () => {
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={vi.fn()} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    expect(screen.getByText('settings.pressKeyCombination')).toBeInTheDocument()
    expect(screen.getByRole('button')).toHaveAccessibleName('settings.pressKeyCombination')
  })

  it.each([
    { platform: 'Win32', value: 'Fn' },
    { platform: 'MacIntel', value: 'RightAlt' },
    { platform: 'Linux x86_64', value: 'RightAlt' },
  ])('rejects native $value on $platform before saving', async ({ platform, value }) => {
    vi.spyOn(navigator, 'platform', 'get').mockReturnValue(platform)
    const save = vi.fn()
    render(
      <ShortcutRecorderBox
        binding={null}
        otherBindings={[]}
        presets={[{ value, label: 'Native test preset' }]}
        onSave={save}
        onCancel={vi.fn()}
      />,
    )
    await act(async () => {})
    fireEvent.click(screen.getByRole('button', { name: 'Native test preset' }))
    expect(save).not.toHaveBeenCalled()
    expect(screen.getByText('shortcuts.errorPlatform')).toBeInTheDocument()
  })

  it.each([
    { key: '?', releasedKey: '/', code: 'Slash', primary: '/' },
    { key: 'Process', releasedKey: 'Process', code: 'Period', primary: '.' },
    { key: ':', releasedKey: ';', code: 'Semicolon', primary: ';' },
    { key: '|', releasedKey: '\\', code: 'Backslash', primary: '\\' },
    { key: '"', releasedKey: "'", code: 'Quote', primary: "'" },
    { key: '~', releasedKey: '`', code: 'Backquote', primary: '`' },
  ])(
    'records physical $code despite a changed layout character',
    async ({ key, releasedKey, code, primary }) => {
      const save = vi.fn()
      render(
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
      )
      await act(async () => {})
      fireEvent.keyDown(window, {
        key,
        code,
        ctrlKey: true,
        shiftKey: true,
        isComposing: key === 'Process',
      })
      fireEvent.keyUp(window, { key: releasedKey, code, ctrlKey: true })
      expect(save).toHaveBeenCalledExactlyOnceWith({ primary, modifiers: ['Ctrl', 'Shift'] })
      fireEvent.keyUp(window, { key: 'Shift', ctrlKey: true })
      fireEvent.keyUp(window, { key: 'Control' })
    },
  )

  it('does not deliver Fn edges to a disabled non-macOS collector', async () => {
    vi.spyOn(navigator, 'platform', 'get').mockReturnValue('Win32')
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    act(() => {
      fnCapture.edge?.(true)
      fnCapture.edge?.(false)
    })
    expect(save).not.toHaveBeenCalled()
    expect([...fnCapture.listeners.values()]).toEqual([false])
  })

  it('preserves extra bindings when retrying an unsupported Fn chord in settings', async () => {
    const platform = vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel')
    const change = vi.fn()
    const original = { primary: 'Fn', modifiers: [] }
    const extra = { primary: '9', modifiers: ['Ctrl', 'Alt'] }
    const editor = render(
      <ShortcutKeycapInput
        label="Dictation shortcuts"
        bindings={[original, extra]}
        otherBindings={[]}
        onChange={change}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'shortcuts.change: Fn' }))
    await act(async () => {})
    act(() => fnCapture.edge?.(true))
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK' })
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
    act(() => fnCapture.edge?.(false))
    expect(change).not.toHaveBeenCalled()
    expect(screen.getByText('shortcuts.errorNativeCombination')).toBeInTheDocument()
    expect(document.querySelector('[data-editing]')).not.toBeNull()
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true })
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
    expect(change).toHaveBeenCalledExactlyOnceWith(
      [{ primary: 'K', modifiers: ['Ctrl'] }, extra],
      true,
    )
    fireEvent.keyUp(window, { key: 'Control' })
    editor.unmount()
    platform.mockRestore()
  })

  it('shows existing error feedback when the native Fn collector cannot start', async () => {
    const platform = vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel')
    fnCapture.status = 'error'
    const save = vi.fn()
    try {
      render(
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
      )
      await act(async () => {})
      expect(screen.getByText('settings.hotkeyRegistrationFailed')).toBeInTheDocument()
      fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true })
      fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
      expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'K', modifiers: ['Ctrl'] })
      fireEvent.keyUp(window, { key: 'Control' })
    } finally {
      cleanup()
      platform.mockRestore()
    }
  })

  it.each(['fn', 'shift'])(
    'submits Fn plus left Shift once when %s releases first',
    async (first) => {
      const save = vi.fn()
      render(
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
      )
      await act(async () => {})
      act(() => fnCapture.edge?.(true))
      fireEvent.keyDown(window, { key: 'Shift', code: 'ShiftLeft', shiftKey: true })
      if (first === 'fn') {
        act(() => fnCapture.edge?.(false))
        fireEvent.keyUp(window, { key: 'Shift', code: 'ShiftLeft' })
      } else {
        fireEvent.keyUp(window, { key: 'Shift', code: 'ShiftLeft' })
        act(() => fnCapture.edge?.(false))
      }
      expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'LeftShift', modifiers: ['Fn'] })
    },
  )

  it('does not turn a right Shift retry into a left Shift binding after a conflict', async () => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox
        binding={null}
        otherBindings={[{ primary: 'LeftShift', modifiers: ['Fn'] }]}
        onSave={save}
        onCancel={vi.fn()}
      />,
    )
    await act(async () => {})
    act(() => fnCapture.edge?.(true))
    fireEvent.keyDown(window, { key: 'Shift', code: 'ShiftLeft', shiftKey: true })
    fireEvent.keyUp(window, { key: 'Shift', code: 'ShiftLeft' })
    act(() => fnCapture.edge?.(false))
    expect(screen.getByText('shortcuts.errorInUse')).toBeInTheDocument()
    act(() => fnCapture.edge?.(true))
    fireEvent.keyDown(window, { key: 'Shift', code: 'ShiftRight', shiftKey: true })
    act(() => fnCapture.edge?.(false))
    fireEvent.keyUp(window, { key: 'Shift', code: 'ShiftRight' })
    expect(save).not.toHaveBeenCalled()
    expect(screen.getByText('shortcuts.errorNativeCombination')).toBeInTheDocument()
  })

  it('retains the physical Shift side when a repeated event has no code', async () => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    act(() => fnCapture.edge?.(true))
    fireEvent.keyDown(window, { key: 'Shift', code: 'ShiftLeft', shiftKey: true })
    fireEvent.keyDown(window, { key: 'Shift', shiftKey: true, repeat: true })
    fireEvent.keyUp(window, { key: 'Shift', code: 'ShiftLeft' })
    act(() => fnCapture.edge?.(false))
    expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'LeftShift', modifiers: ['Fn'] })
  })

  it('keeps right Alt as the existing generic modifier when recording a letter chord', async () => {
    const platform = vi.spyOn(navigator, 'platform', 'get').mockReturnValue('Win32')
    const save = vi.fn()
    try {
      render(
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
      )
      await act(async () => {})
      fireEvent.keyDown(window, { key: 'Alt', code: 'AltRight', altKey: true })
      fireEvent.keyDown(window, { key: 'k', code: 'KeyK', altKey: true })
      fireEvent.keyUp(window, { key: 'k', code: 'KeyK', altKey: true })
      fireEvent.keyUp(window, { key: 'Alt', code: 'AltRight' })
      expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'K', modifiers: ['Alt'] })
    } finally {
      cleanup()
      platform.mockRestore()
    }
  })

  it.each([
    { key: ' ', code: 'Space', primary: 'Space', shiftKey: false },
    { key: 'Shift', code: 'ShiftLeft', primary: 'LeftShift', shiftKey: true },
    { key: 'Shift', code: 'ShiftRight', primary: 'RightShift', shiftKey: true },
    { key: ' ', code: 'Space', primary: 'Space', shiftKey: false, altFirst: true },
    { key: 'Shift', code: 'ShiftLeft', primary: 'LeftShift', shiftKey: true, altFirst: true },
    { key: 'Shift', code: 'ShiftRight', primary: 'RightShift', shiftKey: true, altFirst: true },
  ])(
    'records supported Windows RightAlt plus $primary with altFirst=$altFirst',
    async ({ key, code, primary, shiftKey, altFirst }) => {
      vi.spyOn(navigator, 'platform', 'get').mockReturnValue('Win32')
      const save = vi.fn()
      render(
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
      )
      await act(async () => {})
      fireEvent.keyDown(window, { key: 'Alt', code: 'AltRight', altKey: true })
      fireEvent.keyDown(window, { key, code, altKey: true, shiftKey })
      if (altFirst) {
        fireEvent.keyUp(window, { key: 'Alt', code: 'AltRight', shiftKey })
        fireEvent.keyUp(window, { key, code, shiftKey: false })
      } else {
        fireEvent.keyUp(window, { key, code, altKey: true, shiftKey: false })
        fireEvent.keyUp(window, { key: 'Alt', code: 'AltRight' })
      }
      expect(save).toHaveBeenCalledExactlyOnceWith({ primary, modifiers: ['RightAlt'] })
    },
  )

  it('records the supported standalone right Alt key on non-macOS platforms', async () => {
    const platform = vi.spyOn(navigator, 'platform', 'get').mockReturnValue('Win32')
    const save = vi.fn()
    try {
      render(
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
      )
      await act(async () => {})
      fireEvent.keyDown(window, { key: 'Alt', code: 'AltRight', altKey: true })
      fireEvent.keyUp(window, { key: 'Alt', code: 'AltRight' })
      expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'RightAlt', modifiers: [] })
    } finally {
      cleanup()
      platform.mockRestore()
    }
  })

  it('rejects an Fn plus Shift letter chord that native registration cannot support', async () => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    act(() => fnCapture.edge?.(true))
    fireEvent.keyDown(window, { key: 'Shift', code: 'ShiftLeft', shiftKey: true })
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', shiftKey: true })
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK', shiftKey: true })
    expect(save).not.toHaveBeenCalled()
    expect(screen.getByText('shortcuts.errorNativeCombination')).toBeInTheDocument()
    fireEvent.keyUp(window, { key: 'Shift', code: 'ShiftLeft' })
    act(() => fnCapture.edge?.(false))
    expect(save).not.toHaveBeenCalled()
  })

  it.each([
    { key: ' ', code: 'Space', shiftKey: false, primary: 'Space' },
    { key: 'Shift', code: 'ShiftLeft', shiftKey: true, primary: 'LeftShift' },
  ])('records Fn with $primary on the first release', async ({ key, code, shiftKey, primary }) => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    act(() => fnCapture.edge?.(true))
    fireEvent.keyDown(window, { key, code, shiftKey })
    fireEvent.keyUp(window, { key, code, shiftKey: false })
    act(() => fnCapture.edge?.(false))
    expect(save).toHaveBeenCalledExactlyOnceWith({ primary, modifiers: ['Fn'] })
  })

  it('records Fn from native edges without using a preset button', async () => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    act(() => fnCapture.edge?.(true))
    expect(save).not.toHaveBeenCalled()
    act(() => fnCapture.edge?.(false))
    expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'Fn', modifiers: [] })
  })

  it.each([
    { key: 'Control', code: 'ControlLeft', ctrlKey: true },
    { key: 'Shift', code: 'ShiftLeft', shiftKey: true },
    { key: 'Alt', code: 'AltLeft', altKey: true },
    { key: 'Meta', code: 'MetaLeft', metaKey: true },
  ])('explains unsupported standalone $key without saving it', async (event) => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox
        binding={{ primary: 'Fn', modifiers: [] }}
        otherBindings={[]}
        onSave={save}
        onCancel={vi.fn()}
      />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, event)
    fireEvent.keyUp(window, { key: event.key, code: event.code })
    expect(save).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('shortcuts.errorNativeCombination')
  })

  it.each([
    { platform: 'MacIntel', count: 2, expected: 'shortcuts.errorNativeCombination' },
    { platform: 'MacIntel', count: 4, expected: 'shortcuts.errorTooMany' },
    { platform: 'Win32', count: 2, expected: 'shortcuts.errorNativeCombination' },
    { platform: 'Win32', count: 4, expected: 'shortcuts.errorTooMany' },
  ])(
    'keeps modifier-only error precedence on $platform with $count keys',
    async ({ platform, count, expected }) => {
      vi.spyOn(navigator, 'platform', 'get').mockReturnValue(platform)
      const save = vi.fn()
      render(
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
      )
      await act(async () => {})
      const events = [
        { key: 'Control', code: 'ControlLeft', ctrlKey: true },
        { key: 'Shift', code: 'ShiftLeft', ctrlKey: true, shiftKey: true },
        { key: 'Alt', code: 'AltLeft', ctrlKey: true, shiftKey: true, altKey: true },
        {
          key: 'Meta',
          code: 'MetaLeft',
          ctrlKey: true,
          shiftKey: true,
          altKey: true,
          metaKey: true,
        },
      ].slice(0, count)
      for (const event of events) fireEvent.keyDown(window, event)
      for (const event of [...events].reverse())
        fireEvent.keyUp(window, { key: event.key, code: event.code })
      expect(save).not.toHaveBeenCalled()
      expect(screen.getByRole('alert')).toHaveTextContent(expected)
    },
  )

  it('still records standalone Windows RightAlt through its supported native path', async () => {
    vi.spyOn(navigator, 'platform', 'get').mockReturnValue('Win32')
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'Alt', code: 'AltRight', altKey: true })
    fireEvent.keyUp(window, { key: 'Alt', code: 'AltRight' })
    expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'RightAlt', modifiers: [] })
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('records the same chord when its modifier is pressed after the letter', async () => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK' })
    fireEvent.keyDown(window, { key: 'Control', code: 'ControlLeft', ctrlKey: true })
    expect(screen.getByRole('button').getAttribute('aria-label')).toContain('K')
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK', ctrlKey: true })
    expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'K', modifiers: ['Ctrl'] })
    fireEvent.keyUp(window, { key: 'Control', code: 'ControlLeft' })
  })

  it('validates named keys on release instead of leaving their preview pending', async () => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'Enter', code: 'Enter' })
    fireEvent.keyUp(window, { key: 'Enter', code: 'Enter' })
    expect(save).not.toHaveBeenCalled()
    expect(screen.getByText('shortcuts.errorReserved')).toBeInTheDocument()
  })

  it('discards the pending chord before notifying Escape cancellation', async () => {
    const save = vi.fn()
    const cancel = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={cancel} />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true })
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
    expect(cancel).toHaveBeenCalledOnce()
    expect(save).not.toHaveBeenCalled()
  })

  it('previews a chord until its first key is released', async () => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true, shiftKey: true })
    expect(save).not.toHaveBeenCalled()
    expect(screen.getByRole('button').getAttribute('aria-label')).toContain('K')
    fireEvent.keyUp(window, { key: 'Shift', ctrlKey: true })
    expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'K', modifiers: ['Ctrl', 'Shift'] })
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK', ctrlKey: true })
    fireEvent.keyUp(window, { key: 'Control' })
    expect(save).toHaveBeenCalledOnce()
  })

  it('cancels a preview on window blur without saving', async () => {
    const save = vi.fn()
    const cancel = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={cancel} />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true })
    fireEvent.blur(window)
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
    expect(save).not.toHaveBeenCalled()
    expect(cancel).toHaveBeenCalledOnce()
  })

  it('updates the settings binding from a physical key without dropping extra bindings', async () => {
    const change = vi.fn()
    const extra = { primary: '9', modifiers: ['Ctrl', 'Alt'] }
    render(
      <ShortcutKeycapInput
        label="Dictation"
        bindings={[{ primary: 'J', modifiers: ['Ctrl', 'Shift'] }, extra]}
        otherBindings={[]}
        onChange={change}
      />,
    )
    const keycap = document.querySelector('[role="group"] [role="button"]')
    expect(keycap).not.toBeNull()
    fireEvent.click(keycap!)
    await act(async () => {})
    fireEvent.keyDown(window, {
      key: 'Process',
      code: 'KeyK',
      isComposing: true,
      ctrlKey: true,
      shiftKey: true,
    })
    fireEvent.keyUp(window, { key: 'Process', code: 'KeyK', ctrlKey: true, shiftKey: true })
    expect(change).toHaveBeenCalledWith(
      [{ primary: 'K', modifiers: ['Ctrl', 'Shift'] }, extra],
      true,
    )
    expect(document.querySelector('[data-editing]')).toBeNull()
    fireEvent.keyUp(window, { key: 'Process', code: 'KeyK' })
  })

  it('records the physical letter instead of an Option-modified character', async () => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, {
      key: '˚',
      code: 'KeyK',
      ctrlKey: true,
      altKey: true,
    })
    fireEvent.keyUp(window, { key: '˚', code: 'KeyK', ctrlKey: true, altKey: true })
    expect(save).toHaveBeenCalledWith({ primary: 'K', modifiers: ['Ctrl', 'Option'] })
    fireEvent.keyUp(window, { key: '˚', code: 'KeyK' })
  })

  it('records the physical shortcut key when an input method consumes the character', async () => {
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, {
      key: 'Process',
      code: 'KeyK',
      isComposing: true,
      ctrlKey: true,
      shiftKey: true,
    })
    fireEvent.keyUp(window, { key: 'Process', code: 'KeyK', ctrlKey: true, shiftKey: true })
    expect(save).toHaveBeenCalledWith({ primary: 'K', modifiers: ['Ctrl', 'Shift'] })
    fireEvent.keyUp(window, { key: 'Process', code: 'KeyK' })
  })

  it('accepts a key immediately after readiness is committed to the DOM', async () => {
    const save = vi.fn()
    function Harness() {
      useLayoutEffect(() => {
        const observer = new MutationObserver(() => {
          if (document.querySelector('[data-capture-ready="true"]')) {
            observer.disconnect()
            window.dispatchEvent(
              new KeyboardEvent('keydown', {
                key: 'k',
                ctrlKey: true,
                shiftKey: true,
                bubbles: true,
                cancelable: true,
              }),
            )
            window.dispatchEvent(
              new KeyboardEvent('keyup', {
                key: 'k',
                ctrlKey: true,
                shiftKey: true,
                bubbles: true,
                cancelable: true,
              }),
            )
          }
        })
        observer.observe(document.body, { attributes: true, subtree: true })
        return () => observer.disconnect()
      }, [])
      return (
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />
      )
    }
    render(<Harness />)
    await act(async () => {})
    expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull()
    expect(save).toHaveBeenCalledWith({ primary: 'K', modifiers: ['Ctrl', 'Shift'] })
  })

  it('does not submit keys or presets until native pause succeeds', async () => {
    let finishPause: () => void = () => {}
    vi.mocked(pauseHotkey).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishPause = resolve
      }),
    )
    const save = vi.fn()
    render(
      <ShortcutRecorderBox
        binding={null}
        otherBindings={[]}
        presets={[{ value: 'Fn', label: 'Fn preset' }]}
        onSave={save}
        onCancel={vi.fn()}
      />,
    )
    await act(async () => {})
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
    fireEvent.click(screen.getByRole('button', { name: 'Fn preset' }))
    expect(save).not.toHaveBeenCalled()
    await act(async () => finishPause())
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
    fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
    expect(save).toHaveBeenCalledWith({ primary: 'K', modifiers: ['Ctrl', 'Shift'] })
  })

  it('keeps failed capture non-submitting while still allowing cancellation', async () => {
    vi.mocked(pauseHotkey).mockRejectedValueOnce(new Error('Pause failed'))
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    const save = vi.fn()
    const cancel = vi.fn()
    try {
      render(
        <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={cancel} />,
      )
      await act(async () => {})
      fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
      expect(save).not.toHaveBeenCalled()
      expect(screen.getByText('settings.hotkeyRegistrationFailed')).toBeInTheDocument()
      fireEvent.keyDown(window, { key: 'Escape' })
      expect(cancel).toHaveBeenCalledTimes(1)
    } finally {
      warning.mockRestore()
    }
  })
})
