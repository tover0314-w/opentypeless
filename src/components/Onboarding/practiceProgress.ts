import type { PracticePage } from './GuidedPracticeFlow'

export function practiceProgress(page: PracticePage): number {
  if (page.stage === 'microphone') return 50
  if (page.mode === 'dictate') {
    if (page.stage === 'introduction') return 56
    if (page.stage === 'shortcut') return 59
    return [62, 65, 68, 69][page.exampleIndex ?? 0] ?? 62
  }
  if (page.mode === 'translate') {
    if (page.stage === 'introduction') return 71
    if (page.stage === 'shortcut') return 74
    if (page.stage === 'language') return 77
    return 80
  }
  if (page.stage === 'introduction') return 83
  if (page.stage === 'shortcut') return 86
  return [88, 89, 91, 93][page.exampleIndex ?? 0] ?? 88
}
