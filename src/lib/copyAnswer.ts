import { renderedAnswerText } from './renderedAnswerText'

export async function copyAnswer(text: string, rendered?: HTMLElement | null): Promise<void> {
  if (rendered && typeof ClipboardItem !== 'undefined' && navigator.clipboard.write) {
    try {
      const html = rendered.innerHTML
      const plain = renderedAnswerText(rendered)
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
