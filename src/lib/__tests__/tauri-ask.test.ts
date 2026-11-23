import { describe, expect, it, vi, beforeEach } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  askAnything,
  dismissAskWindow,
  getCloudBackupHistory,
  showAskWindow,
  startAskDictation,
  startAskFlow,
  stopAskFlow,
  takePendingAskMessage,
  updateAskHotkey,
} from '../tauri'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
}))

describe('Ask Anything Tauri wrappers', () => {
  it('reads backup history through a single bounded native snapshot command', async () => {
    vi.mocked(invoke).mockResolvedValueOnce([])
    await expect(getCloudBackupHistory()).resolves.toEqual([])
    expect(invoke).toHaveBeenCalledWith('get_cloud_backup_history')
  })

  it.each([null, {}, [null], Array.from({ length: 5001 }, () => ({ raw_text: 'Synthetic' }))])(
    'rejects malformed or overflowing backup snapshots before upload: %j',
    async (response) => {
      vi.mocked(invoke).mockResolvedValueOnce(response)
      await expect(getCloudBackupHistory()).rejects.toThrow('Invalid backup history snapshot')
    },
  )
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('invokes the ask_anything command with the trimmed question', async () => {
    vi.mocked(invoke).mockResolvedValueOnce('A short answer.')

    const answer = await askAnything('  What is OpenTypeless?  ')

    expect(answer).toBe('A short answer.')
    expect(invoke).toHaveBeenCalledWith('ask_anything', { question: 'What is OpenTypeless?' })
  })

  it('invokes the ask hotkey update command independently from dictation hotkey', async () => {
    vi.mocked(invoke).mockResolvedValueOnce(undefined)

    await updateAskHotkey('Ctrl+.')

    expect(invoke).toHaveBeenCalledWith('update_ask_hotkey', { hotkey: 'Ctrl+.' })
  })

  it('opens the native Ask floating window', async () => {
    vi.mocked(invoke).mockResolvedValueOnce(undefined)

    await showAskWindow()

    expect(invoke).toHaveBeenCalledWith('show_ask_window')
  })

  it('dismisses Ask through the native focus restoration command', async () => {
    vi.mocked(invoke).mockResolvedValueOnce(undefined)
    await dismissAskWindow()
    expect(invoke).toHaveBeenCalledWith('dismiss_ask_window')
  })

  it('starts the voice-first Ask flow', async () => {
    vi.mocked(invoke).mockResolvedValueOnce(undefined)

    await startAskFlow()

    expect(invoke).toHaveBeenCalledWith('start_ask_flow')
  })

  it('stops the voice-first Ask flow', async () => {
    vi.mocked(invoke).mockResolvedValueOnce(undefined)

    await stopAskFlow()

    expect(invoke).toHaveBeenCalledWith('stop_ask_flow')
  })

  it('reads and clears pending Ask popup messages from Tauri', async () => {
    vi.mocked(invoke).mockResolvedValueOnce({
      kind: 'result',
      payload: { question: 'Q', answer: 'A' },
    })

    const pending = await takePendingAskMessage()

    expect(pending).toEqual({
      kind: 'result',
      payload: { question: 'Q', answer: 'A' },
    })
    expect(invoke).toHaveBeenCalledWith('take_pending_ask_message')
  })

  it('starts Ask dictation and returns recording metadata', async () => {
    vi.mocked(invoke).mockResolvedValueOnce({
      usedSelectedText: true,
      selectedTextTruncated: false,
    })

    await expect(startAskDictation()).resolves.toEqual({
      usedSelectedText: true,
      selectedTextTruncated: false,
    })
    expect(invoke).toHaveBeenCalledWith('start_ask_dictation')
  })
})
