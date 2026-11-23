import { afterEach, expect, it, vi } from 'vitest'
import { copyAnswerSelection } from '../copyAnswerSelection'

afterEach(() => {
  window.getSelection()?.removeAllRanges()
  document.body.replaceChildren()
})

function setup() {
  const root = document.createElement('div')
  root.innerHTML =
    '<p>Claim <a href="https://example.invalid">Source</a><span data-citation-group="true"><button>12</button></span>.</p>'
  document.body.append(root)
  const range = document.createRange()
  range.selectNodeContents(root)
  window.getSelection()?.addRange(range)
  const clipboard = { setData: vi.fn() }
  return { root, range, clipboard }
}

it('copies only selected content without inline controls or changing the page', () => {
  const { root, clipboard } = setup()
  const original = root.innerHTML
  expect(copyAnswerSelection(root, clipboard)).toBe(true)
  expect(clipboard.setData).toHaveBeenCalledWith('text/plain', 'Claim Source.')
  expect(clipboard.setData).toHaveBeenCalledWith(
    'text/html',
    '<p>Claim <a href="https://example.invalid">Source</a>.</p>',
  )
  expect(root.innerHTML).toBe(original)
})

it('removes a partial selection inside a citation control', () => {
  const { root, range, clipboard } = setup()
  const text = root.querySelector('button')!.firstChild!
  range.setStart(text, 0)
  range.setEnd(text, 1)
  expect(copyAnswerSelection(root, clipboard)).toBe(true)
  expect(clipboard.setData).toHaveBeenCalledWith('text/plain', '')
})

it.each(['start', 'end'])('trims a selection endpoint inside a citation: %s', (endpoint) => {
  const { root, range, clipboard } = setup()
  const paragraph = root.querySelector('p')!
  const controlText = root.querySelector('button')!.firstChild!
  if (endpoint === 'start') {
    range.setStart(controlText, 1)
    range.setEnd(paragraph.lastChild!, 1)
  } else {
    range.setStart(paragraph.firstChild!, 0)
    range.setEnd(controlText, 1)
  }
  expect(copyAnswerSelection(root, clipboard)).toBe(true)
  expect(clipboard.setData).toHaveBeenCalledWith(
    'text/plain',
    endpoint === 'start' ? '.' : 'Claim Source',
  )
})

it('does not intercept missing clipboard data or a collapsed selection', () => {
  const { root, range, clipboard } = setup()
  expect(copyAnswerSelection(root, null)).toBe(false)
  range.collapse(true)
  expect(copyAnswerSelection(root, clipboard)).toBe(false)
  expect(clipboard.setData).not.toHaveBeenCalled()
})

it('leaves ordinary selection copying to the browser', () => {
  const { root, range, clipboard } = setup()
  range.selectNodeContents(root.querySelector('a')!)
  expect(copyAnswerSelection(root, clipboard)).toBe(false)
  expect(clipboard.setData).not.toHaveBeenCalled()
})

it('does not intercept selection crossing into another surface', () => {
  const { root, range, clipboard } = setup()
  const outside = document.createElement('p')
  outside.textContent = 'Outside'
  document.body.append(outside)
  range.setEnd(outside.firstChild!, 3)
  expect(copyAnswerSelection(root, clipboard)).toBe(false)
  expect(clipboard.setData).not.toHaveBeenCalled()
})
