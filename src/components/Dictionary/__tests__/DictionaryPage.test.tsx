import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../../i18n'
import * as tauri from '../../../lib/tauri'
import { useAppStore, type DictionaryEntry } from '../../../stores/appStore'
import { toast } from '../../toast-service'
import { DictionaryPage } from '../index'

vi.mock('../../../lib/tauri')
vi.mock('../../toast-service', () => {
  const toast = Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() })
  return { toast }
})

const words: DictionaryEntry[] = [
  { id: 1, word: 'OpenTypeless', pronunciation: 'open typeless', auto: false },
  { id: 2, word: 'Kubernetes', pronunciation: null, auto: true },
  { id: 3, word: 'MeloLab', pronunciation: 'mee-lo', auto: false },
]

function report(acceptedWords: string[], extra: Partial<tauri.DictionaryImportReport> = {}) {
  return {
    accepted: acceptedWords.length,
    skippedDuplicates: 0,
    skippedInvalid: 0,
    errors: [],
    acceptedWords,
    ...extra,
  }
}

function csvFile(name: string, content: string, size?: number) {
  const file = new File([content], name, { type: 'text/csv' })
  const bytes = new TextEncoder().encode(content)
  Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(bytes.buffer) })
  if (size !== undefined) Object.defineProperty(file, 'size', { value: size })
  return { file, bytes: Array.from(bytes) }
}

function chip(word: string) {
  return screen.getByText(word).closest('li') as HTMLElement
}

describe('DictionaryPage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    vi.clearAllMocks()
    useAppStore.setState({ dictionary: words, correctionRules: [] })
    vi.mocked(tauri.getDictionary).mockResolvedValue(words)
    vi.mocked(tauri.getCorrectionRules).mockResolvedValue([])
    vi.mocked(tauri.addDictionaryEntry).mockResolvedValue(undefined)
    vi.mocked(tauri.updateDictionaryEntry).mockResolvedValue(undefined)
    vi.mocked(tauri.removeDictionaryEntry).mockResolvedValue(undefined)
    vi.mocked(tauri.removeDictionaryEntries).mockResolvedValue(2)
    vi.mocked(tauri.addCorrectionRule).mockResolvedValue(undefined)
    vi.mocked(tauri.updateCorrectionRule).mockResolvedValue(undefined)
    vi.mocked(tauri.removeCorrectionRule).mockResolvedValue(undefined)
    vi.mocked(tauri.setCorrectionRuleEnabled).mockResolvedValue(undefined)
    vi.mocked(tauri.exportDictionaryJson).mockResolvedValue('{}')
    vi.mocked(tauri.exportDictionaryCsv).mockResolvedValue('type,word\n')
  })

  afterEach(() => {
    cleanup()
  })

  it('filters by source tab and shows the matching empty description', () => {
    render(<DictionaryPage />)

    expect(screen.getAllByTestId('dictionary-word')).toHaveLength(3)
    fireEvent.click(screen.getByRole('tab', { name: 'Auto-added' }))
    expect(screen.getAllByTestId('dictionary-word')).toHaveLength(1)
    expect(screen.getByText('Kubernetes')).toBeInTheDocument()
    expect(within(chip('Kubernetes')).getByRole('tooltip')).toHaveTextContent(
      'Auto-added by OpenTypeless',
    )

    fireEvent.click(screen.getByRole('tab', { name: 'Manually-added' }))
    expect(screen.getAllByTestId('dictionary-word')).toHaveLength(2)
    expect(screen.queryByText('Kubernetes')).toBeNull()

    act(() => useAppStore.setState({ dictionary: [] }))
    expect(screen.getByRole('heading', { name: 'No words yet' })).toBeInTheDocument()
    expect(
      screen.getByText(
        'You can manually add your unique names and words to your personal dictionary.',
      ),
    ).toBeInTheDocument()
  })

  it('expands search, filters words and pronunciations, and shows only no-results copy', () => {
    render(<DictionaryPage />)

    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    const input = screen.getByRole('searchbox', { name: 'Search' })
    expect(input).toHaveAttribute('maxLength', '60')
    fireEvent.change(input, { target: { value: 'mee-lo' } })
    expect(screen.getAllByTestId('dictionary-word')).toHaveLength(1)
    expect(screen.getByText('MeloLab')).toBeInTheDocument()

    fireEvent.change(input, { target: { value: 'zzz' } })
    expect(screen.getByText('No words found matching your search.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'No words yet' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Clear search' }))
    expect(screen.getAllByTestId('dictionary-word')).toHaveLength(3)
  })

  it('preserves the search query when Escape belongs to input composition', () => {
    render(<DictionaryPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    const input = screen.getByRole('searchbox', { name: 'Search' })
    fireEvent.change(input, { target: { value: 'Melo' } })
    fireEvent.keyDown(input, { key: 'Escape', isComposing: true })
    expect(input).toHaveValue('Melo')
    expect(screen.getAllByTestId('dictionary-word')).toHaveLength(1)
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(input).toHaveValue('')
    expect(screen.getAllByTestId('dictionary-word')).toHaveLength(3)
  })

  it('adds a word from the header modal with validation and duplicate feedback', async () => {
    render(<DictionaryPage />)

    fireEvent.click(screen.getByRole('button', { name: 'New word' }))
    const dialog = screen.getByRole('dialog', { name: 'Add to dictionary' })
    const input = within(dialog).getByPlaceholderText('Add a new word')
    expect(input).toHaveFocus()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Add word' }))
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Please enter a word'))
    fireEvent.change(input, { target: { value: 'x'.repeat(101) } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('The word exceeds the length limit.'),
    )
    expect(tauri.addDictionaryEntry).not.toHaveBeenCalled()

    vi.mocked(tauri.addDictionaryEntry).mockRejectedValueOnce('dictionary_duplicate')
    fireEvent.change(input, { target: { value: 'MeloLab' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('This word is already in your dictionary.'),
    )
    expect(screen.getByRole('dialog', { name: 'Add to dictionary' })).toBeInTheDocument()

    fireEvent.change(input, { target: { value: ' TalkMore ' } })
    fireEvent.change(within(dialog).getByPlaceholderText('Pronunciation (optional)'), {
      target: { value: 'talk more' },
    })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => {
      expect(tauri.addDictionaryEntry).toHaveBeenLastCalledWith('TalkMore', 'talk more')
      expect(toast.success).toHaveBeenCalledWith('“TalkMore” added')
    })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('does not submit on Enter while an IME composition is active', () => {
    render(<DictionaryPage />)
    fireEvent.click(screen.getByRole('button', { name: 'New word' }))
    const input = screen.getByPlaceholderText('Add a new word')
    fireEvent.change(input, { target: { value: '词' } })
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    expect(tauri.addDictionaryEntry).not.toHaveBeenCalled()
  })

  it('edits and deletes a word from its more menu', async () => {
    render(<DictionaryPage />)

    fireEvent.click(screen.getByRole('button', { name: 'Edit and delete OpenTypeless' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Edit' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit word' })
    const input = within(dialog).getByPlaceholderText('Add a new word')
    expect(input).toHaveValue('OpenTypeless')
    expect(within(dialog).queryByText('Import CSV')).toBeNull()
    fireEvent.change(input, { target: { value: 'OpenTypeless Pro' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(tauri.updateDictionaryEntry).toHaveBeenCalledWith(
        1,
        'OpenTypeless Pro',
        'open typeless',
      )
      expect(toast.success).toHaveBeenCalledWith('Updated “OpenTypeless Pro”')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Edit and delete MeloLab' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }))
    const confirm = screen.getByRole('alertdialog', { name: 'Delete word?' })
    expect(confirm).toHaveTextContent('“MeloLab” will be deleted forever.')
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }))
    await waitFor(() => {
      expect(tauri.removeDictionaryEntry).toHaveBeenCalledWith(3)
      expect(toast.success).toHaveBeenCalledWith('“MeloLab” deleted')
    })
  })

  it('multi-selects words, swaps the toolbar, and batch deletes', async () => {
    render(<DictionaryPage />)

    fireEvent.click(screen.getByRole('button', { name: 'Select word OpenTypeless' }))
    expect(screen.queryByRole('tablist')).toBeNull()
    expect(screen.getByText('1 word selected')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Clear selection' })).toHaveAttribute(
      'aria-checked',
      'mixed',
    )
    expect(screen.queryByRole('button', { name: /Edit and delete/ })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Select word MeloLab' }))
    expect(screen.getByText('2 words selected')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Select all' }))
    expect(screen.getByText('3 words selected')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Clear selection' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Select word Kubernetes' }))

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    const confirm = screen.getByRole('alertdialog', { name: 'Delete 2 words?' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }))
    await waitFor(() => {
      expect(tauri.removeDictionaryEntries).toHaveBeenCalledWith([1, 3])
      expect(toast.success).toHaveBeenCalledWith('2 words deleted')
    })
    expect(screen.getByRole('tablist')).toBeInTheDocument()
  })

  it('imports a CSV from the add modal through a preview grid', async () => {
    const { file, bytes } = csvFile('terms.csv', 'Typeless\nREA\n')
    vi.mocked(tauri.previewDictionaryImport).mockResolvedValue(
      report(['Typeless', 'REA'], { skippedDuplicates: 1 }),
    )
    vi.mocked(tauri.commitDictionaryImport).mockResolvedValue(report(['Typeless', 'REA']))
    render(<DictionaryPage />)

    fireEvent.click(screen.getByRole('button', { name: 'New word' }))
    expect(screen.getByRole('button', { name: 'Import CSV' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Choose dictionary import file'), {
      target: { files: [file] },
    })

    expect(await screen.findByText('2 valid words found in terms.csv')).toBeInTheDocument()
    const dialog = screen.getByRole('dialog', { name: 'Add to dictionary' })
    expect(within(dialog).getByText('REA')).toBeInTheDocument()
    expect(within(dialog).getByText(/Skipped 1 duplicates/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add words' }))

    await waitFor(() => {
      expect(tauri.previewDictionaryImport).toHaveBeenCalledWith(bytes, 'csv')
      expect(tauri.commitDictionaryImport).toHaveBeenCalledWith(bytes, 'csv')
      expect(toast.success).toHaveBeenCalledWith('2 words added')
    })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('does not reopen an import preview after its picker dialog is closed', async () => {
    let finish: (value: tauri.DictionaryImportReport) => void = () => {}
    vi.mocked(tauri.previewDictionaryImport).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const { file } = csvFile('late.csv', 'REA\n')
    render(<DictionaryPage />)
    fireEvent.click(screen.getByRole('button', { name: 'New word' }))
    fireEvent.change(screen.getByLabelText('Choose dictionary import file'), {
      target: { files: [file] },
    })
    await waitFor(() => expect(tauri.previewDictionaryImport).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await act(async () => finish(report(['REA'])))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(tauri.commitDictionaryImport).not.toHaveBeenCalled()
  })

  it('shows import failures inline with a retry picker', async () => {
    const big = csvFile('huge.csv', 'a', 5 * 1024 * 1024 + 1)
    render(<DictionaryPage />)

    fireEvent.click(screen.getByRole('button', { name: 'New word' }))
    fireEvent.change(screen.getByLabelText('Choose dictionary import file'), {
      target: { files: [big.file] },
    })
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'File is too large. Maximum size is 5 MB.',
    )
    expect(tauri.previewDictionaryImport).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Add words' })).toBeDisabled()

    vi.mocked(tauri.previewDictionaryImport).mockRejectedValueOnce(
      'dictionary_import_invalid_structure',
    )
    const bad = csvFile('bad.csv', 'a,b\n')
    fireEvent.change(screen.getByLabelText('Choose dictionary import file'), {
      target: { files: [bad.file] },
    })
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Use a one-column CSV'))
    expect(screen.getByText('bad.csv')).toBeInTheDocument()
  })

  it('keeps replacement rules in their own tab', async () => {
    const rule = { id: 9, pattern: 'open type less', replacement: 'OpenTypeless', enabled: true }
    useAppStore.setState({ correctionRules: [rule] })
    vi.mocked(tauri.getCorrectionRules).mockResolvedValue([rule])
    render(<DictionaryPage />)

    fireEvent.click(screen.getByRole('tab', { name: 'Replacements' }))
    expect(screen.queryAllByTestId('dictionary-word')).toHaveLength(0)
    expect(screen.getByText('open type less')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('Wrong phrase'), { target: { value: ' 拓肯 ' } })
    fireEvent.change(screen.getByPlaceholderText('Correct phrase'), {
      target: { value: ' Token ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add correction' }))
    await waitFor(() => expect(tauri.addCorrectionRule).toHaveBeenCalledWith('拓肯', 'Token'))

    fireEvent.click(screen.getByRole('switch', { name: 'Enable correction open type less' }))
    await waitFor(() => expect(tauri.setCorrectionRuleEnabled).toHaveBeenCalledWith(9, false))

    fireEvent.click(screen.getByRole('button', { name: 'Edit correction open type less' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit correction' })
    fireEvent.change(within(dialog).getByLabelText('Correct phrase'), {
      target: { value: 'OpenTypeless!' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(tauri.updateCorrectionRule).toHaveBeenCalledWith(
        9,
        'open type less',
        'OpenTypeless!',
        true,
      ),
    )
  })

  it('exports JSON and CSV from the header menu', async () => {
    const createObjectURL = vi.fn().mockReturnValue('blob:test')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(<DictionaryPage />)

    fireEvent.click(screen.getByRole('button', { name: 'Export dictionary' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Export JSON' }))
    await waitFor(() => expect(tauri.exportDictionaryJson).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: 'Export dictionary' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Export CSV' }))
    await waitFor(() => expect(tauri.exportDictionaryCsv).toHaveBeenCalled())
    expect(click).toHaveBeenCalledTimes(2)

    click.mockRestore()
    vi.unstubAllGlobals()
  })

  it('renders translated plural copy in Chinese', async () => {
    await i18n.changeLanguage('zh')
    render(<DictionaryPage />)
    expect(screen.getByRole('button', { name: '新词' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '自动添加' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '选择词汇 OpenTypeless' }))
    fireEvent.click(screen.getByRole('button', { name: '选择词汇 MeloLab' }))
    expect(screen.getByText('已选择 2 个词')).toBeInTheDocument()
  })
})
