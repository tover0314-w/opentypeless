export class ShortcutCaptureState {
  private held = new Set<string>()
  private chord: string[] = []
  private submitted = false

  press(key: string): string[] {
    this.held.add(key)
    if (this.submitted) return []
    if (!this.chord.includes(key)) this.chord.push(key)
    return [...this.chord]
  }

  release(key: string): string[] | null {
    if (!this.held.delete(key)) return null
    const result = !this.submitted && this.chord.length > 0 ? [...this.chord] : null
    this.submitted = true
    if (this.held.size === 0) this.cancel()
    return result
  }

  cancel(): void {
    this.held.clear()
    this.chord = []
    this.submitted = false
  }
}
