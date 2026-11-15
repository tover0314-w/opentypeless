export function waveformLevel(rms: number): number {
  if (!Number.isFinite(rms) || rms <= 0) return 0
  const decibels = 20 * Math.log10(rms)
  return Math.min(1, Math.max(0, (decibels + 60) / 48))
}
