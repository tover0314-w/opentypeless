import { renderedAnswerText } from './renderedAnswerText'

export async function copyAnswer(text: string, rendered?: HTMLElement | null): Promise<void> {
  if (rendered && typeof ClipboardItem !== 'undefined' && navigator.clipboard.write) {
    try {
      const clean = rendered.cloneNode(true) as HTMLElement
      clean.querySelectorAll('[data-citation-group="true"]').forEach((node) => node.remove())
      const html = clean.innerHTML
      const plain = renderedAnswerText(clean)
      await navigator.clipboard.write([
        new ClipboardItem({
          'text/html': new Blob([html], { type: 'text/html' }),
          'text/plain': new Blob([plain], { type: 'text/plain' }),
        }),
      ])
      return
    } catch {
      await navigator.clipboard.writeText(text)
      return
    }
  }
  await navigator.clipboard.writeText(text)
}
