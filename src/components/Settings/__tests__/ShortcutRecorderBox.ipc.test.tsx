import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { clearMocks, mockIPC } from '@tauri-apps/api/mocks'
import { emit } from '@tauri-apps/api/event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ShortcutKeycapInput, ShortcutRecorderBox } from '../ShortcutKeycapInput'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

beforeEach(() => {
  vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel')
})

afterEach(async () => {
  fireEvent.keyUp(window, { key: ' ', code: 'Space' })
  fireEvent.keyUp(window, { key: 'Control' })
  await act(async () => cleanup())
  clearMocks()
  vi.restoreAllMocks()
})

describe('shortcut capture through actual hooks and Tauri JS', () => {
  it('revokes the old Fn collector without resuming global shortcuts during editor handoff', async () => {
    const captures: string[] = []
    const stopped: string[] = []
    const commands: string[] = []
    mockIPC(
      (command, payload) => {
        commands.push(command)
        if (command === 'start_shortcut_capture')
          captures.push((payload as { captureId: string }).captureId)
        if (command === 'stop_shortcut_capture')
          stopped.push((payload as { captureId: string }).captureId)
      },
      { shouldMockEvents: true },
    )
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
    await waitFor(() => expect(captures).toHaveLength(1))
    await act(async () => {})
    fireEvent.click(screen.getByRole('button', { name: 'shortcuts.change: Ctrl J' }))
    await waitFor(() => expect(captures).toHaveLength(2))
    await waitFor(() => expect(stopped).toContain(captures[0]))
    await act(async () => {
      await emit('shortcut-capture:fn', { captureId: captures[0], pressed: true })
      await emit('shortcut-capture:fn', { captureId: captures[0], pressed: false })
    })
    expect(firstChange).not.toHaveBeenCalled()
    expect(secondChange).not.toHaveBeenCalled()
    expect(commands.filter((command) => command === 'pause_hotkey')).toHaveLength(1)
    expect(commands).not.toContain('resume_hotkey')
    await act(async () => {
      await emit('shortcut-capture:fn', { captureId: captures[1], pressed: true })
      await emit('shortcut-capture:fn', { captureId: captures[1], pressed: false })
    })
    expect(secondChange).toHaveBeenCalledExactlyOnceWith([{ primary: 'Fn', modifiers: [] }], true)
    expect(firstChange).not.toHaveBeenCalled()
  })

  it('ignores Fn events during startup and after blur even if startup completes late', async () => {
    let finishStartup!: () => void
    let captureId = ''
    const stopped: string[] = []
    mockIPC(
      (command, payload) => {
        if (command === 'start_shortcut_capture') {
          captureId = (payload as { captureId: string }).captureId
          return new Promise<void>((resolve) => {
            finishStartup = resolve
          })
        }
        if (command === 'stop_shortcut_capture') {
          stopped.push((payload as { captureId: string }).captureId)
        }
      },
      { shouldMockEvents: true },
    )
    const save = vi.fn()
    const cancel = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={cancel} />,
    )
    await waitFor(() => expect(finishStartup).toBeTypeOf('function'))
    await act(async () => {
      await emit('shortcut-capture:fn', { captureId, pressed: true })
      await emit('shortcut-capture:fn', { captureId, pressed: false })
    })
    expect(save).not.toHaveBeenCalled()
    fireEvent.blur(window)
    expect(cancel).toHaveBeenCalledOnce()
    await waitFor(() => expect(stopped).toEqual([captureId]))
    await act(async () => finishStartup())
    await waitFor(() => expect(stopped).toEqual([captureId, captureId]))
    await act(async () => {
      await emit('shortcut-capture:fn', { captureId, pressed: true })
      await emit('shortcut-capture:fn', { captureId, pressed: false })
    })
    expect(save).not.toHaveBeenCalled()
  })

  it('routes owned Fn events to the active settings row and preserves other bindings', async () => {
    const captures: string[] = []
    const commands: string[] = []
    mockIPC(
      (command, payload) => {
        commands.push(command)
        if (command === 'start_shortcut_capture') {
          captures.push((payload as { captureId: string }).captureId)
        }
      },
      { shouldMockEvents: true },
    )
    const change = vi.fn()
    const extra = { primary: '9', modifiers: ['Ctrl', 'Option'] }
    render(
      <ShortcutKeycapInput
        label="Dictation shortcuts"
        bindings={[{ primary: 'Fn', modifiers: [] }, extra]}
        otherBindings={[]}
        onChange={change}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'shortcuts.change: Fn' }))
    await waitFor(() => expect(captures).toHaveLength(1))
    await act(async () => {})
    await act(async () => {
      await emit('shortcut-capture:fn', { captureId: crypto.randomUUID(), pressed: true })
      await emit('shortcut-capture:fn', { captureId: captures[0], pressed: true })
    })
    fireEvent.keyDown(window, { key: ' ', code: 'Space' })
    fireEvent.keyUp(window, { key: ' ', code: 'Space' })
    expect(change).toHaveBeenCalledExactlyOnceWith(
      [{ primary: 'Space', modifiers: ['Fn'] }, extra],
      true,
    )
    await act(async () => {
      await emit('shortcut-capture:fn', { captureId: captures[0], pressed: false })
    })
    expect(change).toHaveBeenCalledOnce()
    expect(commands).toContain('pause_hotkey')
    await waitFor(() => expect(commands).toContain('stop_shortcut_capture'))
  })

  it('shows startup failure while keeping ordinary key recording usable', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    mockIPC(
      (command) => {
        if (command === 'start_shortcut_capture') throw new Error('Synthetic native failure')
      },
      { shouldMockEvents: true },
    )
    const save = vi.fn()
    render(
      <ShortcutRecorderBox binding={null} otherBindings={[]} onSave={save} onCancel={vi.fn()} />,
    )
    await waitFor(() =>
      expect(screen.getByText('settings.hotkeyRegistrationFailed')).toBeInTheDocument(),
    )
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true })
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK' })
    fireEvent.keyUp(window, { key: 'Control' })
    expect(save).toHaveBeenCalledExactlyOnceWith({ primary: 'K', modifiers: ['Ctrl'] })
    expect(log).toHaveBeenCalledWith('Failed to start local shortcut capture')
  })
})
