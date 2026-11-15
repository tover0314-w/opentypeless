import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AskAnswerCard } from '../AskAnswerCard'
import { toast } from '../../toast-service'

vi.mock('../../toast-service', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn() }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

beforeEach(() => {
  vi.clearAllMocks()
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('answer card result changes', () => {
  it('keeps long answer content uncompressed inside the scrolling card', () => {
    const answer = Array.from({ length: 24 }, (_, index) => `Paragraph ${index + 1}`).join('\n\n')
    const { container } = render(<AskAnswerCard answer={answer} onClose={vi.fn()} />)
    const body = container.querySelector('.answer-markdown')
    const answerSection = body?.parentElement?.parentElement
    expect(answerSection).toHaveClass('shrink-0')
    expect(answerSection).not.toHaveClass('overflow-hidden')
    expect(answerSection?.parentElement).toHaveClass('overflow-y-auto', 'min-h-0')
    expect(screen.getByText('Paragraph 24')).toBeInTheDocument()
  })

  it('uses the approved project glass material without a large outer shadow', () => {
    render(<AskAnswerCard answer="Answer" onClose={vi.fn()} />)
    const card = screen.getByTestId('ask-floating-note')
    expect(card).toHaveAttribute('data-ask-material', 'glass')
    expect(card).not.toHaveClass('shadow-float')
    expect(card).toHaveClass('ask-result-glass')
  })

  it.each(['success', 'failure'] as const)(
    'ignores a late copy %s after the answer card unmounts',
    async (outcome) => {
      let complete = () => {}
      vi.mocked(navigator.clipboard.writeText).mockReturnValueOnce(
        new Promise<void>((resolve, reject) => {
          complete = outcome === 'success' ? resolve : () => reject(new Error('Clipboard denied'))
        }),
      )
      const { unmount } = render(<AskAnswerCard answer="Answer" onClose={vi.fn()} />)
      fireEvent.click(screen.getByRole('button', { name: 'ask.copyAnswer' }))
      unmount()
      await act(async () => complete())
      expect(toast.error).not.toHaveBeenCalled()
      expect(screen.queryByRole('button', { name: 'ask.copyAnswer' })).toBeNull()
    },
  )

  it.each([
    'file:///private/document',
    'javascript:alert',
    'opentypeless://auth',
    'https://user:secret@example.com/docs',
  ])('does not expose an actionable answer link for %s', (address) => {
    const onOpenLink = vi.fn()
    render(
      <AskAnswerCard
        answer={`[Reference](${address})`}
        onClose={vi.fn()}
        onOpenLink={onOpenLink}
      />,
    )
    expect(screen.queryByRole('link')).toBeNull()
    fireEvent.click(screen.getByText('Reference'))
    expect(onOpenLink).not.toHaveBeenCalled()
  })

  it.each(['https://example.com/docs', 'mailto:person@example.com'])(
    'opens the supported answer link %s only after a user click',
    (address) => {
      const onOpenLink = vi.fn()
      render(
        <AskAnswerCard
          answer={`[Reference](${address})`}
          onClose={vi.fn()}
          onOpenLink={onOpenLink}
        />,
      )
      expect(onOpenLink).not.toHaveBeenCalled()
      fireEvent.click(screen.getByRole('link', { name: 'Reference' }))
      expect(onOpenLink).toHaveBeenCalledTimes(1)
      expect(onOpenLink).toHaveBeenCalledWith(address)
    },
  )

  it('keeps the latest copy success when an older copy fails', async () => {
    let failOlder: (error: Error) => void = () => {}
    vi.mocked(navigator.clipboard.writeText).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        failOlder = reject
      }),
    )
    render(<AskAnswerCard answer="Answer" onClose={vi.fn()} />)
    const button = screen.getByRole('button', { name: 'ask.copyAnswer' })
    fireEvent.click(button)
    await act(async () => fireEvent.click(button))
    expect(button).toHaveAttribute('title', 'ask.copied')
    await act(async () => failOlder(new Error('Old copy failed')))
    expect(button).toHaveAttribute('title', 'ask.copied')
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('reports clipboard failure and allows the user to retry copying', async () => {
    vi.mocked(navigator.clipboard.writeText).mockRejectedValueOnce(new Error('Clipboard denied'))
    render(<AskAnswerCard answer="Answer" onClose={vi.fn()} />)
    const button = screen.getByRole('button', { name: 'ask.copyAnswer' })
    await act(async () => fireEvent.click(button))
    expect(toast.error).toHaveBeenCalledWith('history.failedToCopy')
    expect(button).toHaveAttribute('title', 'ask.copy')
    await act(async () => fireEvent.click(button))
    expect(button).toHaveAttribute('title', 'ask.copied')
  })

  it('does not report a late clipboard failure for a previous answer', async () => {
    let failCopy: (error: Error) => void = () => {}
    vi.mocked(navigator.clipboard.writeText).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        failCopy = reject
      }),
    )
    const { rerender } = render(<AskAnswerCard answer="First" onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'ask.copyAnswer' }))
    rerender(<AskAnswerCard answer="Second" onClose={vi.fn()} />)
    await act(async () => failCopy(new Error('Clipboard denied')))
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('copies the rendered answer rather than the question as rich HTML', async () => {
    const formats = vi.fn()
    const write = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { write, writeText: vi.fn() },
    })
    vi.stubGlobal(
      'ClipboardItem',
      class {
        constructor(payload: Record<string, Blob>) {
          formats(payload)
        }
      },
    )
    render(
      <AskAnswerCard
        prompt="Private question"
        answer="**Clear answer** [Docs](https://example.com/docs) [Unsafe](file:///private/document)"
        onClose={vi.fn()}
      />,
    )
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'ask.copyAnswer' })))
    const payload = formats.mock.calls[0][0] as Record<string, Blob>
    const html = await new Promise<string>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.readAsText(payload['text/html'])
    })
    expect(html).toContain('<strong>Clear answer</strong>')
    expect(html).not.toContain('Private question')
    const copied = document.createElement('div')
    copied.innerHTML = html
    expect(copied.querySelectorAll('a')).toHaveLength(1)
    expect(copied.querySelector('a')).toHaveAttribute('href', 'https://example.com/docs')
    expect(copied.textContent).toContain('Unsafe')
    expect(html).not.toContain('file:///private/document')
    expect(write).toHaveBeenCalledTimes(1)
  })

  it('clears copied feedback when a different answer arrives', async () => {
    vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined)
    const { rerender } = render(<AskAnswerCard answer="First" onClose={vi.fn()} />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'ask.copyAnswer' })))
    expect(screen.getByRole('button', { name: 'ask.copyAnswer' })).toHaveAttribute(
      'title',
      'ask.copied',
    )
    rerender(<AskAnswerCard answer="Second" onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'ask.copyAnswer' })).toHaveAttribute(
      'title',
      'ask.copy',
    )
  })

  it('ignores a late copy completion for a previous answer', async () => {
    let finishCopy: () => void = () => {}
    vi.spyOn(navigator.clipboard, 'writeText').mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishCopy = resolve
      }),
    )
    const { rerender } = render(<AskAnswerCard answer="First" onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'ask.copyAnswer' }))
    rerender(<AskAnswerCard answer="Second" onClose={vi.fn()} />)
    await act(async () => finishCopy())
    expect(screen.getByRole('button', { name: 'ask.copyAnswer' })).toHaveAttribute(
      'title',
      'ask.copy',
    )
  })

  it('collapses a new long prompt instead of keeping the previous expansion', () => {
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(100)
    const { rerender } = render(
      <AskAnswerCard prompt="First question" answer="First" onClose={vi.fn()} />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'ask.more' }))
    expect(screen.getByRole('button', { name: 'ask.showLess' })).toBeInTheDocument()
    rerender(<AskAnswerCard prompt="Second question" answer="Second" onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'ask.more' })).toBeInTheDocument()
  })

  it('expands the entire prompt and selection rather than clipping at a fixed height', () => {
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(2600)
    render(
      <AskAnswerCard
        prompt="Long question"
        selectedText="Long selection"
        answer="Answer"
        onClose={vi.fn()}
      />,
    )
    const content = screen.getByText('Long question').parentElement
    expect(content).toHaveStyle({ maxHeight: '44px' })
    fireEvent.click(screen.getByRole('button', { name: 'ask.more' }))
    expect(content).toHaveStyle({ maxHeight: '2600px' })
    fireEvent.click(screen.getByRole('button', { name: 'ask.showLess' }))
    expect(content).toHaveStyle({ maxHeight: '44px' })
  })

  it('remeasures an expanded prompt after resize and disconnects on unmount', () => {
    let height = 100
    let notifyResize = () => {}
    const observe = vi.fn()
    const disconnect = vi.fn()
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(() => height)
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: () => void) {
          notifyResize = callback
        }
        observe = observe
        disconnect = disconnect
      },
    )
    const { unmount } = render(
      <AskAnswerCard prompt="Resizable question" answer="Answer" onClose={vi.fn()} />,
    )
    const paragraph = screen.getByText('Resizable question')
    const content = paragraph.parentElement
    expect(observe).toHaveBeenCalledWith(content)
    expect(observe).toHaveBeenCalledWith(paragraph)
    fireEvent.click(screen.getByRole('button', { name: 'ask.more' }))
    expect(content).toHaveStyle({ maxHeight: '100px' })
    height = 3000
    act(() => notifyResize())
    expect(content).toHaveStyle({ maxHeight: '3000px' })
    fireEvent.click(screen.getByRole('button', { name: 'ask.showLess' }))
    expect(content).toHaveStyle({ maxHeight: '44px' })
    height = 4000
    act(() => notifyResize())
    expect(content).toHaveStyle({ maxHeight: '44px' })
    fireEvent.click(screen.getByRole('button', { name: 'ask.more' }))
    expect(content).toHaveStyle({ maxHeight: '4000px' })
    unmount()
    expect(disconnect).toHaveBeenCalledTimes(1)
  })
})
