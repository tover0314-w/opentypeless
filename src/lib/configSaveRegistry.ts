const identityPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export class ConfigSaveRegistry<Config> {
  private readonly submissions = new Map<string, Config>()

  constructor(private readonly capacity = 16) {
    if (!Number.isSafeInteger(capacity) || capacity < 1) {
      throw new Error('Configuration save capacity is invalid')
    }
  }

  register(saveId: string, config: Config): void {
    if (
      !identityPattern.test(saveId) ||
      this.submissions.has(saveId) ||
      this.submissions.size >= this.capacity
    ) {
      throw new Error('Configuration save registration rejected')
    }
    this.submissions.set(saveId, structuredClone(config))
  }

  snapshot(saveId: string): Config | null {
    if (!this.submissions.has(saveId)) return null
    return structuredClone(this.submissions.get(saveId)!)
  }

  retire(saveId: string): boolean {
    return this.submissions.delete(saveId)
  }
}

export const pendingConfigSaves = new ConfigSaveRegistry<import('../stores/appStore').AppConfig>()
