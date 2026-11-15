import { valuesEqual } from './configValuesEqual'

export interface ConfigCommit<Config> {
  configEpoch: string
  revision: number
  saveId: string | null
  config: Config
}

export type ConfigCommitDisposition =
  | 'accepted'
  | 'duplicate'
  | 'stale'
  | 'conflict'
  | 'wrongEpoch'
  | 'invalid'
  | 'uninitialized'

const identityPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export interface ConfigFieldGroup<Config> {
  fields: readonly (keyof Config)[]
  authority: 'editable' | 'native'
}

export function mergeConfigDraft<Config extends object>(
  baseline: Config,
  draft: Config,
  persisted: Config,
  groups: readonly ConfigFieldGroup<Config>[],
): Config {
  const keys = Object.keys(persisted)
  const covered = new Set<keyof Config>()
  for (const group of groups) {
    if (group.authority !== 'editable' && group.authority !== 'native') {
      throw new Error('Configuration field authority is invalid')
    }
    if (group.fields.length === 0) throw new Error('Configuration field group is empty')
    for (const field of group.fields) {
      if (
        covered.has(field) ||
        !Object.prototype.hasOwnProperty.call(persisted, field) ||
        !Object.prototype.hasOwnProperty.call(baseline, field) ||
        !Object.prototype.hasOwnProperty.call(draft, field)
      ) {
        throw new Error('Configuration field policy is invalid')
      }
      covered.add(field)
    }
  }
  if (
    covered.size !== keys.length ||
    Object.keys(baseline).length !== keys.length ||
    Object.keys(draft).length !== keys.length
  ) {
    throw new Error('Configuration field policy is incomplete')
  }
  const merged = structuredClone(persisted)
  for (const group of groups) {
    const edited = group.fields.some((field) => !valuesEqual(draft[field], baseline[field]))
    if (group.authority === 'editable' && edited) {
      for (const field of group.fields) merged[field] = structuredClone(draft[field])
    }
  }
  return merged
}

export class ConfigCommitCursor<Config> {
  private current: ConfigCommit<Config> | null = null

  constructor(private readonly validateConfig: (value: unknown) => value is Config) {}

  get snapshot(): ConfigCommit<Config> | null {
    return this.current ? structuredClone(this.current) : null
  }

  initialize(value: unknown): boolean {
    const commit = this.parse(value)
    if (!commit) return false
    if (this.current?.configEpoch === commit.configEpoch) {
      if (commit.revision < this.current.revision) return false
      if (commit.revision === this.current.revision) return valuesEqual(commit, this.current)
    }
    this.current = commit
    return true
  }

  accept(value: unknown): ConfigCommitDisposition {
    const commit = this.parse(value)
    if (!commit) return 'invalid'
    if (!this.current) return 'uninitialized'
    if (commit.configEpoch !== this.current.configEpoch) return 'wrongEpoch'
    if (commit.revision < this.current.revision) return 'stale'
    if (commit.revision === this.current.revision) {
      return valuesEqual(commit, this.current) ? 'duplicate' : 'conflict'
    }
    this.current = commit
    return 'accepted'
  }

  private parse(value: unknown): ConfigCommit<Config> | null {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null
    const record = value as Record<string, unknown>
    if (
      Object.keys(record).length !== 4 ||
      !['configEpoch', 'revision', 'saveId', 'config'].every((key) =>
        Object.prototype.hasOwnProperty.call(record, key),
      ) ||
      typeof record.configEpoch !== 'string' ||
      !identityPattern.test(record.configEpoch) ||
      typeof record.revision !== 'number' ||
      !Number.isSafeInteger(record.revision) ||
      record.revision < 0 ||
      (record.saveId !== null &&
        (typeof record.saveId !== 'string' || !identityPattern.test(record.saveId))) ||
      !this.validateConfig(record.config)
    ) {
      return null
    }
    try {
      return structuredClone({
        configEpoch: record.configEpoch,
        revision: record.revision,
        saveId: record.saveId,
        config: record.config,
      })
    } catch {
      return null
    }
  }
}
