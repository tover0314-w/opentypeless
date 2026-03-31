import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { History } from './index'
import * as appStore from '../../stores/appStore'

// Mock the app store
const mockHistory = [
  {
    id: 1,
    created_at: '2026-03-31T10:30:00Z',
    app_name: 'VS Code',
    app_type: 'code',
    raw_text: '嗯那个就是说我们需要清理脚本碎片',
    polished_text: '我们需要清理脚本碎片',
    language: 'zh',
    duration_ms: 5000,
  },
  {
    id: 2,
    created_at: '2026-03-31T09:15:00Z',
    app_name: 'Chrome',
    app_type: 'general',
    raw_text: 'I need you to give me the plan',
    polished_text: 'I need you to give me the plan.',
    language: 'en',
    duration_ms: 3000,
  },
]

// Mock clipboard
const mockWriteText = vi.fn(() => Promise.resolve())
Object.assign(navigator, {
  clipboard: {
    writeText: mockWriteText,
  },
})

// Mock i18n - return key as-is (English)
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key, // Just return the key (English)
  }),
}))

// Mock app store
vi.mock('../../stores/appStore', () => ({
  useAppStore: vi.fn(),
}))

// Mock tauri
vi.mock('../../lib/tauri', () => ({
  clearHistory: vi.fn(),
}))

// Mock toast
vi.mock('../Toast', () => ({
  toast: {
    error: vi.fn(),
  },
}))

describe('History Component', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanup()
  })

  describe('Step 1: Raw Text Display (Expand/Collapse)', () => {
    it('should display polished_text by default and not show raw_text', () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: mockHistory,
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      // Should show polished text
      expect(screen.getByText('我们需要清理脚本碎片')).toBeInTheDocument()
      expect(screen.getByText('I need you to give me the plan.')).toBeInTheDocument()

      // Should NOT show raw text initially
      expect(screen.queryByText('嗯那个就是说我们需要清理脚本碎片')).not.toBeInTheDocument()
    })

    it('should display raw_text when clicking "View Original" button', async () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: mockHistory,
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      // Find and click "View Original" button for first entry
      const viewRawButton = screen.getAllByText('View Original')[0]
      fireEvent.click(viewRawButton)

      // Should now show raw text
      await waitFor(() => {
        expect(screen.getByText('嗯那个就是说我们需要清理脚本碎片')).toBeInTheDocument()
      })
    })

    it('should hide raw_text when clicking "Hide Original" button', async () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: mockHistory,
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      // First expand
      const viewRawButton = screen.getAllByText('View Original')[0]
      fireEvent.click(viewRawButton)

      await waitFor(() => {
        expect(screen.getByText('嗯那个就是说我们需要清理脚本碎片')).toBeInTheDocument()
      })

      // Then collapse
      const hideRawButton = screen.getAllByText('Hide Original')[0]
      fireEvent.click(hideRawButton)

      // Raw text should be hidden
      await waitFor(() => {
        expect(screen.queryByText('嗯那个就是说我们需要清理脚本碎片')).not.toBeInTheDocument()
      })
    })

    it('should expand/collapse independently for each history entry', async () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: mockHistory,
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      // Expand first entry only
      const viewRawButtons = screen.getAllByText('View Original')
      fireEvent.click(viewRawButtons[0])

      // First entry should show raw text
      await waitFor(() => {
        expect(screen.getByText('嗯那个就是说我们需要清理脚本碎片')).toBeInTheDocument()
      })

      // Second entry should NOT show raw text
      expect(screen.queryByText('I need you to give me the plan')).not.toBeInTheDocument()
    })
  })

  describe('Step 2: Enhanced Copy Functionality', () => {
    it('should copy polished_text when clicking copy button', async () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: mockHistory,
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      // Find copy button for polished text and click
      const copyButton = screen.getByLabelText('Copy polished: 我们需要清理脚本碎片')
      fireEvent.click(copyButton)

      // Should copy polished text
      await waitFor(() => {
        expect(mockWriteText).toHaveBeenCalledWith('我们需要清理脚本碎片')
      })
    })

    it('should copy raw_text when clicking copy button in expanded view', async () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: mockHistory,
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      // First expand to show raw text
      const viewRawButton = screen.getAllByText('View Original')[0]
      fireEvent.click(viewRawButton)

      // Wait for raw text to appear
      await waitFor(() => {
        expect(screen.getByText('嗯那个就是说我们需要清理脚本碎片')).toBeInTheDocument()
      })

      // Find the Copy original button by its aria-label
      const copyOriginalButton = screen.getByLabelText(
        'Copy original: 嗯那个就是说我们需要清理脚本碎片',
      )
      fireEvent.click(copyOriginalButton)

      // Should copy raw text
      await waitFor(() => {
        expect(mockWriteText).toHaveBeenCalledWith('嗯那个就是说我们需要清理脚本碎片')
      })
    })
  })

  describe('Step 3: Search Functionality (Regression Test)', () => {
    it('should search in both raw_text and polished_text', () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: mockHistory,
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      // Search for text that only exists in raw_text
      const searchInputs = screen.getAllByPlaceholderText('history.searchPlaceholder')
      fireEvent.change(searchInputs[0], { target: { value: '嗯那个就是说' } })

      // Should show the entry with matching raw_text
      expect(screen.getByText('我们需要清理脚本碎片')).toBeInTheDocument()

      // Search for text in polished_text
      fireEvent.change(searchInputs[0], { target: { value: 'plan' } })

      // Should show the entry with matching polished_text
      expect(screen.getByText('I need you to give me the plan.')).toBeInTheDocument()
    })

    it('should filter results when searching', () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: mockHistory,
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      // Both entries should be visible initially
      expect(screen.getByText('我们需要清理脚本碎片')).toBeInTheDocument()
      expect(screen.getByText('I need you to give me the plan.')).toBeInTheDocument()

      // Search for only Chinese entry
      const searchInputs = screen.getAllByPlaceholderText('history.searchPlaceholder')
      fireEvent.change(searchInputs[0], { target: { value: '清理脚本' } })

      // Should only show Chinese entry
      expect(screen.getByText('我们需要清理脚本碎片')).toBeInTheDocument()
      expect(screen.queryByText('I need you to give me the plan.')).not.toBeInTheDocument()
    })
  })

  describe('Edge Cases', () => {
    it('should handle empty history gracefully', () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: [],
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      expect(screen.getByText('history.noHistory')).toBeInTheDocument()
    })

    it('should show "No results" when search has no matches', () => {
      vi.mocked(appStore.useAppStore).mockImplementation((selector) => {
        const state = {
          history: mockHistory,
          setHistory: vi.fn(),
        }
        return selector(state as unknown as appStore.AppState)
      })

      render(<History />)

      const searchInputs = screen.getAllByPlaceholderText('history.searchPlaceholder')
      fireEvent.change(searchInputs[0], { target: { value: 'nonexistent text' } })

      expect(screen.getByText('history.noResults')).toBeInTheDocument()
    })
  })
})
