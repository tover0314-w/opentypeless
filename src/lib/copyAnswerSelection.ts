const CITATION_SELECTOR = '[data-citation-group="true"]'

function citationAncestor(node: Node): Element | null {
  return (node instanceof Element ? node : node.parentElement)?.closest(CITATION_SELECTOR) ?? null
}

export function copyAnswerSelection(
  root: HTMLElement,
  clipboard: Pick<DataTransfer, 'setData'> | null,
): boolean {
  const selection = root.ownerDocument.defaultView?.getSelection()
  if (!clipboard || !selection || selection.rangeCount !== 1 || selection.isCollapsed) return false
  const range = selection.getRangeAt(0)
  if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return false
  if (
    !Array.from(root.querySelectorAll(CITATION_SELECTOR)).some((node) => range.intersectsNode(node))
  ) {
    return false
  }
  const cleanRange = range.cloneRange()
  const startControl = citationAncestor(range.startContainer)
  const endControl = citationAncestor(range.endContainer)
  const clean = root.ownerDocument.createElement('div')
  if (!startControl || startControl !== endControl) {
    if (startControl) cleanRange.setStartAfter(startControl)
    if (endControl) cleanRange.setEndBefore(endControl)
    clean.append(cleanRange.cloneContents())
    clean.querySelectorAll(CITATION_SELECTOR).forEach((node) => node.remove())
  }
  clipboard.setData('text/plain', clean.textContent ?? '')
  clipboard.setData('text/html', clean.innerHTML)
  return true
}
