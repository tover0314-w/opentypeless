type SegmenterConstructor = new (
  locale: undefined,
  options: { granularity: 'grapheme' },
) => { segment: (text: string) => Iterable<{ segment: string }> }

export function textPreview(text: string, limit: number): string {
  const Segmenter = (Intl as typeof Intl & { Segmenter?: SegmenterConstructor }).Segmenter
  const segments = Segmenter
    ? new Segmenter(undefined, { granularity: 'grapheme' }).segment(text)
    : Array.from(text, (segment) => ({ segment }))
  const preview: string[] = []
  for (const item of segments) {
    if (preview.length >= limit) return `${preview.join('')}…`
    preview.push(item.segment)
  }
  return text
}
