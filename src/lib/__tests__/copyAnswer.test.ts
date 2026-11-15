import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { copyAnswer } from '../copyAnswer'

const write = vi.fn()
const writeText = vi.fn()
const item = vi.fn()

function readBlob(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  write.mockResolvedValue(undefined)
  writeText.mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { write, writeText },
  })
  vi.stubGlobal(
    'ClipboardItem',
    class {
      constructor(data: Record<string, Blob>) {
        item(data)
      }
    },
  )
})

afterEach(() => vi.unstubAllGlobals())

describe('answer clipboard formats', () => {
  it('preserves paragraphs, list markers and code in the actual plain-text payload', async () => {
    const rendered = document.createElement('div')
    rendered.innerHTML =
      '<p>First paragraph</p><p>Second paragraph</p><ul><li>Alpha</li><li>Beta</li></ul><pre><code>line one\n  line two</code></pre>'
    await copyAnswer('Original markdown', rendered)
    const formats = item.mock.calls[0][0] as Record<string, Blob>
    expect(await readBlob(formats['text/plain'])).toBe(
      'First paragraph\n\nSecond paragraph\n\n• Alpha\n• Beta\n\nline one\n  line two',
    )
    expect(await readBlob(formats['text/html'])).toBe(rendered.innerHTML)
  })

  it('keeps numbered list order and link labels in copied plain text', async () => {
    const rendered = document.createElement('div')
    rendered.innerHTML =
      '<ol start="3"><li><strong>First</strong></li><li><a href="https://example.com">Docs</a></li></ol>'
    await copyAnswer('Original markdown', rendered)
    const formats = item.mock.calls[0][0] as Record<string, Blob>
    expect(await readBlob(formats['text/plain'])).toBe('3. First\n4. Docs')
  })

  it('copies rendered HTML and plain text together when supported', async () => {
    const rendered = document.createElement('div')
    rendered.innerHTML = '<p><strong>Answer</strong></p>'
    await copyAnswer('**Answer**', rendered)
    expect(item).toHaveBeenCalledWith({
      'text/html': expect.any(Blob),
      'text/plain': expect.any(Blob),
    })
    expect(write).toHaveBeenCalledTimes(1)
    expect(writeText).not.toHaveBeenCalled()
  })

  it('falls back to the existing text when rich copying is rejected', async () => {
    write.mockRejectedValueOnce(new Error('format unsupported'))
    const rendered = document.createElement('div')
    rendered.innerHTML = '<p>Answer</p>'
    await copyAnswer('Answer', rendered)
    expect(writeText).toHaveBeenCalledWith('Answer')
  })

  it('keeps plain copying when no rendered content is supplied', async () => {
    await copyAnswer('Question')
    expect(writeText).toHaveBeenCalledWith('Question')
    expect(write).not.toHaveBeenCalled()
  })
})
