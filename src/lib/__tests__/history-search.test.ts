import { beforeEach, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { searchHistoryPage } from '../tauri'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn().mockResolvedValue([]) }))
beforeEach(() => vi.clearAllMocks())

it('passes local search and keyset pagination to the native command', async () => {
  await searchHistoryPage('中文 100%_', 'ask', 51, 30)
  expect(invoke).toHaveBeenCalledWith('search_history_page', {
    query: '中文 100%_',
    mode: 'ask',
    limit: 51,
    beforeId: 30,
  })
})
